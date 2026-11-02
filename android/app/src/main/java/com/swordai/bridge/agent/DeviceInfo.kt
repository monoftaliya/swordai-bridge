package com.swordai.bridge.agent

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.swordai.bridge.AppGraph
import com.swordai.bridge.BuildConfig
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.capture.ScreenCapture
import com.swordai.bridge.core.commands.Tools
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.notifications.NotificationCollector
import kotlinx.serialization.json.JsonObject

/** Сведения об устройстве и состоянии агента (без персональных данных). */
object DeviceInfo {
    fun device(): JsonObject = jsonOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "android_release" to Build.VERSION.RELEASE,
        "sdk_int" to Build.VERSION.SDK_INT,
        "security_patch" to Build.VERSION.SECURITY_PATCH,
    )

    fun capabilities(): List<String> = Tools.ALL

    /** Краткое состояние для сервера (сообщения hello/status). */
    fun state(context: Context): JsonObject {
        val settings = AppGraph.settings.current
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        return jsonOf(
            "control_enabled" to settings.controlEnabled,
            "confirm_mode" to settings.confirmMode.key,
            "accessibility" to AgentAccessibilityService.connected.value,
            "projection" to ScreenCapture.active.value,
            "notification_access" to NotificationCollector.connected.value,
            "folders" to settings.folders.size,
            "screen_on" to power.isInteractive,
            "locked" to keyguard.isKeyguardLocked,
            "battery" to battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            "charging" to battery.isCharging,
            "network" to networkType(context),
        )
    }

    /** Полный ответ инструмента get_device_status. */
    fun collect(context: Context, includeApps: Boolean): JsonObject {
        val settings = AppGraph.settings.current
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val battery = context.getSystemService(BatteryManager::class.java)
        val foreground = AgentAccessibilityService.instance?.foregroundPackage()
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val apps = if (includeApps) {
            AppGraph.apps.launchable().map {
                jsonOf("label" to it.label, "package" to it.packageName, "protected" to AppGraph.settings.isProtected(it.packageName))
            }
        } else {
            null
        }
        return jsonOf(
            "device" to device(),
            "app" to jsonOf(
                "version" to BuildConfig.VERSION_NAME,
                "control_enabled" to settings.controlEnabled,
                "confirm_mode" to settings.confirmMode.key,
                "notification_sharing" to settings.notificationSharing.key,
            ),
            "permissions" to jsonOf(
                "accessibility_service" to AgentAccessibilityService.connected.value,
                "screen_capture_active" to ScreenCapture.active.value,
                "notification_access" to NotificationCollector.connected.value,
                "post_notifications" to notificationsGranted,
                "allowed_folders" to settings.folders.map { it.name },
                "battery_unrestricted" to power.isIgnoringBatteryOptimizations(context.packageName),
            ),
            "state" to jsonOf(
                "screen_on" to power.isInteractive,
                "locked" to keyguard.isKeyguardLocked,
                "foreground_package" to foreground,
                "foreground_app" to foreground?.let { AppGraph.apps.label(it) },
                "battery_percent" to battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                "charging" to battery.isCharging,
                "network" to networkType(context),
                "power_save" to power.isPowerSaveMode,
            ),
            "apps" to apps,
        )
    }

    fun networkType(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        val base = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && base != "vpn") "$base+vpn" else base
    }
}
