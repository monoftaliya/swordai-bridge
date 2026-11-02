package com.swordai.bridge.agent

import android.content.Context
import com.swordai.bridge.AppGraph
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.capture.ScreenCapture
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.log.LogEntry

/**
 * Аварийная остановка: мгновенно запрещает выполнение команд, прерывает текущую,
 * отклоняет ожидающие подтверждения и останавливает захват экрана.
 * Возобновить управление можно только на самом телефоне.
 */
object EmergencyStop {
    fun trigger(context: Context, source: String) {
        val wasEnabled = AppGraph.settings.current.controlEnabled
        AppGraph.settings.update { it.copy(controlEnabled = false) }
        AppGraph.executor.cancelAll("paused", "Управление остановлено пользователем ($source)")
        AppGraph.confirmations.denyAll()
        ScreenCapture.stop()
        AgentAccessibilityService.instance?.overlay?.hide()
        AppGraph.state.setCurrentAction(null)
        if (wasEnabled) {
            AppGraph.log.add(LogEntry.CAT_SECURITY, "Аварийная остановка", "Источник: $source", LogEntry.ST_WARN)
        }
        StatusReporter.event("emergency_stop", jsonOf("source" to source))
    }

    fun resume(context: Context) {
        AppGraph.settings.update { it.copy(controlEnabled = true) }
        AppGraph.log.add(LogEntry.CAT_SECURITY, "Управление возобновлено", "На телефоне", LogEntry.ST_OK)
        StatusReporter.event("control_resumed")
    }
}
