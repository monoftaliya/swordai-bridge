package com.swordai.bridge.net

import com.swordai.bridge.BuildConfig
import com.swordai.bridge.agent.AgentState
import com.swordai.bridge.agent.Integration
import com.swordai.bridge.agent.LinkStatus
import com.swordai.bridge.core.booleanOrNull
import com.swordai.bridge.core.doubleOrNull
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.core.longOrNull
import com.swordai.bridge.core.net.Backoff
import com.swordai.bridge.core.objectOrNull
import com.swordai.bridge.core.parseJsonObject
import com.swordai.bridge.core.protocol.Messages
import com.swordai.bridge.core.stringOrNull
import com.swordai.bridge.core.toCompactString
import com.swordai.bridge.data.ActionLog
import com.swordai.bridge.data.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Постоянное WebSocket-соединение с Cloud Bridge.
 * Переподключение — экспоненциальная задержка с джиттером; при появлении сети — сразу.
 * Отпечаток ключа сервера сверяется с закреплённым при сопряжении.
 */
class BridgeConnection(
    private val http: OkHttpClient,
    private val pairing: Pairing,
    private val state: AgentState,
    private val log: ActionLog,
    private val scope: CoroutineScope,
    private val pairingClient: PairingClient,
    private val helloProvider: () -> JsonObject,
    private val onServerMessage: (JsonObject) -> Unit,
    private val onConnected: (serverTimeOffsetSec: Double) -> Unit,
) {
    private val lock = Any()
    private val backoff = Backoff()
    private var socket: WebSocket? = null
    private var generation = 0
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile private var running = false
    @Volatile private var welcomed = false
    @Volatile private var lastPongAt = 0L
    @Volatile var serverTimeOffsetSec: Double = 0.0
        private set

    val isConnected: Boolean get() = welcomed && socket != null

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        synchronized(lock) {
            generation++
            reconnectJob?.cancel()
            heartbeatJob?.cancel()
            socket?.close(1000, "client stopped")
            socket = null
            welcomed = false
        }
        state.setConnection(LinkStatus.DISABLED)
    }

    /** Немедленная попытка переподключения (например, сеть появилась). */
    fun reconnectNow() {
        if (!running || isConnected) return
        backoff.reset()
        connect()
    }

    fun onNetworkLost() {
        if (running && !isConnected) state.setConnection(LinkStatus.OFFLINE, "Нет сети")
    }

    fun send(message: JsonObject): Boolean {
        val ws = synchronized(lock) { socket } ?: return false
        if (!welcomed) return false
        return ws.send(message.toCompactString())
    }

    private fun connect() {
        synchronized(lock) {
            if (!running) return
            reconnectJob?.cancel()
            heartbeatJob?.cancel()
            generation++
            val gen = generation
            socket?.cancel()
            welcomed = false
            state.setConnection(LinkStatus.CONNECTING)
            val request = Request.Builder()
                .url(pairing.wsUrl)
                .header("Authorization", "Bearer ${pairing.token}")
                .header("User-Agent", "SwordAI-Bridge-Android/${BuildConfig.VERSION_NAME}")
                .build()
            socket = http.newWebSocket(request, Listener(gen))
        }
    }

    private fun isCurrent(gen: Int): Boolean = synchronized(lock) { gen == generation && running }

    private inner class Listener(private val gen: Int) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Ждём welcome от сервера.
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(gen)) return
            val message = parseJsonObject(text) ?: return
            when (message.stringOrNull("type")) {
                "welcome" -> onWelcome(message)
                "pong" -> onPong(message)
                "command", "cancel", "control" -> onServerMessage(message)
                "error" -> log.add(
                    LogEntry.CAT_CONNECTION,
                    "Сервер сообщил об ошибке",
                    message.stringOrNull("message"),
                    LogEntry.ST_WARN,
                )
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent(gen)) return
            handleDisconnect(code, reason, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent(gen)) return
            handleDisconnect(null, t.message ?: t.javaClass.simpleName, response?.code)
        }
    }

    private fun onWelcome(message: JsonObject) {
        val fingerprint = message.stringOrNull("key_fingerprint")
        if (fingerprint != null && fingerprint != pairing.fingerprint) {
            log.add(
                LogEntry.CAT_SECURITY,
                "Ключ сервера изменился",
                "Ожидался ${pairing.fingerprint}, получен $fingerprint. Соединение прервано; выполните сопряжение заново, если вы переустанавливали сервер.",
                LogEntry.ST_ERROR,
            )
            running = false
            synchronized(lock) {
                generation++
                socket?.close(1000, "key mismatch")
                socket = null
            }
            state.setConnection(LinkStatus.KEY_MISMATCH, "Ключ сервера не совпадает с закреплённым")
            return
        }
        val serverTime = message.doubleOrNull("server_time")
        if (serverTime != null) serverTimeOffsetSec = serverTime - System.currentTimeMillis() / 1000.0
        welcomed = true
        lastPongAt = System.currentTimeMillis()
        val wasReconnect = backoff.attempts > 0
        backoff.reset()
        state.setConnection(LinkStatus.CONNECTED, serverVersion = message.stringOrNull("server_version"))
        message.objectOrNull("integration")?.let { applyIntegration(it) }
        log.add(
            LogEntry.CAT_CONNECTION,
            if (wasReconnect) "Соединение восстановлено" else "Подключено к Cloud Bridge",
            pairing.serverUrl,
            LogEntry.ST_OK,
        )
        send(helloProvider())
        onConnected(serverTimeOffsetSec)
        val interval = (message.longOrNull("heartbeat_sec") ?: 20L).coerceIn(5L, 120L) * 1000
        startHeartbeat(interval)
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && running) {
                delay(intervalMs)
                if (!welcomed) break
                // Нет ответа на три пинга подряд — соединение «зависло», пересоздаём.
                if (System.currentTimeMillis() - lastPongAt > intervalMs * 3 + 5_000) {
                    log.add(LogEntry.CAT_CONNECTION, "Сервер не отвечает", "Переподключение", LogEntry.ST_WARN)
                    synchronized(lock) { socket?.cancel() }
                    break
                }
                send(Messages.ping(System.currentTimeMillis()))
            }
        }
    }

    private fun onPong(message: JsonObject) {
        lastPongAt = System.currentTimeMillis()
        message.longOrNull("t")?.let { sent ->
            val rtt = System.currentTimeMillis() - sent
            if (rtt in 0..120_000) state.recordLatency(rtt)
        }
        message.objectOrNull("integration")?.let { applyIntegration(it) }
        state.tickActivity()
    }

    private fun applyIntegration(obj: JsonObject) {
        val last = obj.objectOrNull("last_call")
        state.setIntegration(
            Integration(
                mcpEnabled = obj.booleanOrNull("mcp_enabled") ?: false,
                authorizedClients = (obj.longOrNull("authorized_clients") ?: 0L).toInt(),
                lastCallAt = last?.doubleOrNull("at")?.let { (it * 1000).toLong() },
                lastClient = last?.stringOrNull("client"),
                lastTool = last?.stringOrNull("tool"),
            ),
        )
    }

    private fun handleDisconnect(code: Int?, reason: String, httpCode: Int?) {
        val wasWelcomed = welcomed
        welcomed = false
        heartbeatJob?.cancel()
        if (!running) return
        when {
            code == CLOSE_REVOKED -> {
                markUnauthorized("Доступ устройства отозван на сервере")
                return
            }
            httpCode == 403 || httpCode == 401 -> {
                // Сервер отклонил рукопожатие: уточняем, отозван ли токен или это временное ограничение.
                scope.launch {
                    when (pairingClient.checkToken(pairing)) {
                        false -> markUnauthorized("Сервер не узнаёт это устройство — выполните сопряжение заново")
                        else -> scheduleReconnect("Сервер временно отклонил подключение ($httpCode)")
                    }
                }
                return
            }
        }
        if (wasWelcomed) {
            log.add(LogEntry.CAT_CONNECTION, "Соединение потеряно", reason.take(200), LogEntry.ST_WARN)
        }
        scheduleReconnect(reason)
    }

    private fun markUnauthorized(message: String) {
        running = false
        log.add(LogEntry.CAT_SECURITY, "Сопряжение недействительно", message, LogEntry.ST_ERROR)
        state.setConnection(LinkStatus.UNAUTHORIZED, message)
    }

    private fun scheduleReconnect(reason: String) {
        synchronized(lock) {
            if (!running) return
            val delayMs = backoff.nextDelayMs()
            val retryAt = System.currentTimeMillis() + delayMs
            state.setConnection(LinkStatus.RECONNECTING, reason.take(200), retryAt)
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                connect()
            }
        }
    }

    companion object {
        const val CLOSE_REVOKED = 4403
    }
}
