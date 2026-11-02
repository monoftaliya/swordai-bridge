package com.swordai.bridge.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.swordai.bridge.AppGraph
import com.swordai.bridge.MainActivity
import com.swordai.bridge.agent.EmergencyStop
import com.swordai.bridge.agent.LinkStatus

/** Плитка быстрых настроек: одно касание — аварийная остановка; при остановке — открыть приложение. */
class StopTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (AppGraph.settings.current.controlEnabled) {
            EmergencyStop.trigger(this, "плитка в шторке")
            updateTile()
        } else {
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = AppGraph.settings.current.controlEnabled
        val connected = AppGraph.state.connection.value.status == LinkStatus.CONNECTED
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (enabled) "SwordAI: стоп" else "SwordAI остановлен"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !enabled -> "Нажмите, чтобы открыть"
                connected -> "Подключено"
                else -> "Нет связи"
            }
        }
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, StopTileService::class.java))
            } catch (e: Exception) {
                // плитка не добавлена пользователем
            }
        }
    }
}
