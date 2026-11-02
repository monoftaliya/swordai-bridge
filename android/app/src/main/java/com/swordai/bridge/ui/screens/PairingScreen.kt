package com.swordai.bridge.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.swordai.bridge.AppGraph
import com.swordai.bridge.core.log.LogEntry
import com.swordai.bridge.net.PairingClient
import com.swordai.bridge.net.PairingException
import com.swordai.bridge.service.BridgeService
import com.swordai.bridge.ui.components.GlassCard
import com.swordai.bridge.ui.components.GradientButton
import com.swordai.bridge.ui.components.StatusOrb
import com.swordai.bridge.ui.theme.Sword
import kotlinx.coroutines.launch

@Composable
fun PairingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("https://") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Android") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusOrb(Sword.Violet, active = true, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("SwordAI Bridge", style = MaterialTheme.typography.headlineMedium)
                Text("Мост между ChatGPT и вашим телефоном", style = MaterialTheme.typography.bodySmall)
            }
        }
        GlassCard {
            Text("Как подключить", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Step(1, "Разверните SwordAI Cloud Bridge (инструкция в docs/DEPLOY.md).")
            Step(2, "Откройте в браузере адрес сервера/admin, войдите и нажмите «Создать код».")
            Step(3, "Введите адрес сервера и код ниже. Код одноразовый и живёт 10 минут.")
        }
        GlassCard {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Sword.Blue,
                unfocusedBorderColor = Sword.Line,
                focusedLabelColor = Sword.Blue,
                cursorColor = Sword.Blue,
                focusedTextColor = Sword.Text,
                unfocusedTextColor = Sword.Text,
            )
            OutlinedTextField(
                value = server,
                onValueChange = { server = it.trim() },
                label = { Text("Адрес сервера") },
                placeholder = { Text("https://my-bridge.onrender.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = fieldColors,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { input -> code = formatCode(input) },
                label = { Text("Код сопряжения") },
                placeholder = { Text("XXXXX-XXXXX") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                colors = fieldColors,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 80) name = it },
                label = { Text("Имя устройства") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = fieldColors,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error.orEmpty(), color = Sword.Danger, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            GradientButton(
                text = "Подключить телефон",
                loading = busy,
                enabled = code.length == 11 && server.length > 8,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val pairing = PairingClient(AppGraph.http).pair(server, code, name)
                            AppGraph.replay.reset()
                            AppGraph.pairing.save(pairing)
                            AppGraph.log.add(
                                LogEntry.CAT_SECURITY,
                                "Сопряжение выполнено",
                                "Сервер ${pairing.serverUrl}, отпечаток ключа ${pairing.fingerprint}",
                                LogEntry.ST_OK,
                            )
                            BridgeService.restart(context)
                        } catch (e: PairingException) {
                            error = e.message
                        } catch (e: Exception) {
                            error = "Ошибка: ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
        Text(
            "Телефон будет выполнять только подписанные сервером команды. Значимые действия всегда требуют вашего подтверждения, " +
                "а аварийная остановка доступна в уведомлении, на экране и в шторке.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Step(number: Int, text: String) {
    Row(Modifier.padding(vertical = 4.dp)) {
        Text("$number.", color = Sword.Violet, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Sword.Text)
    }
}

/** Приводит ввод к виду XXXXX-XXXXX. */
private fun formatCode(input: String): String {
    val raw = input.uppercase().filter { it.isLetterOrDigit() }.take(10)
    return if (raw.length > 5) raw.substring(0, 5) + "-" + raw.substring(5) else raw
}
