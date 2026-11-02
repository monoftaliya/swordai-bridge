package com.swordai.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.swordai.bridge.AppGraph
import com.swordai.bridge.agent.StatusReporter
import com.swordai.bridge.core.log.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Служба специальных возможностей: чтение экрана и жесты.
 * Работает только после явного включения пользователем в системных настройках.
 */
class AgentAccessibilityService : AccessibilityService() {

    lateinit var overlay: OverlayIndicator
        private set

    @Volatile var lastWindowPackage: String? = null
        private set
    @Volatile var lastWindowClass: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = OverlayIndicator(this)
        instance = this
        _connected.value = true
        AppGraph.log.add(LogEntry.CAT_SYSTEM, "Служба специальных возможностей включена", null, LogEntry.ST_OK)
        StatusReporter.report()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { lastWindowPackage = it }
            lastWindowClass = event.className?.toString()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (instance === this) {
            instance = null
            _connected.value = false
            if (::overlay.isInitialized) overlay.hide()
            AppGraph.log.add(LogEntry.CAT_SYSTEM, "Служба специальных возможностей выключена", null, LogEntry.ST_WARN)
            StatusReporter.report()
        }
    }

    /** Пакет приложения на переднем плане. */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString() ?: lastWindowPackage

    /** Выполнить жест и дождаться завершения. */
    suspend fun performGesture(gesture: GestureDescription): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
