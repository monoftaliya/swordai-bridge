package com.swordai.bridge.agent

import com.swordai.bridge.core.protocol.Messages
import kotlinx.serialization.json.JsonObject

/** Результат выполнения одного инструмента на телефоне. */
sealed class ToolOutcome {
    data class Success(val data: JsonObject, val image: JsonObject? = null) : ToolOutcome()
    data class Failure(val code: String, val message: String) : ToolOutcome()

    fun toMessage(commandId: String): JsonObject = when (this) {
        is Success -> Messages.success(commandId, data, image)
        is Failure -> Messages.failure(commandId, code, message)
    }
}
