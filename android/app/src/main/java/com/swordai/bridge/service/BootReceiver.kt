package com.swordai.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.swordai.bridge.AppGraph
import com.swordai.bridge.core.log.LogEntry

/**
 * Восстановление соединения после перезагрузки или обновления приложения —
 * только если пользователь включил автозапуск и соединение было активно.
 * Запуск foreground-сервиса из BOOT_COMPLETED разрешён системой (тип specialUse).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val settings = AppGraph.settings.current
        if (!settings.autoStart || !settings.serviceEnabled || AppGraph.pairing.current == null) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_START),
            )
            AppGraph.log.add(LogEntry.CAT_CONNECTION, "Автозапуск после перезагрузки", null, LogEntry.ST_INFO)
        } catch (e: Exception) {
            Log.w("BootReceiver", "Cannot start service: $e")
            AppGraph.log.add(LogEntry.CAT_CONNECTION, "Автозапуск не удался", e.message, LogEntry.ST_WARN)
        }
    }
}
