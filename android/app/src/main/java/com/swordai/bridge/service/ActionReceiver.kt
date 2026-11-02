package com.swordai.bridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swordai.bridge.AppGraph
import com.swordai.bridge.agent.EmergencyStop

/** Кнопки уведомлений: подтверждение действий, аварийная остановка, отключение. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_APPROVE -> intent.getStringExtra(EXTRA_ID)?.let { AppGraph.confirmations.resolve(it, true) }
            ACTION_DENY -> intent.getStringExtra(EXTRA_ID)?.let { AppGraph.confirmations.resolve(it, false) }
            ACTION_EMERGENCY_STOP -> EmergencyStop.trigger(context, "уведомление")
            ACTION_DISCONNECT -> BridgeService.stop(context)
        }
    }

    companion object {
        const val ACTION_APPROVE = "com.swordai.bridge.action.APPROVE"
        const val ACTION_DENY = "com.swordai.bridge.action.DENY"
        const val ACTION_EMERGENCY_STOP = "com.swordai.bridge.action.EMERGENCY_STOP"
        const val ACTION_DISCONNECT = "com.swordai.bridge.action.DISCONNECT"
        const val EXTRA_ID = "id"
    }
}
