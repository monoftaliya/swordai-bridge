package com.swordai.bridge.net

import android.os.Build
import com.swordai.bridge.BuildConfig
import com.swordai.bridge.core.Ids
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.objectOrNull
import com.swordai.bridge.core.parseJsonObject
import com.swordai.bridge.core.protocol.CommandVerifier
import com.swordai.bridge.core.stringOrNull
import com.swordai.bridge.core.toCompactString
import com.swordai.bridge.data.Pairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class PairingException(message: String) : Exception(message)

/** REST-часть протокола: сопряжение, проверка и отвязка устройства. */
class PairingClient(baseClient: OkHttpClient) {
    private val http = baseClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun pair(serverInput: String, code: String, deviceName: String): Pairing = withContext(Dispatchers.IO) {
        val base = normalizeServerUrl(serverInput)
        val body = jsonOf(
            "code" to code.trim(),
            "device" to jsonOf(
                "name" to deviceName.ifBlank { Build.MODEL }.take(80),
                "manufacturer" to Build.MANUFACTURER.take(80),
                "model" to Build.MODEL.take(80),
                "android_release" to Build.VERSION.RELEASE.take(20),
                "sdk_int" to Build.VERSION.SDK_INT,
            ),
            "app_version" to BuildConfig.VERSION_NAME,
            "protocol" to CommandVerifier.PROTOCOL_VERSION,
        )
        val request = Request.Builder()
            .url("$base/api/v1/pair")
            .post(body.toCompactString().toRequestBody(jsonType))
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = parseJsonObject(raw)?.objectOrNull("error")?.stringOrNull("message")
                    throw PairingException(message ?: "Сервер ответил ошибкой ${response.code}")
                }
                raw
            }
        } catch (e: IOException) {
            throw PairingException("Сервер недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
        val json = parseJsonObject(text) ?: throw PairingException("Некорректный ответ сервера")
        val deviceId = json.stringOrNull("device_id") ?: throw PairingException("В ответе нет device_id")
        val token = json.stringOrNull("device_token") ?: throw PairingException("В ответе нет токена")
        val server = json.objectOrNull("server") ?: throw PairingException("В ответе нет данных сервера")
        if (!Ids.DEVICE_ID.matches(deviceId) || !token.startsWith("sdt_") || token.length > 200) {
            throw PairingException("Ответ сервера не прошёл проверку")
        }
        val publicKey = server.stringOrNull("signing_public_key") ?: throw PairingException("Сервер не передал ключ подписи")
        val verifier = try {
            CommandVerifier(publicKey, deviceId)
        } catch (e: Exception) {
            throw PairingException("Неверный ключ подписи сервера")
        }
        val reported = server.stringOrNull("signing_key_fingerprint")
        if (reported != null && reported != verifier.fingerprint) {
            throw PairingException("Отпечаток ключа не совпадает — сопряжение отменено")
        }
        val wsUrl = server.stringOrNull("ws_url") ?: defaultWsUrl(base)
        validateWsUrl(base, wsUrl)
        Pairing(
            serverUrl = base,
            wsUrl = wsUrl,
            deviceId = deviceId,
            deviceName = json.stringOrNull("device_name") ?: deviceName,
            token = token,
            serverPublicKey = publicKey,
            fingerprint = verifier.fingerprint,
            serverName = server.stringOrNull("name") ?: "SwordAI Bridge",
            serverVersion = server.stringOrNull("version") ?: "?",
            pairedAt = System.currentTimeMillis(),
        )
    }

    /** Сообщить серверу об отвязке (ошибки сети не мешают локальной отвязке). */
    suspend fun unpair(pairing: Pairing): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${pairing.serverUrl}/api/v1/device/unpair")
            .header("Authorization", "Bearer ${pairing.token}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        try {
            http.newCall(request).execute().use { it.isSuccessful }
        } catch (e: IOException) {
            false
        }
    }

    /** true — сервер узнаёт устройство; false — токен отозван или неизвестен; null — сеть недоступна. */
    suspend fun checkToken(pairing: Pairing): Boolean? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${pairing.serverUrl}/api/v1/device/me")
            .header("Authorization", "Bearer ${pairing.token}")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 401 -> false
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        fun normalizeServerUrl(input: String): String {
            var value = input.trim().trimEnd('/')
            if (value.isEmpty()) throw PairingException("Укажите адрес сервера")
            if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://$value"
            val url = value.toHttpUrlOrNull() ?: throw PairingException("Неверный адрес сервера")
            if (!url.isHttps && !BuildConfig.DEBUG) {
                throw PairingException("Нужен адрес https:// (http разрешён только в отладочной сборке для локального теста)")
            }
            if (url.query != null || url.fragment != null) throw PairingException("Адрес не должен содержать ? или #")
            return url.toString().trimEnd('/')
        }

        private fun defaultWsUrl(base: String): String =
            base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/device"

        /** WebSocket должен вести на тот же хост, что и сервер сопряжения, и быть защищён, если сервер на https. */
        private fun validateWsUrl(base: String, ws: String) {
            val baseUrl = base.toHttpUrlOrNull() ?: throw PairingException("Неверный адрес сервера")
            val httpLike = ws.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrlOrNull()
                ?: throw PairingException("Неверный адрес WebSocket")
            if (httpLike.host != baseUrl.host) throw PairingException("Адрес WebSocket указывает на другой сервер")
            if (baseUrl.isHttps && !ws.startsWith("wss://")) throw PairingException("Сервер на https должен использовать wss://")
        }
    }
}
