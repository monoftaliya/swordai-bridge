package com.swordai.bridge.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.swordai.bridge.AppGraph
import com.swordai.bridge.BuildConfig
import com.swordai.bridge.agent.StatusReporter
import com.swordai.bridge.agent.ToolOutcome
import com.swordai.bridge.core.commands.NotificationsArgs
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.security.Redactor
import com.swordai.bridge.data.NotificationSharing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Доступ к уведомлениям (выдаётся пользователем в системных настройках).
 * Уведомления защищённых приложений не передаются; коды подтверждения маскируются.
 */
class NotificationCollector : NotificationListenerService() {

    private data class Record(
        val key: String,
        val pkg: String,
        val title: String?,
        val text: String?,
        val time: Long,
        val ongoing: Boolean,
        val category: String?,
        var removed: Boolean = false,
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        _connected.value = true
        StatusReporter.report()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        _connected.value = false
        StatusReporter.report()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val record = sbn?.let { toRecord(it) } ?: return
        synchronized(recent) {
            recent.removeAll { it.key == record.key }
            recent.addFirst(record)
            while (recent.size > 100) recent.removeLast()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        synchronized(recent) { recent.firstOrNull { it.key == key }?.removed = true }
    }

    companion object {
        @Volatile
        private var instance: NotificationCollector? = null
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
        private val recent = ArrayDeque<Record>()

        private fun toRecord(sbn: StatusBarNotification): Record? {
            if (sbn.packageName == BuildConfig.APPLICATION_ID) return null
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = big ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (title.isNullOrBlank() && text.isNullOrBlank()) return null
            return Record(
                key = sbn.key,
                pkg = sbn.packageName,
                title = title?.take(200),
                text = text?.take(1000),
                time = sbn.postTime,
                ongoing = sbn.isOngoing,
                category = sbn.notification.category,
            )
        }

        fun query(args: NotificationsArgs): ToolOutcome {
            val sharing = AppGraph.settings.current.notificationSharing
            if (sharing == NotificationSharing.OFF) {
                return ToolOutcome.Failure("notifications_disabled", "Пользователь запретил передачу уведомлений в настройках приложения")
            }
            val service = instance
                ?: return ToolOutcome.Failure(
                    "notification_access_disabled",
                    "Нет доступа к уведомлениям. В приложении: Разрешения → Доступ к уведомлениям.",
                )
            val active = try {
                service.activeNotifications?.mapNotNull { toRecord(it) }.orEmpty()
            } catch (e: SecurityException) {
                emptyList()
            }
            val history = synchronized(recent) { recent.map { it.copy() } }
            val merged = (active + history.filter { h -> active.none { it.key == h.key } })
                .asSequence()
                .filter { args.includeOngoing || !it.ongoing }
                .filter { args.packageName == null || it.pkg == args.packageName }
                .filter { !AppGraph.settings.isProtected(it.pkg) }
                .sortedByDescending { it.time }
                .take(args.limit)
                .map { r ->
                    mapOf(
                        "app" to (AppGraph.apps.label(r.pkg) ?: r.pkg),
                        "package" to r.pkg,
                        "title" to Redactor.redact(r.title),
                        "text" to if (sharing == NotificationSharing.FULL) Redactor.redact(r.text) else null,
                        "time" to r.time,
                        "active" to active.any { it.key == r.key },
                        "ongoing" to r.ongoing,
                        "category" to r.category,
                    )
                }
                .toList()
            return ToolOutcome.Success(
                jsonOf(
                    "notifications" to merged,
                    "count" to merged.size,
                    "mode" to sharing.key,
                    "note" to "Коды подтверждения и номера карт скрыты; уведомления защищённых приложений не передаются.",
                ),
            )
        }
    }
}
