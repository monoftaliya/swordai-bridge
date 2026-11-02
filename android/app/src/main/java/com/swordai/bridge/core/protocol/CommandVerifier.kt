package com.swordai.bridge.core.protocol

import com.swordai.bridge.core.Ids
import com.swordai.bridge.core.doubleOrNull
import com.swordai.bridge.core.longOrNull
import com.swordai.bridge.core.objectOrNull
import com.swordai.bridge.core.parseJsonObject
import com.swordai.bridge.core.stringOrNull
import kotlinx.serialization.json.JsonObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Проверенная команда от сервера. */
data class Command(
    val commandId: String,
    val deviceId: String,
    val seq: Long,
    val tool: String,
    val args: JsonObject,
    val issuedAt: Double,
    val expiresAt: Double,
    val originSource: String,
    val originClient: String,
)

sealed class VerifyResult {
    data class Ok(val command: Command) : VerifyResult()
    data class Rejected(val reason: String) : VerifyResult()
}

/**
 * Проверка подписи ECDSA P-256 (SHA256withECDSA, DER) над точной строкой payload.
 * Открытый ключ сервера закреплён при сопряжении: команды от любого другого
 * источника отбрасываются ещё до разбора JSON.
 */
class CommandVerifier(serverPublicKeyB64: String, private val deviceId: String) {
    private val publicKey: PublicKey
    val fingerprint: String

    init {
        val der = Base64.getDecoder().decode(serverPublicKeyB64)
        publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        fingerprint = fingerprintOf(der)
    }

    fun verify(envelope: JsonObject): VerifyResult {
        val payloadText = envelope.stringOrNull("payload") ?: return VerifyResult.Rejected("malformed")
        val signatureB64 = envelope.stringOrNull("sig") ?: return VerifyResult.Rejected("malformed")
        if (payloadText.length > MAX_PAYLOAD_CHARS || signatureB64.length > 512) {
            return VerifyResult.Rejected("malformed")
        }
        val signatureBytes = try {
            Base64.getDecoder().decode(signatureB64)
        } catch (e: IllegalArgumentException) {
            return VerifyResult.Rejected("invalid_signature")
        }
        val valid = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(payloadText.toByteArray(Charsets.UTF_8))
            verifier.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
        if (!valid) return VerifyResult.Rejected("invalid_signature")

        val payload = parseJsonObject(payloadText) ?: return VerifyResult.Rejected("malformed")
        val version = payload.longOrNull("v") ?: return VerifyResult.Rejected("malformed")
        if (version != PROTOCOL_VERSION.toLong()) return VerifyResult.Rejected("unsupported_version")
        val commandId = payload.stringOrNull("command_id") ?: return VerifyResult.Rejected("malformed")
        val targetDevice = payload.stringOrNull("device_id") ?: return VerifyResult.Rejected("malformed")
        val seq = payload.longOrNull("seq") ?: return VerifyResult.Rejected("malformed")
        val tool = payload.stringOrNull("tool") ?: return VerifyResult.Rejected("malformed")
        val args = payload.objectOrNull("args") ?: return VerifyResult.Rejected("malformed")
        val issuedAt = payload.doubleOrNull("issued_at") ?: return VerifyResult.Rejected("malformed")
        val expiresAt = payload.doubleOrNull("expires_at") ?: return VerifyResult.Rejected("malformed")
        if (!Ids.COMMAND_ID.matches(commandId) || seq < 1 || !TOOL_NAME.matches(tool)) {
            return VerifyResult.Rejected("malformed")
        }
        if (targetDevice != deviceId) return VerifyResult.Rejected("wrong_device")
        val origin = payload.objectOrNull("origin")
        return VerifyResult.Ok(
            Command(
                commandId = commandId,
                deviceId = targetDevice,
                seq = seq,
                tool = tool,
                args = args,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                originSource = origin?.stringOrNull("source")?.take(40) ?: "unknown",
                originClient = origin?.stringOrNull("client")?.take(80) ?: "unknown",
            ),
        )
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        private const val MAX_PAYLOAD_CHARS = 512 * 1024
        private val TOOL_NAME = Regex("^[a-z_]{3,40}$")

        /** Отпечаток в том же формате, что показывает сервер: 8 групп по 4 hex-символа. */
        fun fingerprintOf(spkiDer: ByteArray): String {
            val hex = MessageDigest.getInstance("SHA-256").digest(spkiDer)
                .joinToString("") { "%02X".format(it) }
                .take(32)
            return hex.chunked(4).joinToString(" ")
        }
    }
}
