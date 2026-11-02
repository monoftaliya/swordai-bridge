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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordai.bridge.AppGraph
import com.swordai.bridge.apps.AppResolver
import com.swordai.bridge.core.security.ProtectedApps
import com.swordai.bridge.ui.components.EmptyHint
import com.swordai.bridge.ui.components.GlassCard
import com.swordai.bridge.ui.theme.Sword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ProtectedAppsScreen(onBack: () -> Unit) {
    val settings by AppGraph.settings.flow.collectAsState()
    var apps by remember { mutableStateOf<List<AppResolver.AppInfo>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { AppGraph.apps.launchable() }
    }
    val list = apps.orEmpty()
        .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<AppResolver.AppInfo> { AppGraph.settings.isProtected(it.packageName) }.thenBy { it.label.lowercase() })

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Sword.Text,
                    modifier = Modifier.clickable(onClick = onBack).padding(8.dp),
                )
                Spacer(Modifier.width(4.dp))
                Column {
                    Text("Защищённые приложения", style = MaterialTheme.typography.titleLarge)
                    Text("ИИ не открывает их, не читает их экран и уведомления", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск приложения") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Sword.Blue,
                    unfocusedBorderColor = Sword.Line,
                    cursorColor = Sword.Blue,
                    focusedTextColor = Sword.Text,
                    unfocusedTextColor = Sword.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            GlassCard {
                Text(
                    "По умолчанию защищены известные банки, кошельки, менеджеры паролей и аутентификаторы, а также приложения, " +
                        "в имени пакета которых есть bank, wallet, pay и т. п. Вы можете изменить выбор для любого приложения.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (apps == null) {
            item { EmptyHint("Загрузка списка приложений…") }
        }
        items(list, key = { it.packageName }) { app ->
            val isProtected = AppGraph.settings.isProtected(app.packageName)
            val byDefault = ProtectedApps.isProtectedByDefault(app.packageName)
            val overridden = settings.protectedOverrides.containsKey(app.packageName)
            GlassCard(corner = 18.dp, padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            app.packageName + when {
                                overridden -> " · ваш выбор"
                                byDefault -> " · по умолчанию"
                                else -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Switch(
                        checked = isProtected,
                        onCheckedChange = { value ->
                            AppGraph.settings.update { s ->
                                val next = s.protectedOverrides.toMutableMap()
                                if (value == byDefault) next.remove(app.packageName) else next[app.packageName] = value
                                s.copy(protectedOverrides = next)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Sword.Violet,
                            uncheckedThumbColor = Sword.Muted,
                            uncheckedTrackColor = Sword.GraphiteHigh,
                            uncheckedBorderColor = Sword.Line,
                        ),
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
