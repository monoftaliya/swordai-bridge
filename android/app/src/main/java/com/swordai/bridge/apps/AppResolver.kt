package com.swordai.bridge.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import com.swordai.bridge.accessibility.AgentAccessibilityService
import com.swordai.bridge.agent.ToolOutcome
import com.swordai.bridge.core.commands.OpenAppArgs
import com.swordai.bridge.core.jsonOf
import java.util.concurrent.ConcurrentHashMap

/** Поиск и запуск установленных приложений (только видимые в лаунчере). */
class AppResolver(private val context: Context) {

    data class AppInfo(val label: String, val packageName: String)

    sealed class Resolution {
        data class Found(val app: AppInfo) : Resolution()
        data class Failed(val code: String, val message: String) : Resolution()
    }

    private val labels = ConcurrentHashMap<String, String>()

    fun launchable(): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return queryActivities(pm, intent)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .also { list -> list.forEach { labels[it.packageName] = it.label } }
    }

    @Suppress("DEPRECATION")
    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    @Suppress("DEPRECATION")
    fun label(packageName: String): String? {
        labels[packageName]?.let { return it }
        val pm = context.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString().also { labels[packageName] = it }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun resolve(args: OpenAppArgs): Resolution {
        val apps = launchable()
        args.packageName?.let { pkg ->
            val app = apps.firstOrNull { it.packageName == pkg }
                ?: return Resolution.Failed("app_not_found", "Приложение $pkg не установлено или не запускается из лаунчера")
            return Resolution.Found(app)
        }
        val name = args.appName.orEmpty().trim().lowercase()
        val exact = apps.filter { it.label.lowercase() == name }
        val candidates = exact.ifEmpty { apps.filter { it.label.lowercase().startsWith(name) } }
            .ifEmpty { apps.filter { it.label.lowercase().contains(name) } }
        return when {
            candidates.size == 1 -> Resolution.Found(candidates.first())
            candidates.isEmpty() -> Resolution.Failed("app_not_found", "Не найдено приложение «${args.appName}»")
            else -> Resolution.Failed(
                "ambiguous",
                "Подходит несколько приложений: " +
                    candidates.take(8).joinToString { "${it.label} (${it.packageName})" } +
                    ". Укажите package_name.",
            )
        }
    }

    fun launch(app: AppInfo): ToolOutcome {
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ToolOutcome.Failure("app_not_found", "У ${app.label} нет экрана запуска")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        // Запуск из службы специальных возможностей разрешён системой и из фона.
        val launcher: Context = AgentAccessibilityService.instance ?: context
        return try {
            launcher.startActivity(intent)
            ToolOutcome.Success(jsonOf("opened" to app.label, "package" to app.packageName))
        } catch (e: Exception) {
            ToolOutcome.Failure("launch_failed", "Не удалось открыть ${app.label}: ${e.message}")
        }
    }
}
