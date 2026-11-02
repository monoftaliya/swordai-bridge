package com.swordai.bridge.data

import android.content.Context
import com.swordai.bridge.core.security.ReplayGuard

/** Сохранение состояния защиты от повтора (последний seq и недавние id команд). */
class ReplayStore(context: Context) : ReplayGuard.Persistence {
    private val prefs = context.getSharedPreferences("replay", Context.MODE_PRIVATE)

    override fun load(): ReplayGuard.Snapshot? {
        if (!prefs.contains("last_seq")) return null
        val ids = prefs.getString("recent_ids", "").orEmpty().split(',').filter { it.isNotBlank() }
        return ReplayGuard.Snapshot(prefs.getLong("last_seq", 0L), ids)
    }

    override fun save(snapshot: ReplayGuard.Snapshot) {
        prefs.edit()
            .putLong("last_seq", snapshot.lastSeq)
            .putString("recent_ids", snapshot.recentIds.joinToString(","))
            .apply()
    }
}
