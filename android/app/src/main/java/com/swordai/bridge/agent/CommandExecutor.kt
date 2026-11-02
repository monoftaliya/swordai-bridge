package com.swordai.bridge.agent

import com.swordai.bridge.AppGraph
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.core.protocol.Command
import com.swordai.bridge.core.protocol.CommandVerifier
import com.swordai.bridge.core.protocol.Messages
import com.swordai.bridge.core.protocol.VerifyResult
import com.swordai.bridge.core.security.ReplayGuard
import com.swordai.bridge.core.stringOrNull
import com.swordai.bridge.data.ActionLog
import com.swordai.bridge.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Приём команд от сервера: проверка подписи и повторов, очередь (строго по одной),
 * выполнение, кэш результатов для повторной доставки.
 */
class CommandExecutor(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val state: AgentState,
    private val log: ActionLog,
    private val replay: ReplayGuard,
    private val runner: ToolRunner,
    private val sink: (JsonObject) -> Boolean,
) {
    @Volatile var verifier: CommandVerifier? = null
    @Volatile var serverTimeOffsetSec: Double = 0.0

    private val queue = Channel<Command>(capacity = QUEUE_CAPACITY)
    private val inProgress = ConcurrentHashMap<String, String>()
    private val results = object : LinkedHashMap<String, JsonObject>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonObject>?): Boolean = size > 60
    }
    private var worker: Job? = null
    @Volatile private var current: Job? = null
    @Volatile private var currentId: String? = null

    fun start() {
        if (worker != null) return
        worker = scope.launch {
            for (command in queue) {
                if (inProgress[command.commandId] == null) continue // отменена в очереди
                val job = scope.launch { process(command) }
                current = job
                currentId = command.commandId
                job.join()
                current = null
                currentId = null
            }
        }
    }

    /** Сообщения сервера типов command / cancel / control. */
    fun onServerMessage(message: JsonObject) {
        when (message.stringOrNull("type")) {
            "command" -> onEnvelope(message)
            "cancel" -> message.stringOrNull("command_id")?.let { cancel(it, "cancelled", "Отменено сервером") }
            "control" -> if (message.stringOrNull("action") == "pause") {
                EmergencyStop.trigger(AppGraph.context, "админ-панель сервера")
            }
        }
    }

    private fun onEnvelope(envelope: JsonObject) {
        val v = verifier ?: return
        when (val result = v.verify(envelope)) {
            is VerifyResult.Rejected -> {
                log.add(LogEntry.CAT_SECURITY, "Команда отклонена", "Причина: ${result.reason}", LogEntry.ST_ERROR)
                sink(Messages.event("command_rejected", jsonOf("reason" to result.reason)))
            }
            is VerifyResult.Ok -> accept(result.command)
        }
    }

    private fun accept(command: Command) {
        val id = command.commandId
        synchronized(results) { results[id] }?.let { cached ->
            sink(cached) // повторная доставка — отвечаем сохранённым результатом
            return
        }
        inProgress[id]?.let { st ->
            sink(Messages.progress(id, st))
            return
        }
        val serverNow = System.currentTimeMillis() / 1000.0 + serverTimeOffsetSec
        when (replay.check(command, serverNow)) {
            ReplayGuard.Verdict.DUPLICATE -> {
                sink(Messages.failure(id, "duplicate", "Команда уже была получена ранее"))
                return
            }
            ReplayGuard.Verdict.REPLAYED -> {
                log.add(LogEntry.CAT_SECURITY, "Повтор команды отклонён", "${command.tool}, seq ${command.seq}", LogEntry.ST_ERROR)
                finish(command, Messages.failure(id, "replayed", "Повторная или устаревшая команда отклонена"))
                return
            }
            ReplayGuard.Verdict.EXPIRED -> {
                finish(command, Messages.failure(id, "expired", "Срок действия команды истёк"))
                return
            }
            ReplayGuard.Verdict.FROM_FUTURE -> {
                finish(command, Messages.failure(id, "clock_skew", "Время команды в будущем — проверьте часы телефона"))
                return
            }
            ReplayGuard.Verdict.ACCEPT -> replay.accept(command)
        }
        sink(Messages.ack(id))
        inProgress[id] = "queued"
        state.recordCommand(CommandRecord(id, command.tool, summarize(command), command.originClient, "queued", System.currentTimeMillis()))
        if (queue.trySend(command).isFailure) {
            inProgress.remove(id)
            finish(command, Messages.failure(id, "busy", "Телефон занят: слишком много команд в очереди"))
        }
    }

    private suspend fun process(command: Command) {
        val id = command.commandId
        val outcome: ToolOutcome = try {
            withTimeout(EXECUTION_TIMEOUT_MS) {
                runner.execute(command) { st ->
                    inProgress[id] = st
                    sink(Messages.progress(id, st))
                }
            }
        } catch (e: TimeoutCancellationException) {
            ToolOutcome.Failure("timeout", "Команда выполнялась слишком долго")
        } catch (e: CancellationException) {
            ToolOutcome.Failure(cancelCode ?: "paused", cancelMessage ?: "Остановлено пользователем")
        } catch (e: Exception) {
            ToolOutcome.Failure("internal_error", "Ошибка на телефоне: ${e.javaClass.simpleName}: ${e.message}")
        }
        finish(command, outcome.toMessage(id))
    }

    @Volatile private var cancelCode: String? = null
    @Volatile private var cancelMessage: String? = null

    /** Отменить конкретную команду (в очереди или выполняющуюся). */
    fun cancel(commandId: String, code: String, message: String) {
        if (currentId == commandId) {
            cancelCode = code
            cancelMessage = message
            current?.cancel()
            return
        }
        if (inProgress.remove(commandId) != null) {
            sink(Messages.failure(commandId, code, message))
            state.updateCommand(commandId, "error", message)
        }
    }

    /** Аварийная остановка: прервать текущую и отклонить все команды в очереди. */
    fun cancelAll(code: String, message: String) {
        cancelCode = code
        cancelMessage = message
        current?.cancel()
        for (id in inProgress.keys.toList()) {
            if (id != currentId) cancel(id, code, message)
        }
    }

    private fun finish(command: Command, message: JsonObject) {
        val id = command.commandId
        synchronized(results) { results[id] = message }
        inProgress.remove(id)
        if (currentId == id) {
            cancelCode = null
            cancelMessage = null
        }
        sink(message)
        val ok = message["ok"]?.toString() == "true"
        val error = (message["error"] as? JsonObject)
        val code = error?.stringOrNull("code")
        val status = when {
            ok -> "ok"
            code in setOf("rejected_by_user", "confirmation_timeout", "paused", "blocked_app", "password_field", "policy_denied") -> "rejected"
            else -> "error"
        }
        state.updateCommand(id, status, error?.stringOrNull("message"))
        log.add(
            LogEntry.CAT_COMMAND,
            "${toolTitle(command.tool)} — ${if (ok) "выполнено" else "не выполнено"}",
            listOfNotNull(summarize(command), error?.stringOrNull("message"), "от ${command.originClient}").joinToString(" · "),
            if (ok) LogEntry.ST_OK else if (status == "rejected") LogEntry.ST_WARN else LogEntry.ST_ERROR,
        )
    }

    companion object {
        private const val QUEUE_CAPACITY = 16
        // Подтверждение (до 90 с) + выполнение последовательности.
        private const val EXECUTION_TIMEOUT_MS = 240_000L

        fun toolTitle(tool: String): String = when (tool) {
            "get_device_status" -> "Состояние устройства"
            "get_screen_info" -> "Чтение экрана"
            "capture_screen" -> "Скриншот"
            "get_notifications" -> "Уведомления"
            "search_files" -> "Поиск файлов"
            "open_app" -> "Открытие приложения"
            "tap_element" -> "Нажатие"
            "swipe_screen" -> "Прокрутка"
            "input_text" -> "Ввод текста"
            "press_back" -> "Назад"
            "press_home" -> "Домой"
            "open_file" -> "Открытие файла"
            "run_sequence" -> "Последовательность"
            else -> tool
        }

        fun summarize(command: Command): String {
            val a = command.args
            return when (command.tool) {
                "open_app" -> a.stringOrNull("app_name") ?: a.stringOrNull("package_name") ?: ""
                "tap_element" -> a.stringOrNull("text")?.let { "«$it»" } ?: a.stringOrNull("element_id")
                    ?: "${a["x"]},${a["y"]}"
                "input_text" -> "«${a.stringOrNull("text")?.take(30)}»"
                "swipe_screen" -> a.stringOrNull("direction") ?: ""
                "search_files" -> a.stringOrNull("query") ?: ""
                else -> ""
            }.ifEmpty { toolTitle(command.tool) }
        }
    }
}
