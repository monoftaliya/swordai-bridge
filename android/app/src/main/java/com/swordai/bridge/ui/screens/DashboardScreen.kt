package com.swordai.bridge.ui.screens

import android.app.KeyguardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordai.bridge.AppGraph
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.agent.ConfirmationManager
import com.swordai.bridge.agent.DeviceInfo
import com.swordai.bridge.agent.EmergencyStop
import com.swordai.bridge.agent.LinkStatus
import com.swordai.bridge.capture.ScreenCapture
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.notifications.NotificationCollector
import com.swordai.bridge.service.BridgeService
import com.swordai.bridge.ui.components.ActivityBars
import com.swordai.bridge.ui.components.EmptyHint
import com.swordai.bridge.ui.components.GhostButton
import com.swordai.bridge.ui.components.GlassCard
import com.swordai.bridge.ui.components.GradientButton
import com.swordai.bridge.ui.components.KeyValue
import com.swordai.bridge.ui.components.LatencyChart
import com.swordai.bridge.ui.components.Pill
import com.swordai.bridge.ui.components.SectionTitle
import com.swordai.bridge.ui.components.StatusDot
import com.swordai.bridge.ui.components.StatusOrb
import com.swordai.bridge.ui.components.UiFormat
import com.swordai.bridge.ui.findActivity
import com.swordai.bridge.ui.theme.Sword
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DeviceSnapshot(
    val battery: Int,
    val charging: Boolean,
    val network: String,
    val locked: Boolean,
)

private fun readDevice(context: Context): DeviceSnapshot {
    val battery = context.getSystemService(BatteryManager::class.java)
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    return DeviceSnapshot(
        battery = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        charging = battery.isCharging,
        network = DeviceInfo.networkType(context),
        locked = keyguard.isKeyguardLocked,
    )
}

@Composable
fun DashboardScreen(onOpenLog: () -> Unit, onOpenPermissions: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val pairing by AppGraph.pairing.flow.collectAsState()
    val connection by AppGraph.state.connection.collectAsState()
    val integration by AppGraph.state.integration.collectAsState()
    val latency by AppGraph.state.latency.collectAsState()
    val activity by AppGraph.state.activity.collectAsState()
    val commands by AppGraph.state.commands.collectAsState()
    val logs by AppGraph.log.entries.collectAsState()
    val settings by AppGraph.settings.flow.collectAsState()
    val pending by AppGraph.confirmations.pending.collectAsState()
    val accessibility by AgentAccessibilityService.connected.collectAsState()
    val projection by ScreenCapture.active.collectAsState()
    val notificationAccess by NotificationCollector.connected.collectAsState()
    val currentAction by AppGraph.state.currentAction.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var device by remember { mutableStateOf(readDevice(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
            device = readDevice(context)
            AppGraph.state.tickActivity()
        }
    }

    val statusColor = UiFormat.statusColor(connection.status)
    val host = pairing?.serverUrl?.removePrefix("https://")?.removePrefix("http://") ?: "—"

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SwordAI Bridge", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${pairing?.deviceName ?: "Телефон"} · $host",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Pill(UiFormat.statusText(connection.status), statusColor)
            }
        }

        if (!settings.controlEnabled) {
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(Sword.Danger, pulsing = true)
                        Spacer(Modifier.width(6.dp))
                        Text("Управление остановлено", style = MaterialTheme.typography.titleMedium, color = Sword.Danger)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Все команды ИИ отклоняются. Возобновить можно только здесь, на телефоне.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Sword.Muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    GradientButton(
                        "Возобновить управление",
                        onClick = { EmergencyStop.resume(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = listOf(Sword.Success, Sword.Blue),
                    )
                }
            }
        }

        items(pending, key = { it.id }) { request -> ConfirmationCard(request) }

        item {
            GlassCard(Modifier.animateContentSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusOrb(statusColor, active = connection.status == LinkStatus.CONNECTED)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(UiFormat.statusText(connection.status), style = MaterialTheme.typography.titleLarge)
                        val sub = when (connection.status) {
                            LinkStatus.CONNECTED -> {
                                val ms = latency.lastOrNull()?.let { "задержка $it мс · " }.orEmpty()
                                ms + "на связи " + UiFormat.duration(now - (connection.connectedAt ?: now))
                            }
                            LinkStatus.RECONNECTING -> {
                                val left = ((connection.nextRetryAt ?: now) - now).coerceAtLeast(0) / 1000
                                "повтор через $left с" + (connection.lastError?.let { " · $it" } ?: "")
                            }
                            else -> connection.lastError ?: "Cloud Bridge: $host"
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AnimatedVisibility(currentAction != null) {
                            Text(
                                "▶ ${currentAction.orEmpty()}",
                                color = Sword.Cyan,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
                when (connection.status) {
                    LinkStatus.DISABLED -> {
                        Spacer(Modifier.height(14.dp))
                        GradientButton("Подключиться к Cloud Bridge", onClick = { BridgeService.start(context) }, modifier = Modifier.fillMaxWidth())
                    }
                    LinkStatus.RECONNECTING, LinkStatus.OFFLINE -> {
                        Spacer(Modifier.height(14.dp))
                        GhostButton("Переподключить сейчас", onClick = { AppGraph.connection()?.reconnectNow() }, modifier = Modifier.fillMaxWidth())
                    }
                    LinkStatus.UNAUTHORIZED, LinkStatus.KEY_MISMATCH -> {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Сервер не принимает это устройство или его ключ изменился. Выполните сопряжение заново в настройках.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(10.dp))
                        GhostButton("Открыть настройки", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
                    }
                    else -> Unit
                }
            }
        }

        if (settings.controlEnabled) {
            item {
                EmergencyButton {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    EmergencyStop.trigger(context, "кнопка в приложении")
                }
            }
        }

        item {
            GlassCard {
                SectionTitle("Статусы")
                StatusLine(
                    "Соединение",
                    if (connection.status == LinkStatus.CONNECTED) "WebSocket активен" else UiFormat.statusText(connection.status),
                    statusColor,
                    connection.status == LinkStatus.CONNECTED,
                )
                StatusLine(
                    "Cloud Bridge",
                    connection.serverVersion?.let { "версия $it · ${pairing?.serverName ?: ""}" } ?: (pairing?.serverName ?: "—"),
                    if (connection.status == LinkStatus.CONNECTED) Sword.Success else Sword.Faint,
                    false,
                )
                val chatText = when {
                    connection.status != LinkStatus.CONNECTED -> "нет данных (нет связи)"
                    !integration.mcpEnabled -> "MCP выключен на сервере (нужен HTTPS)"
                    integration.authorizedClients == 0 -> "не подключён — добавьте MCP-сервер в ChatGPT"
                    else -> "подключений: ${integration.authorizedClients} · последний вызов " +
                        UiFormat.ago(integration.lastCallAt)
                }
                val chatColor = when {
                    connection.status != LinkStatus.CONNECTED -> Sword.Faint
                    integration.authorizedClients > 0 -> Sword.Success
                    else -> Sword.Warning
                }
                StatusLine("ChatGPT Integration", chatText, chatColor, integration.authorizedClients > 0)
            }
        }

        item {
            GlassCard {
                SectionTitle("Задержка соединения") {
                    Text(latency.lastOrNull()?.let { "$it мс" } ?: "—", style = MaterialTheme.typography.titleSmall, color = Sword.Cyan)
                }
                if (latency.size < 2) EmptyHint("График появится после нескольких пингов.") else LatencyChart(latency)
                if (latency.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "мин ${latency.min()} · сред ${latency.average().toInt()} · макс ${latency.max()} мс",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(16.dp))
                SectionTitle("Команды за 30 минут") {
                    Text("${activity.sum()}", style = MaterialTheme.typography.titleSmall, color = Sword.Violet)
                }
                ActivityBars(activity)
            }
        }

        item {
            GlassCard {
                SectionTitle("Устройство")
                KeyValue("Модель", "${Build.MANUFACTURER} ${Build.MODEL}")
                KeyValue("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                KeyValue("Батарея", "${device.battery}%" + if (device.charging) " · заряжается" else "")
                KeyValue("Сеть", device.network)
                KeyValue("Экран", if (device.locked) "заблокирован" else "разблокирован")
                KeyValue("ID", pairing?.deviceId ?: "—", mono = true)
            }
        }

        item {
            val missing = buildList {
                if (!accessibility) add("Служба специальных возможностей")
                if (!notificationAccess) add("Доступ к уведомлениям")
                if (settings.folders.isEmpty()) add("Папки для поиска файлов")
            }
            GlassCard(onClick = onOpenPermissions) {
                SectionTitle("Разрешения") {
                    Pill(if (missing.isEmpty()) "всё готово" else "нужно: ${missing.size}", if (missing.isEmpty()) Sword.Success else Sword.Warning)
                }
                PermissionLine("Управление экраном", accessibility)
                PermissionLine("Захват экрана (сессия)", projection)
                PermissionLine("Уведомления для ИИ", notificationAccess)
                PermissionLine("Папки: ${settings.folders.size}", settings.folders.isNotEmpty())
                Spacer(Modifier.height(8.dp))
                Text("Нажмите, чтобы настроить разрешения", style = MaterialTheme.typography.bodySmall, color = Sword.Blue)
            }
        }

        item {
            GlassCard {
                SectionTitle("Последние команды")
                if (commands.isEmpty()) EmptyHint("Команд ещё не было. Попросите ChatGPT, например: «Открой на телефоне настройки».")
                commands.take(6).forEach { cmd ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(UiFormat.commandColor(cmd.status), pulsing = cmd.status == "running" || cmd.status == "awaiting", dotSize = 8.dp)
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${com.swordai.bridge.agent.CommandExecutor.toolTitle(cmd.tool)} · ${cmd.summary}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${cmd.client} · ${UiFormat.ago(cmd.time)}" + (cmd.detail?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Pill(UiFormat.commandStatusText(cmd.status), UiFormat.commandColor(cmd.status))
                    }
                }
            }
        }

        item {
            GlassCard {
                SectionTitle("Журнал действий") {
                    Text("Весь журнал", color = Sword.Blue, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(onClick = onOpenLog))
                }
                if (logs.isEmpty()) EmptyHint("Пока пусто.")
                logs.take(6).forEach { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun ConfirmationCard(request: ConfirmationManager.Request) {
    val context = LocalContext.current
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(Sword.Violet, pulsing = true)
            Spacer(Modifier.width(6.dp))
            Text("Подтвердите действие", style = MaterialTheme.typography.titleMedium, color = Sword.Violet)
        }
        Spacer(Modifier.height(8.dp))
        Text(request.title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        Text("${request.client} · ${request.detail}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Отклонить", onClick = { AppGraph.confirmations.resolve(request.id, false) }, modifier = Modifier.weight(1f), color = Sword.Danger)
            GradientButton(
                "Разрешить",
                onClick = {
                    AppGraph.confirmations.resolve(request.id, true)
                    // Вернуть на экран приложение, в котором ИИ выполняет действие.
                    context.findActivity()?.moveTaskToBack(true)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EmergencyButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFFB0102C), Sword.Danger, Color(0xFFFF7A59))))
            .border(1.dp, Color(0x55FFFFFF), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ЭКСТРЕННАЯ ОСТАНОВКА", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Мгновенно запретить ИИ любые действия", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusLine(title: String, value: String, color: Color, pulsing: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, pulsing = pulsing, dotSize = 8.dp)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PermissionLine(title: String, granted: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (granted) Sword.Success else Sword.Faint))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (granted) Sword.Text else Sword.Muted)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun LogRow(entry: LogEntry) {
    val color = when (entry.status) {
        LogEntry.ST_OK -> Sword.Success
        LogEntry.ST_ERROR -> Sword.Danger
        LogEntry.ST_WARN -> Sword.Warning
        LogEntry.ST_PENDING -> Sword.Violet
        else -> Sword.Blue
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!entry.detail.isNullOrBlank()) {
                Text(entry.detail, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(timeFormat.format(Date(entry.time)), style = MaterialTheme.typography.labelSmall, color = Sword.Faint)
    }
}
