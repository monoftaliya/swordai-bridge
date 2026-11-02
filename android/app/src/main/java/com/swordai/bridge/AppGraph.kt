package com.swordai.bridge

import android.content.Context
import com.swordai.bridge.agent.AgentState
import com.swordai.bridge.agent.CommandExecutor
import com.swordai.bridge.agent.ConfirmationManager
import com.swordai.bridge.agent.ToolRunner
import com.swordai.bridge.apps.AppResolver
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.core.security.ReplayGuard
import com.swordai.bridge.data.ActionLog
import com.swordai.bridge.data.PairingStore
import com.swordai.bridge.data.ReplayStore
import com.swordai.bridge.data.SecretStore
import com.swordai.bridge.data.Settings
import com.swordai.bridge.files.FileGateway
import com.swordai.bridge.net.BridgeConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Простой контейнер зависимостей приложения. */
object AppGraph {
    lateinit var context: Context
        private set
    lateinit var settings: Settings
        private set
    lateinit var secrets: SecretStore
        private set
    lateinit var pairing: PairingStore
        private set
    lateinit var log: ActionLog
        private set
    lateinit var state: AgentState
        private set
    lateinit var confirmations: ConfirmationManager
        private set
    lateinit var replay: ReplayGuard
        private set
    lateinit var executor: CommandExecutor
        private set
    lateinit var apps: AppResolver
        private set
    lateinit var files: FileGateway
        private set
    lateinit var http: OkHttpClient
        private set

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var connection: BridgeConnection? = null

    fun init(app: Context) {
        context = app.applicationContext
        settings = Settings(context)
        secrets = SecretStore(context)
        pairing = PairingStore(context, secrets)
        log = ActionLog(context, scope)
        state = AgentState()
        confirmations = ConfirmationManager(context)
        replay = ReplayGuard(ReplayStore(context))
        apps = AppResolver(context)
        files = FileGateway(context, settings)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket: без таймаута чтения
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        executor = CommandExecutor(
            scope = scope,
            settings = settings,
            state = state,
            log = log,
            replay = replay,
            runner = ToolRunner(context, settings, state, log, confirmations, apps, files),
            sink = { message -> send(message) },
        )
        executor.start()
        log.add(LogEntry.CAT_SYSTEM, "Приложение запущено", "Версия ${BuildConfig.VERSION_NAME}", LogEntry.ST_INFO)
    }

    fun attachConnection(value: BridgeConnection?) {
        connection = value
    }

    fun connection(): BridgeConnection? = connection

    /** Отправить сообщение серверу, если соединение установлено. */
    fun send(message: JsonObject): Boolean = connection?.send(message) ?: false
}
