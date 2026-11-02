package com.swordai.bridge.data

import android.content.Context
import com.swordai.bridge.core.JsonCodec
import com.swordai.bridge.core.booleanOrNull
import com.swordai.bridge.core.jsonOf
import com.swordai.bridge.core.security.ConfirmMode
import com.swordai.bridge.core.security.ProtectedApps
import com.swordai.bridge.core.stringOrNull
import com.swordai.bridge.core.toCompactString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Папка, которую пользователь разрешил через системный выбор (Storage Access Framework). */
data class FolderGrant(val uri: String, val name: String)

enum class NotificationSharing(val key: String) {
    FULL("full"),
    TITLES("titles"),
    OFF("off");

    companion object {
        fun fromKey(key: String?): NotificationSharing = entries.firstOrNull { it.key == key } ?: FULL
    }
}

data class AppSettings(
    /** false — аварийная остановка: телефон отклоняет все команды. */
    val controlEnabled: Boolean = true,
    /** Пользователь хочет держать соединение (сервис запущен). */
    val serviceEnabled: Boolean = false,
    val autoStart: Boolean = true,
    val confirmMode: ConfirmMode = ConfirmMode.SIGNIFICANT,
    val notificationSharing: NotificationSharing = NotificationSharing.FULL,
    val overlayIndicator: Boolean = true,
    val highAvailability: Boolean = false,
    /** Пакет → защищён ли он (переопределения пользователя поверх списка по умолчанию). */
    val protectedOverrides: Map<String, Boolean> = emptyMap(),
    val folders: List<FolderGrant> = emptyList(),
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow.asStateFlow()
    val current: AppSettings get() = _flow.value

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_flow.value)
        save(next)
        _flow.value = next
    }

    fun isProtected(packageName: String): Boolean =
        current.protectedOverrides[packageName] ?: ProtectedApps.isProtectedByDefault(packageName)

    private fun load(): AppSettings {
        val overrides = HashMap<String, Boolean>()
        prefs.getString("protected_overrides", null)?.let { raw ->
            val obj = runCatching { JsonCodec.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            obj?.keys?.forEach { pkg -> obj.booleanOrNull(pkg)?.let { overrides[pkg] = it } }
        }
        val folders = ArrayList<FolderGrant>()
        prefs.getString("folders", null)?.let { raw ->
            val arr = runCatching { JsonCodec.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            arr?.forEach { element ->
                val o = element as? JsonObject ?: return@forEach
                val uri = o.stringOrNull("uri") ?: return@forEach
                folders.add(FolderGrant(uri, o.stringOrNull("name") ?: uri))
            }
        }
        return AppSettings(
            controlEnabled = prefs.getBoolean("control_enabled", true),
            serviceEnabled = prefs.getBoolean("service_enabled", false),
            autoStart = prefs.getBoolean("auto_start", true),
            confirmMode = ConfirmMode.fromKey(prefs.getString("confirm_mode", null)),
            notificationSharing = NotificationSharing.fromKey(prefs.getString("notification_sharing", null)),
            overlayIndicator = prefs.getBoolean("overlay_indicator", true),
            highAvailability = prefs.getBoolean("high_availability", false),
            protectedOverrides = overrides,
            folders = folders,
        )
    }

    private fun save(s: AppSettings) {
        val overrides = jsonOf(*s.protectedOverrides.map { (k, v) -> k to v }.toTypedArray())
        val folders = JsonArray(s.folders.map { jsonOf("uri" to it.uri, "name" to it.name) })
        prefs.edit()
            .putBoolean("control_enabled", s.controlEnabled)
            .putBoolean("service_enabled", s.serviceEnabled)
            .putBoolean("auto_start", s.autoStart)
            .putString("confirm_mode", s.confirmMode.key)
            .putString("notification_sharing", s.notificationSharing.key)
            .putBoolean("overlay_indicator", s.overlayIndicator)
            .putBoolean("high_availability", s.highAvailability)
            .putString("protected_overrides", overrides.toCompactString())
            .putString("folders", JsonCodec.encodeToString(JsonArray.serializer(), folders))
            .apply()
    }
}
