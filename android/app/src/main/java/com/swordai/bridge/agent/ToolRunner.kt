package com.swordai.bridge.agent

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.swordai.bridge.BuildConfig
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.accessibility.ScreenReader
import com.swordai.bridge.accessibility.UiActions
import com.swordai.bridge.apps.AppResolver
import com.swordai.bridge.capture.ScreenCapture
import com.swordai.bridge.core.commands.ArgsException
import com.swordai.bridge.core.commands.BackArgs
import com.swordai.bridge.core.commands.CaptureArgs
import com.swordai.bridge.core.commands.DeviceStatusArgs
import com.swordai.bridge.core.commands.HomeArgs
import com.swordai.bridge.core.commands.InputArgs
import com.swordai.bridge.core.commands.NotificationsArgs
import com.swordai.bridge.core.commands.OpenAppArgs
import com.swordai.bridge.core.commands.OpenFileArgs
import com.swordai.bridge.core.commands.ScreenInfoArgs
import com.swordai.bridge.core.commands.SearchFilesArgs
import com.swordai.bridge.core.commands.SequenceArgs
import com.swordai.bridge.core.commands.SwipeArgs
import com.swordai.bridge.core.commands.TapArgs
import com.swordai.bridge.core.commands.ToolArgs
import com.swordai.bridge.core.commands.ToolArgsParser
import com.swordai.bridge.core.commands.Tools
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.core.protocol.Command
import com.swordai.bridge.core.security.ActionContext
import com.swordai.bridge.core.security.SecurityPolicy
import com.swordai.bridge.data.ActionLog
import com.swordai.bridge.data.Settings
import com.swordai.bridge.files.FileGateway
import com.swordai.bridge.notifications.NotificationCollector
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Выполнение одной команды: проверка аргументов → проверка экрана → политика →
 * подтверждение (при необходимости) → действие. Набор действий фиксирован в коде.
 */
class ToolRunner(
    private val context: Context,
    private val settings: Settings,
    private val state: AgentState,
    private val log: ActionLog,
    private val confirmations: ConfirmationManager,
    private val apps: AppResolver,
    private val files: FileGateway,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private class Prepared(
        val action: ActionContext,
        val description: String,
        val execute: suspend () -> ToolOutcome,
    )

    private sealed class Prep {
        class Ok(val prepared: Prepared) : Prep()
        class Fail(val failure: ToolOutcome.Failure) : Prep()
    }

    suspend fun execute(command: Command, progress: (String) -> Unit): ToolOutcome {
        if (!settings.current.controlEnabled) return paused()
        val args = try {
            ToolArgsParser.parse(command.tool, command.args)
        } catch (e: ArgsException) {
            return ToolOutcome.Failure("bad_args", "Телефон отклонил аргументы: ${e.message}")
        }
        if (command.tool in Tools.NEEDS_SCREEN) screenGate()?.let { return it }
        return if (args is SequenceArgs) {
            runSequence(command, args, progress)
        } else {
            runStep(command, command.tool, args, progress, null)
        }
    }

    private suspend fun runSequence(command: Command, args: SequenceArgs, progress: (String) -> Unit): ToolOutcome {
        val steps = ArrayList<JsonObject>()
        var completed = 0
        var firstFailure: ToolOutcome.Failure? = null
        for ((index, step) in args.steps.withIndex()) {
            val label = "шаг ${index + 1}/${args.steps.size}"
            val outcome = when {
                !settings.current.controlEnabled -> paused()
                else -> screenGate() ?: runStep(command, step.tool, step.args, progress, label)
            }
            steps.add(
                jsonOf(
                    "index" to index + 1,
                    "tool" to step.tool,
                    "ok" to (outcome is ToolOutcome.Success),
                    "data" to (outcome as? ToolOutcome.Success)?.data,
                    "error" to (outcome as? ToolOutcome.Failure)?.let { jsonOf("code" to it.code, "message" to it.message) },
                ),
            )
            if (outcome is ToolOutcome.Success) {
                completed++
            } else {
                val failure = outcome as ToolOutcome.Failure
                if (firstFailure == null) firstFailure = failure
                if (args.stopOnError || failure.code == "paused") break
            }
            if (step.waitMs > 0 && index < args.steps.lastIndex) delay(step.waitMs.toLong())
        }
        val failure = firstFailure
        if (completed == 0 && failure != null) return failure
        return ToolOutcome.Success(jsonOf("total" to args.steps.size, "completed" to completed, "steps" to steps))
    }

    private suspend fun runStep(
        command: Command,
        tool: String,
        args: ToolArgs,
        progress: (String) -> Unit,
        stepLabel: String?,
    ): ToolOutcome {
        var prepared = when (val prep = prepare(tool, args)) {
            is Prep.Fail -> return prep.failure
            is Prep.Ok -> prep.prepared
        }
        val decision = policy().evaluate(prepared.action)
        val description = (stepLabel?.let { "$it: " } ?: "") + prepared.description
        if (!decision.allowed) {
            log.add(LogEntry.CAT_SECURITY, "Действие заблокировано", "$description — ${decision.reason}", LogEntry.ST_WARN)
            return ToolOutcome.Failure(decision.code ?: "policy_denied", decision.reason)
        }
        if (decision.needsConfirmation) {
            progress("awaiting_confirmation")
            state.updateCommand(command.commandId, "awaiting", description)
            log.add(LogEntry.CAT_SECURITY, "Запрошено подтверждение", description, LogEntry.ST_PENDING)
            val requestId = if (stepLabel == null) command.commandId else "${command.commandId}#${stepLabel.hashCode()}"
            val detail = decision.reason.ifBlank { "Действие требует подтверждения по вашим настройкам" }
            when (confirmations.request(requestId, description, detail, command.originClient)) {
                ConfirmationManager.Outcome.DENIED -> {
                    log.add(LogEntry.CAT_SECURITY, "Действие отклонено пользователем", description, LogEntry.ST_WARN)
                    return ToolOutcome.Failure("rejected_by_user", "Пользователь отклонил действие: $description")
                }
                ConfirmationManager.Outcome.TIMEOUT -> {
                    log.add(LogEntry.CAT_SECURITY, "Подтверждение не получено", description, LogEntry.ST_WARN)
                    return ToolOutcome.Failure("confirmation_timeout", "Пользователь не подтвердил действие за 90 секунд")
                }
                ConfirmationManager.Outcome.APPROVED -> {
                    log.add(LogEntry.CAT_SECURITY, "Действие разрешено пользователем", description, LogEntry.ST_OK)
                    progress("confirmed")
                }
            }
            if (!settings.current.controlEnabled) return paused()
            UiActions.dismissNotificationShade(context, AgentAccessibilityService.instance)
            // Экран мог измениться, пока пользователь думал: цель должна остаться прежней.
            prepared = awaitSameTarget(tool, args, prepared)
                ?: return ToolOutcome.Failure(
                    "screen_changed",
                    "Экран изменился во время подтверждения — действие не выполнено. Вызовите get_screen_info.",
                )
        }
        progress("executing")
        state.updateCommand(command.commandId, "running", description)
        state.setCurrentAction(description)
        val overlay = AgentAccessibilityService.instance?.overlay
        if (tool !in Tools.READ_ONLY) overlay?.show(description)
        return try {
            prepared.execute()
        } finally {
            state.setCurrentAction(null)
            overlay?.idle()
        }
    }

    private suspend fun awaitSameTarget(tool: String, args: ToolArgs, original: Prepared): Prepared? {
        if (tool in Tools.READ_ONLY || tool == Tools.OPEN_APP || tool == Tools.OPEN_FILE) {
            return (prepare(tool, args) as? Prep.Ok)?.prepared
        }
        repeat(10) {
            delay(300)
            val again = (prepare(tool, args) as? Prep.Ok)?.prepared
            if (again != null &&
                again.action.foregroundPackage == original.action.foregroundPackage &&
                again.action.targetLabel == original.action.targetLabel
            ) {
                return again
            }
        }
        return null
    }

    private fun policy(): SecurityPolicy =
        SecurityPolicy(BuildConfig.APPLICATION_ID, settings::isProtected, settings.current.confirmMode)

    private fun screenGate(): ToolOutcome.Failure? = when {
        !power.isInteractive -> ToolOutcome.Failure(
            "screen_off",
            "Экран телефона выключен. Попросите пользователя включить и разблокировать телефон.",
        )
        keyguard.isKeyguardLocked -> ToolOutcome.Failure(
            "screen_locked",
            "Телефон заблокирован. Обход блокировки невозможен — попросите пользователя разблокировать его.",
        )
        else -> null
    }

    private fun paused() = ToolOutcome.Failure(
        "paused",
        "Управление остановлено пользователем (аварийная остановка). Возобновить можно только на телефоне.",
    )

    private fun ok(action: ActionContext, description: String, execute: suspend () -> ToolOutcome): Prep =
        Prep.Ok(Prepared(action, description, execute))

    private fun fail(code: String, message: String): Prep = Prep.Fail(ToolOutcome.Failure(code, message))

    private fun noAccessibility(): Prep = fail(
        "accessibility_disabled",
        "Служба специальных возможностей SwordAI Bridge выключена. Пользователь должен включить её в приложении (Разрешения).",
    )

    private fun elementNotFound(id: String): Prep = fail(
        "element_not_found",
        "Элемент $id не найден: экран изменился или снимок устарел. Вызовите get_screen_info ещё раз.",
    )

    private fun appName(pkg: String?): String = pkg?.let { apps.label(it) ?: it } ?: "текущем приложении"

    private fun prepare(tool: String, args: ToolArgs): Prep {
        val service = AgentAccessibilityService.instance
        val fg = service?.foregroundPackage()
        return when (args) {
            is DeviceStatusArgs -> ok(ActionContext(tool, fg), "Состояние устройства") {
                ToolOutcome.Success(DeviceInfo.collect(context, args.includeApps))
            }
            is NotificationsArgs -> ok(ActionContext(tool, fg), "Чтение уведомлений") { NotificationCollector.query(args) }
            is SearchFilesArgs -> ok(ActionContext(tool, fg), "Поиск файлов «${args.query}»") { files.search(args) }
            is ScreenInfoArgs -> {
                val s = service ?: return noAccessibility()
                ok(ActionContext(tool, fg), "Чтение экрана") {
                    val info = ScreenReader.snapshot(s, args.maxElements, args.includeInvisible)
                    if (info == null) {
                        ToolOutcome.Failure("no_window", "Активное окно недоступно (экран меняется). Повторите через секунду.")
                    } else {
                        ToolOutcome.Success(info.toJson())
                    }
                }
            }
            is CaptureArgs -> {
                if (service == null) return noAccessibility()
                ok(ActionContext(tool, fg), "Скриншот экрана") { ScreenCapture.capture(context, args) }
            }
            is OpenAppArgs -> when (val resolution = apps.resolve(args)) {
                is AppResolver.Resolution.Failed -> fail(resolution.code, resolution.message)
                is AppResolver.Resolution.Found -> ok(
                    ActionContext(tool, fg, targetPackage = resolution.app.packageName),
                    "Открыть «${resolution.app.label}»",
                ) { apps.launch(resolution.app) }
            }
            is TapArgs -> prepareTap(service ?: return noAccessibility(), fg, args)
            is SwipeArgs -> {
                val s = service ?: return noAccessibility()
                val target = args.elementId?.let { id -> ScreenReader.resolve(s, id, null) ?: return elementNotFound(id) }
                val where = target?.let { " (${it.element.id})" }.orEmpty()
                ok(
                    ActionContext(tool, fg, targetLabel = target?.let { ScreenReader.labelFor(it) }),
                    "Прокрутка ${directionName(args.direction)}$where в ${appName(fg)}",
                ) { UiActions.swipe(s, args, target) }
            }
            is InputArgs -> {
                val s = service ?: return noAccessibility()
                val node: AccessibilityNodeInfo = if (args.elementId != null) {
                    ScreenReader.resolve(s, args.elementId, null)?.node ?: return elementNotFound(args.elementId)
                } else {
                    s.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        ?: return fail("no_focused_input", "Нет поля ввода в фокусе — укажите element_id поля")
                }
                val preview = args.text.replace('\n', ' ').take(40) + if (args.text.length > 40) "…" else ""
                ok(
                    ActionContext(tool, fg, targetIsPassword = node.isPassword, submit = args.submit),
                    "Ввести «$preview»" + (if (args.submit) " и отправить" else "") + " в ${appName(fg)}",
                ) { UiActions.input(node, args) }
            }
            BackArgs -> {
                val s = service ?: return noAccessibility()
                ok(ActionContext(tool, fg), "Кнопка «Назад»") {
                    globalAction(s, AccessibilityService.GLOBAL_ACTION_BACK, "back")
                }
            }
            HomeArgs -> {
                val s = service ?: return noAccessibility()
                ok(ActionContext(tool, fg), "Кнопка «Домой»") {
                    globalAction(s, AccessibilityService.GLOBAL_ACTION_HOME, "home")
                }
            }
            is OpenFileArgs -> ok(ActionContext(tool, fg), "Открыть файл") { files.open(args.fileId) }
            is SequenceArgs -> fail("bad_args", "Вложенные последовательности запрещены")
        }
    }

    private fun prepareTap(service: AgentAccessibilityService, fg: String?, args: TapArgs): Prep {
        val x = args.x
        val y = args.y
        val ref = when {
            args.elementId != null ->
                ScreenReader.resolve(service, args.elementId, args.snapshotId) ?: return elementNotFound(args.elementId)
            args.text != null -> {
                val (found, count) = ScreenReader.findByText(service, args.text, args.exact, args.index)
                found ?: return fail(
                    "element_not_found",
                    if (count == 0) "На экране нет элемента с текстом «${args.text}»"
                    else "Найдено совпадений: $count, а index=${args.index}",
                )
            }
            x != null && y != null -> ScreenReader.nodeAt(service, x, y)
            else -> return fail("bad_args", "Не указана цель нажатия")
        }
        val label = ref?.let { ScreenReader.labelFor(it) }
        val shown = label?.take(60) ?: if (x != null && y != null) "точка $x,$y" else "элемент"
        val action = ActionContext(
            tool = Tools.TAP,
            foregroundPackage = fg,
            targetLabel = label,
            targetKnown = label != null,
            targetIsPassword = ref?.isPassword == true,
        )
        val description = (if (args.longPress) "Долгое нажатие" else "Нажать") + " «$shown» в ${appName(fg)}"
        return if (x != null && y != null) {
            ok(action, description) { UiActions.tapPoint(service, x, y, args.longPress) }
        } else {
            val target = ref ?: return fail("element_not_found", "Цель нажатия не найдена")
            ok(action, description) { UiActions.tapNode(service, target, args.longPress) }
        }
    }

    private fun globalAction(service: AgentAccessibilityService, action: Int, name: String): ToolOutcome =
        if (UiActions.global(service, action)) {
            ToolOutcome.Success(jsonOf("pressed" to name))
        } else {
            ToolOutcome.Failure("action_failed", "Система не выполнила действие $name")
        }

    private fun directionName(direction: String): String = when (direction) {
        "up" -> "вверх"
        "down" -> "вниз"
        "left" -> "влево"
        else -> "вправо"
    }
}
