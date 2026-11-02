package com.swordai.bridge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Результат сопряжения с Cloud Bridge. */
data class Pairing(
    val serverUrl: String,
    val wsUrl: String,
    val deviceId: String,
    val deviceName: String,
    val token: String,
    val serverPublicKey: String,
    val fingerprint: String,
    val serverName: String,
    val serverVersion: String,
    val pairedAt: Long,
)

class PairingStore(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<Pairing?> = _flow.asStateFlow()
    val current: Pairing? get() = _flow.value

    private fun load(): Pairing? {
        val deviceId = prefs.getString("device_id", null) ?: return null
        val token = secrets.get("device_token") ?: return null
        return Pairing(
            serverUrl = prefs.getString("server_url", null) ?: return null,
            wsUrl = prefs.getString("ws_url", null) ?: return null,
            deviceId = deviceId,
            deviceName = prefs.getString("device_name", null) ?: "Android",
            token = token,
            serverPublicKey = prefs.getString("server_key", null) ?: return null,
            fingerprint = prefs.getString("fingerprint", null) ?: return null,
            serverName = prefs.getString("server_name", null) ?: "SwordAI Bridge",
            serverVersion = prefs.getString("server_version", null) ?: "?",
            pairedAt = prefs.getLong("paired_at", 0L),
        )
    }

    @Synchronized
    fun save(pairing: Pairing) {
        secrets.put("device_token", pairing.token)
        prefs.edit()
            .putString("server_url", pairing.serverUrl)
            .putString("ws_url", pairing.wsUrl)
            .putString("device_id", pairing.deviceId)
            .putString("device_name", pairing.deviceName)
            .putString("server_key", pairing.serverPublicKey)
            .putString("fingerprint", pairing.fingerprint)
            .putString("server_name", pairing.serverName)
            .putString("server_version", pairing.serverVersion)
            .putLong("paired_at", pairing.pairedAt)
            .commit()
        _flow.value = pairing
    }

    @Synchronized
    fun clear() {
        secrets.put("device_token", null)
        prefs.edit().clear().commit()
        _flow.value = null
    }
}
