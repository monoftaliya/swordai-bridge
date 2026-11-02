package com.swordai.bridge.agent

import com.swordai.bridge.AppGraph
import com.swordai.bridge.core.protocol.Messages
import com.swordai.bridge.service.BridgeService
import com.swordai.bridge.service.StopTileService
import kotlinx.serialization.json.JsonObject

/** Сообщает серверу и системному интерфейсу (уведомление, плитка) об изменении состояния. */
object StatusReporter {
    fun report() {
        AppGraph.send(Messages.status(DeviceInfo.state(AppGraph.context)))
        BridgeService.refresh()
        StopTileService.requestUpdate(AppGraph.context)
    }

    fun event(name: String, detail: JsonObject? = null) {
        AppGraph.send(Messages.event(name, detail))
        report()
    }
}
