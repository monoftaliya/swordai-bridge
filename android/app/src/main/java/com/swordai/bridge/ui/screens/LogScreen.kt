package com.swordai.bridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.swordai.bridge.AppGraph
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.ui.components.EmptyHint
import com.swordai.bridge.ui.components.GlassCard
import com.swordai.bridge.ui.components.ScreenHeader
import com.swordai.bridge.ui.theme.Sword

private val filters = listOf(
    null to "Все",
    LogEntry.CAT_COMMAND to "Команды",
    LogEntry.CAT_SECURITY to "Безопасность",
    LogEntry.CAT_CONNECTION to "Соединение",
    LogEntry.CAT_SYSTEM to "Система",
)

@Composable
fun LogScreen() {
    val entries by AppGraph.log.entries.collectAsState()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val shown = if (filter == null) entries else entries.filter { it.category == filter }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader("Журнал", "Все действия ИИ и события безопасности на этом телефоне") {
                Text(
                    "Очистить",
                    color = Sword.Muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { confirmClear = true }.padding(8.dp),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                filters.forEach { (key, title) ->
                    val selected = filter == key
                    Text(
                        title,
                        color = if (selected) Sword.Text else Sword.Muted,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (selected) Sword.Blue.copy(alpha = 0.3f) else Sword.Graphite.copy(alpha = 0.6f))
                            .border(1.dp, if (selected) Sword.Blue else Sword.Line, CircleShape)
                            .clickable { filter = key }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        item {
            GlassCard {
                if (shown.isEmpty()) EmptyHint("Записей нет.")
                shown.take(300).forEach { LogRow(it) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал?") },
            text = { Text("Записи на телефоне будут удалены. Журнал сервера сохранится.") },
            confirmButton = {
                TextButton(onClick = {
                    AppGraph.log.clear()
                    confirmClear = false
                }) { Text("Очистить", color = Sword.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
            containerColor = Sword.GraphiteHigh,
        )
    }
}
