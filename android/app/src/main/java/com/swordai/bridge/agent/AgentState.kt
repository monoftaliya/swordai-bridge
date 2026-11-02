package com.swordai.bridge.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LinkStatus {
    /** Сервис выключен пользователем. */
    DISABLED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    /** Нет сети. */
    OFFLINE,
    /** Сервер не узнаёт устройство (отозвано или база сервера сброшена) — нужно новое сопряжение. */
    UNAUTHORIZED,
    /** Ключ сервера изменился — возможна подмена, соединение прекращено. */
    KEY_MISMATCH,
}

data class ConnectionInfo(
    val status: LinkStatus = LinkStatus.DISABLED,
    val since: Long = System.currentTimeMillis(),
    val lastError: String? = null,
    val serverVersion: String? = null,
    val nextRetryAt: Long? = null,
    val connectedAt: Long? = null,
)

data class Integration(
    val mcpEnabled: Boolean = false,
    val authorizedClients: Int = 0,
    val lastCallAt: Long? = null,
    val lastClient: String? = null,
    val lastTool: String? = null,
)

data class CommandRecord(
    val commandId: String,
    val tool: String,
    val summary: String,
    val client: String,
    val status: String,
    val time: Long,
    val detail: String? = null,
)

/** Наблюдаемое состояние агента для интерфейса и уведомления. */
class AgentState {
    private val _connection = MutableStateFlow(ConnectionInfo())
    val connection: StateFlow<ConnectionInfo> = _connection.asStateFlow()

    private val _integration = MutableStateFlow(Integration())
    val integration: StateFlow<Integration> = _integration.asStateFlow()

    private val _latency = MutableStateFlow<List<Long>>(emptyList())
    val latency: StateFlow<List<Long>> = _latency.asStateFlow()

    private val _commands = MutableStateFlow<List<CommandRecord>>(emptyList())
    val commands: StateFlow<List<CommandRecord>> = _commands.asStateFlow()

    /** Количество команд по минутам за последние 30 минут (последний элемент — текущая минута). */
    private val _activity = MutableStateFlow(List(ACTIVITY_MINUTES) { 0 })
    val activity: StateFlow<List<Int>> = _activity.asStateFlow()
    private var activityMinute = System.currentTimeMillis() / 60_000

    private val _currentAction = MutableStateFlow<String?>(null)
    val currentAction: StateFlow<String?> = _currentAction.asStateFlow()

    fun setConnection(status: LinkStatus, error: String? = null, nextRetryAt: Long? = null, serverVersion: String? = null) {
        _connection.update { old ->
            val now = System.currentTimeMillis()
            ConnectionInfo(
                status = status,
                since = if (old.status == status) old.since else now,
                lastError = error ?: if (status == LinkStatus.CONNECTED) null else old.lastError,
                serverVersion = serverVersion ?: old.serverVersion,
                nextRetryAt = nextRetryAt,
                connectedAt = when (status) {
                    LinkStatus.CONNECTED -> if (old.status == LinkStatus.CONNECTED) old.connectedAt else now
                    else -> null
                },
            )
        }
    }

    fun setIntegration(value: Integration) {
        _integration.value = value
    }

    fun recordLatency(ms: Long) {
        _latency.update { (it + ms).takeLast(LATENCY_SAMPLES) }
    }

    @Synchronized
    fun recordCommand(record: CommandRecord) {
        bumpActivity()
        _commands.update { (listOf(record) + it.filterNot { r -> r.commandId == record.commandId }).take(MAX_COMMANDS) }
    }

    fun updateCommand(commandId: String, status: String, detail: String? = null) {
        _commands.update { list ->
            list.map { if (it.commandId == commandId) it.copy(status = status, detail = detail ?: it.detail) else it }
        }
    }

    fun setCurrentAction(text: String?) {
        _currentAction.value = text
    }

    /** Сдвигает окно активности, чтобы график показывал нули в минуты без команд. */
    @Synchronized
    fun tickActivity() {
        shiftActivity(System.currentTimeMillis() / 60_000)
    }

    private fun bumpActivity() {
        val minute = System.currentTimeMillis() / 60_000
        shiftActivity(minute)
        _activity.update { list -> list.toMutableList().also { it[it.lastIndex] = it.last() + 1 } }
    }

    private fun shiftActivity(minute: Long) {
        val delta = (minute - activityMinute).toInt()
        if (delta <= 0) return
        activityMinute = minute
        _activity.update { list ->
            if (delta >= ACTIVITY_MINUTES) List(ACTIVITY_MINUTES) { 0 } else list.drop(delta) + List(delta) { 0 }
        }
    }

    companion object {
        const val ACTIVITY_MINUTES = 30
        const val LATENCY_SAMPLES = 60
        const val MAX_COMMANDS = 50
    }
}
