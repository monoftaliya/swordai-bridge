package com.swordai.bridge.core.security

import com.swordai.bridge.core.commands.Tools

enum class ConfirmMode(val key: String) {
    /** Подтверждаются только значимые действия (по умолчанию). */
    SIGNIFICANT("significant"),
    /** Подтверждается любое действие, меняющее состояние телефона. */
    ALL_ACTIONS("all_actions"),
    /** Подтверждается всё, включая чтение экрана и уведомлений. */
    EVERYTHING("everything");

    companion object {
        fun fromKey(key: String?): ConfirmMode = entries.firstOrNull { it.key == key } ?: SIGNIFICANT
    }
}

enum class Risk { READ, ACTION, SIGNIFICANT }

/** Контекст одного атомарного действия (шаги последовательности оцениваются по отдельности). */
data class ActionContext(
    val tool: String,
    val foregroundPackage: String?,
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val targetKnown: Boolean = true,
    val targetIsPassword: Boolean = false,
    val submit: Boolean = false,
)

data class Decision(
    val allowed: Boolean,
    val risk: Risk,
    val needsConfirmation: Boolean,
    val code: String? = null,
    val reason: String = "",
)

/**
 * Политика безопасности на телефоне. Работает независимо от сервера:
 * даже скомпрометированный сервер не сможет обойти блокировки и подтверждения.
 */
class SecurityPolicy(
    private val ownPackage: String,
    private val isProtected: (String) -> Boolean,
    private val confirmMode: ConfirmMode,
) {
    fun evaluate(ctx: ActionContext): Decision {
        val fg = ctx.foregroundPackage
        val fgProtected = fg != null && isProtected(fg)
        return when (ctx.tool) {
            Tools.DEVICE_STATUS, Tools.NOTIFICATIONS, Tools.SEARCH_FILES -> read()
            Tools.SCREEN_INFO, Tools.CAPTURE -> when {
                fgProtected -> deny("blocked_app", "Экран защищённого приложения ($fg) не передаётся")
                else -> read()
            }
            Tools.BACK, Tools.HOME -> action(Risk.ACTION) // всегда можно уйти с экрана
            Tools.OPEN_APP -> {
                val target = ctx.targetPackage
                when {
                    target == null -> action(Risk.ACTION)
                    target == ownPackage -> deny("blocked_app", "ИИ не может управлять самим SwordAI Bridge")
                    isProtected(target) -> deny("blocked_app", "Приложение $target защищено от управления ИИ")
                    else -> action(Risk.ACTION)
                }
            }
            Tools.OPEN_FILE -> action(Risk.ACTION)
            Tools.TAP, Tools.SWIPE, Tools.INPUT -> uiAction(ctx, fg, fgProtected)
            else -> deny("policy_denied", "Неизвестное действие ${ctx.tool}")
        }
    }

    private fun uiAction(ctx: ActionContext, fg: String?, fgProtected: Boolean): Decision {
        if (fg == ownPackage) return deny("blocked_app", "ИИ не может управлять самим SwordAI Bridge")
        if (fgProtected) return deny("blocked_app", "Приложение $fg защищено от управления ИИ")
        if (ctx.tool == Tools.INPUT && ctx.targetIsPassword) {
            return deny("password_field", "Ввод в поля паролей запрещён")
        }
        if (ctx.tool == Tools.TAP && ctx.targetIsPassword) {
            return action(Risk.SIGNIFICANT, "Нажатие на поле пароля")
        }
        val systemSensitive = fg != null && fg in ProtectedApps.SENSITIVE_SYSTEM
        return when (ctx.tool) {
            Tools.INPUT -> when {
                ctx.submit -> action(Risk.SIGNIFICANT, "Ввод с отправкой")
                systemSensitive -> action(Risk.SIGNIFICANT, "Ввод в системном приложении $fg")
                else -> action(Risk.ACTION)
            }
            Tools.TAP -> when {
                !ctx.targetKnown -> action(Risk.SIGNIFICANT, "Цель нажатия не распознана")
                SensitiveWords.isSignificant(ctx.targetLabel) -> action(Risk.SIGNIFICANT, "Значимая кнопка «${ctx.targetLabel?.take(60)}»")
                systemSensitive -> action(Risk.SIGNIFICANT, "Нажатие в системном приложении $fg")
                else -> action(Risk.ACTION)
            }
            else -> action(Risk.ACTION) // прокрутка
        }
    }

    private fun read(): Decision = Decision(true, Risk.READ, confirmMode == ConfirmMode.EVERYTHING)

    private fun action(risk: Risk, reason: String = ""): Decision {
        val confirm = when (risk) {
            Risk.SIGNIFICANT -> true // отключить нельзя
            Risk.ACTION -> confirmMode != ConfirmMode.SIGNIFICANT
            Risk.READ -> confirmMode == ConfirmMode.EVERYTHING
        }
        return Decision(true, risk, confirm, reason = reason)
    }

    private fun deny(code: String, reason: String): Decision = Decision(false, Risk.SIGNIFICANT, false, code, reason)
}

object ProtectedApps {
    /** Известные банковские, платёжные приложения, менеджеры паролей и аутентификаторы. */
    val DEFAULT: Set<String> = setOf(
        "ru.sberbankmobile",
        "com.idamob.tinkoff.android",
        "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android",
        "ru.nspk.mirpay",
        "ru.yandex.money",
        "com.yandex.bank",
        "com.google.android.apps.walletnfcrel",
        "com.samsung.android.spay",
        "com.paypal.android.p2pmobile",
        "com.revolut.revolut",
        "com.transferwise.android",
        "com.x8bit.bitwarden",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "keepass2android.keepass2android",
        "com.kunzisoft.keepass.free",
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.beemdevelopment.aegis",
        "org.fedorahosted.freeotp",
    )

    private val HEURISTIC = Regex(
        "(bank|banking|wallet|\\.pay|pay\\.|fintech|authenticator|password|keepass|bitwarden|1password|vault)",
        RegexOption.IGNORE_CASE,
    )

    /** Системные приложения, где любое нажатие требует подтверждения. */
    val SENSITIVE_SYSTEM: Set<String> = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.samsung.android.lool",
        "com.huawei.systemmanager",
    )

    fun isProtectedByDefault(packageName: String): Boolean =
        packageName in DEFAULT || HEURISTIC.containsMatchIn(packageName)
}

/** Распознавание «значимых» кнопок по тексту (рус/англ). */
object SensitiveWords {
    private val PREFIXES = listOf(
        "отправ", "удал", "оплат", "оплач", "платёж", "платеж", "купи", "покуп", "заказ", "оформ", "перевод",
        "перевест", "подтвер", "опубликов", "публик", "поделит", "позвон", "звонок", "установ", "разреш",
        "принят", "принима", "подпис", "сброс", "очист", "стере", "стира", "отписат", "заблок", "блокир",
        "пожалов", "выйти", "списат", "снять", "пополн", "форматир",
        "send", "delet", "remov", "payment", "purchas", "checkout", "transfer", "confirm", "publish",
        "install", "uninstall", "accept", "subscrib", "unsubscrib", "erase", "wipe", "logout", "withdraw",
        "submit", "approve", "grant", "authoriz", "format",
    )
    private val EXACT = setOf(
        "pay", "buy", "order", "post", "share", "call", "dial", "allow", "sign", "reset", "clear", "block",
        "report", "tweet", "выход", "ок", "ok", "да", "yes",
    )
    private val PHRASES = listOf("log out", "sign out", "sign in", "top up", "выйти из")
    private val SPLIT = Regex("[^\\p{L}\\p{N}]+")

    fun isSignificant(label: String?): Boolean {
        if (label.isNullOrBlank()) return false
        val lower = label.lowercase()
        if (PHRASES.any { lower.contains(it) }) return true
        return lower.split(SPLIT).any { word ->
            word.isNotEmpty() && (word in EXACT || PREFIXES.any { word.startsWith(it) })
        }
    }
}
