package com.swordai.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.swordai.bridge.agent.ToolOutcome
import com.swordai.bridge.core.commands.InputArgs
import com.swordai.bridge.core.commands.SwipeArgs
import com.swordai.bridge.core.jsonOf

/** Действия с интерфейсом через службу специальных возможностей. */
object UiActions {
    private const val TAP_MS = 60L
    private const val LONG_PRESS_MS = 650L

    suspend fun tapNode(service: AgentAccessibilityService, ref: NodeRef, longPress: Boolean): ToolOutcome {
        val node = ref.node
        var method = "gesture"
        if (!longPress) {
            val clickable = ancestorOrSelf(node) { it.isClickable && it.isEnabled }
            if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) method = "click"
        } else {
            val longClickable = ancestorOrSelf(node) { it.isLongClickable && it.isEnabled }
            if (longClickable != null && longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                method = "long_click"
            }
        }
        val (x, y) = visibleCenter(service, ref)
        if (method == "gesture" && !tapAt(service, x, y, longPress)) {
            return ToolOutcome.Failure("gesture_failed", "Система не выполнила жест нажатия")
        }
        service.overlay.flashTap(x, y)
        return ToolOutcome.Success(
            jsonOf(
                "method" to method,
                "x" to x,
                "y" to y,
                "target" to jsonOf(
                    "cls" to ref.element.cls,
                    "text" to ref.element.text,
                    "desc" to ref.element.desc,
                    "rid" to ref.element.resourceId,
                ),
            ),
        )
    }

    suspend fun tapPoint(service: AgentAccessibilityService, x: Int, y: Int, longPress: Boolean): ToolOutcome {
        val metrics = ScreenMetrics.get(service)
        if (x >= metrics.width || y >= metrics.height) {
            return ToolOutcome.Failure("out_of_bounds", "Точка ($x, $y) вне экрана ${metrics.width}×${metrics.height}")
        }
        if (!tapAt(service, x, y, longPress)) return ToolOutcome.Failure("gesture_failed", "Система не выполнила жест")
        service.overlay.flashTap(x, y)
        return ToolOutcome.Success(jsonOf("method" to "gesture", "x" to x, "y" to y))
    }

    suspend fun swipe(service: AgentAccessibilityService, args: SwipeArgs, target: NodeRef?): ToolOutcome {
        if (target != null) {
            val scrollable = ancestorOrSelf(target.node) { it.isScrollable }
                ?: return ToolOutcome.Failure("not_scrollable", "Элемент ${target.element.id} нельзя прокрутить")
            val action = when (args.direction) {
                "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            val scrolled = scrollable.performAction(action)
            return ToolOutcome.Success(jsonOf("method" to "scroll_action", "scrolled" to scrolled))
        }
        val m = ScreenMetrics.get(service)
        val half = (args.distance * 0.8 / 2).toFloat()
        val w = m.width.toFloat()
        val h = m.height.toFloat()
        val (sx, sy, ex, ey) = when (args.direction) {
            "up" -> listOf(w / 2, h * (0.5f + half), w / 2, h * (0.5f - half))
            "down" -> listOf(w / 2, h * (0.5f - half), w / 2, h * (0.5f + half))
            "left" -> listOf(w * (0.5f + half), h / 2, w * (0.5f - half), h / 2)
            else -> listOf(w * (0.5f - half), h / 2, w * (0.5f + half), h / 2)
        }
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, args.durationMs.toLong()))
            .build()
        service.overlay.setTouchable(false)
        val ok = try {
            service.performGesture(gesture)
        } finally {
            service.overlay.setTouchable(true)
        }
        if (!ok) return ToolOutcome.Failure("gesture_failed", "Система не выполнила жест прокрутки")
        return ToolOutcome.Success(jsonOf("method" to "gesture", "direction" to args.direction))
    }

    fun input(target: AccessibilityNodeInfo, args: InputArgs): ToolOutcome {
        if (!target.isEditable) return ToolOutcome.Failure("not_editable", "Элемент не является полем ввода")
        if (target.isPassword) return ToolOutcome.Failure("password_field", "Ввод в поля паролей запрещён")
        val existing = if (args.mode == "append" && !target.isShowingHintText) target.text?.toString().orEmpty() else ""
        val value = existing + args.text
        if (!target.isFocused) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val bundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (!target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
            return ToolOutcome.Failure("input_failed", "Поле не приняло текст")
        }
        var submitted = false
        if (args.submit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                submitted = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            if (!submitted) {
                return ToolOutcome.Failure(
                    "submit_failed",
                    "Текст введён, но клавишу «Ввод» нажать не удалось (нужен Android 11+ и поддержка поля). " +
                        "Нажмите кнопку отправки через tap_element.",
                )
            }
        }
        return ToolOutcome.Success(jsonOf("typed" to args.text.length, "mode" to args.mode, "submitted" to submitted))
    }

    fun global(service: AccessibilityService, action: Int): Boolean = service.performGlobalAction(action)

    /** Свернуть шторку уведомлений (после подтверждения из уведомления), чтобы она не перекрыла жест. */
    @Suppress("DEPRECATION")
    fun dismissNotificationShade(context: Context, service: AccessibilityService?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    private suspend fun tapAt(service: AgentAccessibilityService, x: Int, y: Int, longPress: Boolean): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, if (longPress) LONG_PRESS_MS else TAP_MS))
            .build()
        service.overlay.setTouchable(false)
        return try {
            service.performGesture(gesture)
        } finally {
            service.overlay.setTouchable(true)
        }
    }

    private fun visibleCenter(service: AgentAccessibilityService, ref: NodeRef): Pair<Int, Int> {
        val m = ScreenMetrics.get(service)
        val rect = Rect(ref.element.left, ref.element.top, ref.element.right, ref.element.bottom)
        if (!rect.intersect(0, 0, m.width, m.height)) return ref.element.centerX to ref.element.centerY
        return rect.centerX() to rect.centerY()
    }

    private fun ancestorOrSelf(node: AccessibilityNodeInfo, maxUp: Int = 5, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var level = 0
        while (current != null && level <= maxUp) {
            if (predicate(current)) return current
            current = current.parent
            level++
        }
        return null
    }
}
