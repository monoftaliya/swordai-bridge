package com.swordai.bridge.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.swordai.bridge.AppGraph
import com.swordai.bridge.BuildConfig
import com.swordai.bridge.agent.LinkStatus
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.core.security.ConfirmMode
import com.swordai.bridge.data.NotificationSharing
import com.swordai.bridge.net.PairingClient
import com.swordai.bridge.service.BridgeService
import com.swordai.bridge.ui.components.GhostButton
import com.swordai.bridge.ui.components.GlassCard
import com.swordai.bridge.ui.components.KeyValue
import com.swordai.bridge.ui.components.ScreenHeader
import com.swordai.bridge.ui.components.SectionTitle
import com.swordai.bridge.ui.components.ToggleRow
import com.swordai.bridge.ui.theme.Sword
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(onOpenProtectedApps: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by AppGraph.settings.flow.collectAsState()
    val pairing by AppGraph.pairing.flow.collectAsState()
    val connection by AppGraph.state.connection.collectAsState()
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    var unpairing by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { ScreenHeader("Настройки", "Безопасность, подтверждения и подключение") }

        item {
            GlassCard {
                SectionTitle("Подключение")
                val p = pairing
                KeyValue("Сервер", p?.serverUrl ?: "—")
                KeyValue("Устройство", p?.deviceName ?: "—")
                KeyValue("ID", p?.deviceId ?: "—", mono = true)
                KeyValue("Ключ сервера", p?.fingerprint ?: "—", mono = true)
                KeyValue("Сопряжено", p?.pairedAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "—")
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    "Соединение с Cloud Bridge",
                    "Foreground-сервис с постоянным уведомлением",
                    settings.serviceEnabled && connection.status != LinkStatus.DISABLED,
                ) { enabled ->
                    if (enabled) BridgeService.start(context) else BridgeService.stop(context)
                }
                Text(
                    "Сверьте отпечаток ключа с админ-панелью сервера: если они различаются, соединение небезопасно.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item {
            GlassCard {
                SectionTitle("Подтверждение действий")
                ChoiceRow(
                    "Только значимые действия",
                    "Отправка, удаление, оплата, системные настройки, нераспознанные цели (рекомендуется)",
                    settings.confirmMode == ConfirmMode.SIGNIFICANT,
                ) { AppGraph.settings.update { it.copy(confirmMode = ConfirmMode.SIGNIFICANT) } }
                ChoiceRow(
                    "Любые действия",
                    "Каждое нажатие, ввод, прокрутка и открытие приложения",
                    settings.confirmMode == ConfirmMode.ALL_ACTIONS,
                ) { AppGraph.settings.update { it.copy(confirmMode = ConfirmMode.ALL_ACTIONS) } }
                ChoiceRow(
                    "Всё, включая чтение",
                    "Даже чтение экрана, скриншоты, уведомления и поиск файлов",
                    settings.confirmMode == ConfirmMode.EVERYTHING,
                ) { AppGraph.settings.update { it.copy(confirmMode = ConfirmMode.EVERYTHING) } }
                Text(
                    "Значимые действия подтверждаются всегда — это нельзя отключить.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        item {
            GlassCard {
                SectionTitle("Уведомления для ИИ")
                ChoiceRow("Заголовок и текст", "Коды подтверждения и номера карт скрываются", settings.notificationSharing == NotificationSharing.FULL) {
                    AppGraph.settings.update { it.copy(notificationSharing = NotificationSharing.FULL) }
                }
                ChoiceRow("Только заголовки", "Без текста сообщений", settings.notificationSharing == NotificationSharing.TITLES) {
                    AppGraph.settings.update { it.copy(notificationSharing = NotificationSharing.TITLES) }
                }
                ChoiceRow("Не передавать", "get_notifications будет отклоняться", settings.notificationSharing == NotificationSharing.OFF) {
                    AppGraph.settings.update { it.copy(notificationSharing = NotificationSharing.OFF) }
                }
            }
        }

        item {
            GlassCard {
                SectionTitle("Безопасность и индикация")
                ToggleRow(
                    "Плашка действий на экране",
                    "Показывать, что делает ИИ, и кнопку «СТОП» поверх приложений",
                    settings.overlayIndicator,
                ) { value -> AppGraph.settings.update { it.copy(overlayIndicator = value) } }
                HorizontalDivider(color = Sword.Line)
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onOpenProtectedApps).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Защищённые приложения", style = MaterialTheme.typography.titleSmall)
                        Text("Банки, кошельки, менеджеры паролей: ИИ их не видит и не управляет ими", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = Sword.Muted)
                }
                HorizontalDivider(color = Sword.Line)
                ToggleRow(
                    "Высокая доступность",
                    "Не давать процессору засыпать, пока есть соединение. Быстрее реакция, но заметный расход батареи.",
                    settings.highAvailability,
                ) { value -> AppGraph.settings.update { it.copy(highAvailability = value) } }
            }
        }

        item {
            GlassCard {
                SectionTitle("Опасная зона")
                Text(
                    "Отвязка удаляет токен устройства с телефона и отзывает его на сервере. Для повторного подключения понадобится новый код.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    if (unpairing) "Отвязка…" else "Отвязать устройство",
                    onClick = { if (!unpairing) confirmUnpair = true },
                    modifier = Modifier.fillMaxWidth(),
                    color = Sword.Danger,
                )
            }
        }

        item {
            Text(
                "SwordAI Bridge ${BuildConfig.VERSION_NAME} · протокол v1",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Отвязать устройство?") },
            text = { Text("ChatGPT потеряет доступ к этому телефону до нового сопряжения.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnpair = false
                    unpairing = true
                    scope.launch {
                        val current = AppGraph.pairing.current
                        BridgeService.stop(context)
                        if (current != null) PairingClient(AppGraph.http).unpair(current)
                        AppGraph.pairing.clear()
                        AppGraph.replay.reset()
                        AppGraph.log.add(LogEntry.CAT_SECURITY, "Устройство отвязано", current?.serverUrl, LogEntry.ST_INFO)
                        unpairing = false
                    }
                }) { Text("Отвязать", color = Sword.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Отмена") } },
            containerColor = Sword.GraphiteHigh,
        )
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = Sword.Blue, unselectedColor = Sword.Muted),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
