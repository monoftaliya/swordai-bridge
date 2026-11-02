package com.swordai.bridge.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.swordai.bridge.AppGraph
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.capture.ScreenCapture
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.notifications.NotificationCollector
import com.swordai.bridge.service.BridgeService
import com.swordai.bridge.ui.components.GhostButton
import com.swordai.bridge.ui.components.GlassCard
import com.swordai.bridge.ui.components.Pill
import com.swordai.bridge.ui.components.ScreenHeader
import com.swordai.bridge.ui.components.ToggleRow
import com.swordai.bridge.ui.theme.Sword
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppGraph.settings.flow.collectAsState()
    val accessibility by AgentAccessibilityService.connected.collectAsState()
    val projection by ScreenCapture.active.collectAsState()
    val notificationAccess by NotificationCollector.connected.collectAsState()
    // Системные экраны настроек не сообщают о результате — перечитываем состояние раз в секунду.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val notificationsGranted = remember(tick) { postNotificationsGranted(context) }
    val batteryUnrestricted = remember(tick) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    var captureMessage by remember { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                AppGraph.files.addFolder(uri)
                AppGraph.log.add(LogEntry.CAT_SECURITY, "Разрешена папка для поиска", uri.lastPathSegment, LogEntry.ST_OK)
            } catch (e: SecurityException) {
                AppGraph.log.add(LogEntry.CAT_SYSTEM, "Не удалось сохранить доступ к папке", e.message, LogEntry.ST_ERROR)
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Доступы", "Всё выдаётся вами и отзывается в любой момент") }

        item {
            PermissionCard(
                title = "Управление экраном",
                description = "Служба специальных возможностей: чтение элементов экрана, нажатия, прокрутка, ввод текста. " +
                    "Без неё ИИ не может управлять телефоном.",
                granted = accessibility,
            ) {
                GhostButton("Открыть настройки специальных возможностей", onClick = {
                    openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, modifier = Modifier.fillMaxWidth())
                if (!accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Если переключатель неактивен («Ограниченная настройка»): Сведения о приложении → ⋮ → «Разрешить ограниченные настройки», затем вернитесь сюда.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    GhostButton("Сведения о приложении", onClick = { openAppDetails(context) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                PermissionCard(
                    title = "Уведомления приложения",
                    description = "Статус соединения и запросы подтверждения значимых действий.",
                    granted = notificationsGranted,
                ) {
                    if (!notificationsGranted) {
                        GhostButton("Разрешить уведомления", onClick = {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            PermissionCard(
                title = "Захват экрана",
                description = "Скриншоты через MediaProjection. Система спрашивает согласие на каждую сессию; на Android 15+ " +
                    "сессия завершается при блокировке телефона. Если сессии нет, при первом скриншоте появится запрос.",
                granted = projection,
            ) {
                if (projection) {
                    GhostButton("Остановить захват", onClick = { ScreenCapture.stop() }, modifier = Modifier.fillMaxWidth(), color = Sword.Danger)
                } else {
                    GhostButton("Начать сессию захвата", onClick = {
                        if (BridgeService.instance == null) {
                            captureMessage = "Сначала подключитесь к Cloud Bridge на главном экране."
                        } else {
                            captureMessage = null
                            scope.launch {
                                if (!ScreenCapture.startManually(context)) captureMessage = "Захват не разрешён."
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth())
                }
                captureMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Sword.Warning)
                }
            }
        }

        item {
            PermissionCard(
                title = "Доступ к уведомлениям",
                description = "Позволяет ИИ читать уведомления (инструмент get_notifications). Коды подтверждения маскируются, " +
                    "уведомления защищённых приложений не передаются.",
                granted = notificationAccess,
            ) {
                GhostButton(
                    if (notificationAccess) "Управлять доступом" else "Выдать доступ",
                    onClick = { openNotificationAccess(context) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            PermissionCard(
                title = "Папки для поиска файлов",
                description = "ИИ ищет и открывает файлы только в выбранных папках, только на чтение.",
                granted = settings.folders.isNotEmpty(),
            ) {
                settings.folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Убрать",
                            color = Sword.Danger,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 8.dp).clickable { AppGraph.files.removeFolder(folder) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                GhostButton("Добавить папку", onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth())
            }
        }

        item {
            PermissionCard(
                title = "Работа в фоне",
                description = "Исключение из оптимизации батареи повышает стабильность соединения. Android всё равно может " +
                    "приостанавливать сеть в глубоком сне — тогда соединение восстановится при пробуждении.",
                granted = batteryUnrestricted,
            ) {
                if (!batteryUnrestricted) {
                    GhostButton("Снять ограничения батареи", onClick = { requestBatteryExemption(context) }, modifier = Modifier.fillMaxWidth())
                }
                ToggleRow(
                    "Автозапуск после перезагрузки",
                    "Восстанавливать соединение, если оно было включено",
                    settings.autoStart,
                ) { value -> AppGraph.settings.update { it.copy(autoStart = value) } }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    granted: Boolean,
    actions: @Composable () -> Unit,
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Pill(if (granted) "включено" else "выключено", if (granted) Sword.Success else Sword.Warning)
        }
        Spacer(Modifier.height(6.dp))
        Text(description, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        Column { actions() }
    }
}

private fun postNotificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        openAppDetails(context)
    }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // нет экрана настроек
    }
}

private fun openNotificationAccess(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, NotificationCollector::class.java).flattenToString(),
        )
    } else {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    openSettings(context, intent)
}
