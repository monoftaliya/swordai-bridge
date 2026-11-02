package com.swordai.bridge.core.protocol

import com.swordai.bridge.core.jsonOf
import kotlinx.serialization.json.JsonObject

/** Сообщения от телефона к серверу (протокол v1, см. docs/PROTOCOL.md). */
object Messages {
    fun hello(appVersion: String, device: JsonObject, capabilities: List<String>, state: JsonObject): JsonObject =
        jsonOf(
            "type" to "hello",
            "protocol" to CommandVerifier.PROTOCOL_VERSION,
            "app_version" to appVersion,
            "device" to device,
            "capabilities" to capabilities,
            "state" to state,
        )

    fun ping(timeMs: Long): JsonObject = jsonOf("type" to "ping", "t" to timeMs)

    fun status(state: JsonObject): JsonObject = jsonOf("type" to "status", "state" to state)

    fun ack(commandId: String): JsonObject = jsonOf("type" to "ack", "command_id" to commandId)

    fun progress(commandId: String, state: String): JsonObject =
        jsonOf("type" to "progress", "command_id" to commandId, "state" to state)

    fun success(commandId: String, data: JsonObject, image: JsonObject? = null): JsonObject =
        jsonOf("type" to "result", "command_id" to commandId, "ok" to true, "data" to data, "image" to image)

    fun failure(commandId: String, code: String, message: String): JsonObject =
        jsonOf(
            "type" to "result",
            "command_id" to commandId,
            "ok" to false,
            "error" to jsonOf("code" to code, "message" to message.take(1000)),
        )

    fun event(name: String, detail: JsonObject? = null): JsonObject =
        jsonOf("type" to "event", "event" to name, "detail" to detail)
}
