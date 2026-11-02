package com.swordai.bridge.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swordai.bridge.AppGraph
import com.swordai.bridge.BuildConfig
import com.swordai.bridge.agent.DeviceInfo
import com.swordai.bridge.agent.LinkStatus
import com.swordai.bridge.capture.ScreenCapture
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.core.protocol.CommandVerifier
import com.swordai.bridge.core.protocol.Messages
import com.swordai.bridge.net.BridgeConnection
import com.swordai.bridge.net.PairingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Foreground-сервис постоянного соединения с Cloud Bridge.
 * Тип specialUse (Android 14+), а на время захвата экрана — дополнительно mediaProjection.
 * Пока сервис работает, показывается уведомление с кнопками «Стоп» и «Отключить».
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connection: BridgeConnection? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var projectionType = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        enterForeground()
        observeState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        enterForeground()
        ensureConnection()
        return START_STICKY
    }

    private fun foregroundTypes(): Int {
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (projectionType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return types
    }

    private fun enterForeground() {
        ServiceCompat.startForeground(this, Notifications.SERVICE_NOTIFICATION_ID, buildNotification(), foregroundTypes())
    }

    /** Добавить/убрать тип mediaProjection. Вызывается при старте и остановке захвата экрана. */
    fun setProjectionForeground(active: Boolean) {
        if (projectionType == active) return
        projectionType = active
        enterForeground()
    }

    private fun ensureConnection() {
        val pairing = AppGraph.pairing.current
        if (pairing == null) {
            AppGraph.log.add(LogEntry.CAT_CONNECTION, "Нет сопряжения", "Сервис остановлен", LogEntry.ST_WARN)
            shutdown()
            return
        }
        if (connection != null) {
            connection?.reconnectNow()
            return
        }
        val verifier = try {
            CommandVerifier(pairing.serverPublicKey, pairing.deviceId)
        } catch (e: Exception) {
            AppGraph.log.add(LogEntry.CAT_SECURITY, "Повреждён ключ сервера", "Выполните сопряжение заново", LogEntry.ST_ERROR)
            AppGraph.state.setConnection(LinkStatus.KEY_MISMATCH, "Повреждён сохранённый ключ сервера")
            return
        }
        AppGraph.executor.verifier = verifier
        val conn = BridgeConnection(
            http = AppGraph.http,
            pairing = pairing,
            state = AppGraph.state,
            log = AppGraph.log,
            scope = AppGraph.scope,
            pairingClient = PairingClient(AppGraph.http),
            helloProvider = {
                Messages.hello(
                    BuildConfig.VERSION_NAME,
                    DeviceInfo.device(),
                    DeviceInfo.capabilities(),
                    DeviceInfo.state(applicationContext),
                )
            },
            onServerMessage = { AppGraph.executor.onServerMessage(it) },
            onConnected = { offset -> AppGraph.executor.serverTimeOffsetSec = offset },
        )
        connection = conn
        AppGraph.attachConnection(conn)
        conn.start()
        registerNetworkCallback()
        applyWakeLock(AppGraph.settings.current.highAvailability)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connection?.reconnectNow()
            }

            override fun onLost(network: Network) {
                connection?.onNetworkLost()
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            // слишком много колбэков у процесса — переподключение всё равно сработает по таймеру
        }
    }

    /** Режим «Высокая доступность»: процессор не засыпает, пока есть соединение (тратит заряд). */
    private fun applyWakeLock(enabled: Boolean) {
        if (enabled && wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SwordAIBridge:connection").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!enabled) {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeState() {
        combine(AppGraph.state.connection, AppGraph.settings.flow, AppGraph.state.currentAction) { conn, settings, action ->
            Triple(conn.status, settings.controlEnabled, action)
        }
            .distinctUntilChanged()
            .debounce(300)
            .onEach { updateNotification() }
            .launchIn(scope)
        AppGraph.settings.flow
            .map { it.highAvailability }
            .distinctUntilChanged()
            .onEach { if (connection != null) applyWakeLock(it) }
            .launchIn(scope)
    }

    private fun buildNotification() = run {
        val conn = AppGraph.state.connection.value
        val settings = AppGraph.settings.current
        val action = AppGraph.state.currentAction.value
        val text = when {
            !settings.controlEnabled -> "Управление остановлено. Команды ИИ отклоняются."
            action != null -> "Выполняется: $action"
            else -> when (conn.status) {
                LinkStatus.CONNECTED -> "Подключено к Cloud Bridge · ожидание команд"
                LinkStatus.CONNECTING -> "Подключение…"
                LinkStatus.RECONNECTING -> "Нет связи с сервером, переподключение…"
                LinkStatus.OFFLINE -> "Нет сети, ожидание подключения"
                LinkStatus.UNAUTHORIZED -> "Требуется повторное сопряжение"
                LinkStatus.KEY_MISMATCH -> "Ключ сервера изменился — соединение прервано"
                LinkStatus.DISABLED -> "Соединение выключено"
            }
        }
        val title = if (ScreenCapture.active.value) "SwordAI Bridge · идёт захват экрана" else "SwordAI Bridge"
        Notifications.serviceNotification(this, title, text, settings.controlEnabled)
    }

    fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        try {
            manager.notify(Notifications.SERVICE_NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            // нет разрешения на уведомления: сервис продолжает работать
        }
    }

    internal fun shutdown() {
        connection?.stop()
        connection = null
        AppGraph.attachConnection(null)
        ScreenCapture.stop()
        applyWakeLock(false)
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // уже снят
            }
        }
        networkCallback = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        connection?.stop()
        connection = null
        AppGraph.attachConnection(null)
        applyWakeLock(false)
        networkCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // уже снят
            }
        }
        networkCallback = null
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.swordai.bridge.action.START"
        const val ACTION_STOP = "com.swordai.bridge.action.STOP"

        @Volatile
        var instance: BridgeService? = null
            private set

        /** Запуск соединения (пользователь включил его в приложении). */
        fun start(context: Context) {
            AppGraph.settings.update { it.copy(serviceEnabled = true) }
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java).setAction(ACTION_START))
        }

        /** Полное отключение соединения. */
        fun stop(context: Context) {
            AppGraph.settings.update { it.copy(serviceEnabled = false) }
            instance?.shutdown()
            AppGraph.state.setConnection(LinkStatus.DISABLED)
        }

        /** Переподключить с новыми данными сопряжения. */
        fun restart(context: Context) {
            instance?.let {
                it.connection?.stop()
                it.connection = null
                AppGraph.attachConnection(null)
            }
            start(context)
        }

        fun refresh() {
            instance?.updateNotification()
        }
    }
}
