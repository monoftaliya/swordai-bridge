package com.swordai.bridge.core

import com.swordai.bridge.core.protocol.CommandVerifier
import com.swordai.bridge.core.protocol.VerifyResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CommandVerifierTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicB64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
    private val verifier = CommandVerifier(publicB64, "dev_TestDevice0001")

    private fun sign(payload: String): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(keyPair.private)
        s.update(payload.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(s.sign())
    }

    private fun payload(deviceId: String = "dev_TestDevice0001", seq: Int = 1, v: Int = 1, id: String = "cmd_TestCommand001") =
        """{"args":{"text":"Привет"},"command_id":"$id","device_id":"$deviceId","expires_at":2000000000.5,""" +
            """"issued_at":1999999900.25,"origin":{"client":"ChatGPT","source":"mcp"},"seq":$seq,"tool":"input_text","v":$v}"""

    private fun envelope(payload: String, sig: String) =
        JsonObject(mapOf("type" to JsonPrimitive("command"), "payload" to JsonPrimitive(payload), "sig" to JsonPrimitive(sig)))

    @Test
    fun acceptsValidSignature() {
        val p = payload()
        val result = verifier.verify(envelope(p, sign(p)))
        assertTrue(result is VerifyResult.Ok)
        val command = (result as VerifyResult.Ok).command
        assertEquals("input_text", command.tool)
        assertEquals(1L, command.seq)
        assertEquals("ChatGPT", command.originClient)
        assertEquals("Привет", command.args.stringOrNull("text"))
    }

    @Test
    fun rejectsTamperedPayload() {
        val p = payload()
        val result = verifier.verify(envelope(p.replace("Привет", "Пока"), sign(p)))
        assertEquals(VerifyResult.Rejected("invalid_signature"), result)
    }

    @Test
    fun rejectsForeignKey() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(other.private) }
        val p = payload()
        s.update(p.toByteArray())
        val result = verifier.verify(envelope(p, Base64.getEncoder().encodeToString(s.sign())))
        assertEquals(VerifyResult.Rejected("invalid_signature"), result)
    }

    @Test
    fun rejectsWrongDeviceAndVersion() {
        val p1 = payload(deviceId = "dev_SomeoneElse001")
        assertEquals(VerifyResult.Rejected("wrong_device"), verifier.verify(envelope(p1, sign(p1))))
        val p2 = payload(v = 2)
        assertEquals(VerifyResult.Rejected("unsupported_version"), verifier.verify(envelope(p2, sign(p2))))
        val p3 = payload(id = "cmd_../../x")
        assertEquals(VerifyResult.Rejected("malformed"), verifier.verify(envelope(p3, sign(p3))))
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(VerifyResult.Rejected("malformed"), verifier.verify(JsonObject(emptyMap())))
        assertEquals(VerifyResult.Rejected("invalid_signature"), verifier.verify(envelope(payload(), "!!!not-base64")))
    }

    @Test
    fun verifiesSignatureMadeByPythonServer() {
        val text = javaClass.classLoader!!.getResource("interop/signed_command.json")!!.readText()
        val fixture = parseJsonObject(text)!!
        val pythonVerifier = CommandVerifier(fixture.stringOrNull("public_key")!!, fixture.stringOrNull("device_id")!!)
        assertEquals(fixture.stringOrNull("fingerprint"), pythonVerifier.fingerprint)
        val result = pythonVerifier.verify(fixture.objectOrNull("envelope")!!)
        assertTrue("Python signature must verify in Kotlin: $result", result is VerifyResult.Ok)
        val command = (result as VerifyResult.Ok).command
        assertEquals("Отправить «сообщение» ✓", command.args.stringOrNull("text"))
        assertEquals(7L, command.seq)
        assertEquals(1767225720.123, command.expiresAt, 0.0001)
    }
}
