package com.swordai.bridge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.swordai.bridge.AppGraph
import com.swordai.bridge.ui.components.AuroraBackground
import com.swordai.bridge.ui.screens.DashboardScreen
import com.swordai.bridge.ui.screens.LogScreen
import com.swordai.bridge.ui.screens.PairingScreen
import com.swordai.bridge.ui.screens.PermissionsScreen
import com.swordai.bridge.ui.screens.ProtectedAppsScreen
import com.swordai.bridge.ui.screens.SettingsScreen
import com.swordai.bridge.ui.theme.Sword

enum class Tab(val title: String, val icon: ImageVector) {
    Home("Главная", Icons.Filled.Home),
    Log("Журнал", Icons.AutoMirrored.Filled.List),
    Permissions("Доступы", Icons.Filled.Lock),
    Settings("Настройки", Icons.Filled.Settings),
}

@Composable
fun AppRoot() {
    val pairing by AppGraph.pairing.flow.collectAsState()
    AuroraBackground {
        Crossfade(targetState = pairing == null, label = "root") { unpaired ->
            if (unpaired) PairingScreen() else MainShell()
        }
    }
}

@Composable
private fun MainShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var protectedApps by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = protectedApps || tab != Tab.Home) {
        if (protectedApps) protectedApps = false else tab = Tab.Home
    }
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            NavigationBar(containerColor = Sword.Graphite.copy(alpha = 0.92f), tonalElevation = 0.dp) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item && !protectedApps,
                        onClick = {
                            protectedApps = false
                            tab = item
                        },
                        icon = { Icon(item.icon, contentDescription = item.title) },
                        label = { Text(item.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Sword.Text,
                            indicatorColor = Sword.Blue.copy(alpha = 0.35f),
                            unselectedIconColor = Sword.Muted,
                            unselectedTextColor = Sword.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (protectedApps) {
                ProtectedAppsScreen(onBack = { protectedApps = false })
            } else {
                Crossfade(targetState = tab, label = "tabs") { current ->
                    when (current) {
                        Tab.Home -> DashboardScreen(
                            onOpenLog = { tab = Tab.Log },
                            onOpenPermissions = { tab = Tab.Permissions },
                            onOpenSettings = { tab = Tab.Settings },
                        )
                        Tab.Log -> LogScreen()
                        Tab.Permissions -> PermissionsScreen()
                        Tab.Settings -> SettingsScreen(onOpenProtectedApps = { protectedApps = true })
                    }
                }
            }
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
