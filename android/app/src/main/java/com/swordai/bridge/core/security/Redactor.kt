package com.swordai.bridge.core.security

/** Маскировка одноразовых кодов и номеров карт в тексте уведомлений. */
object Redactor {
    private val CODE_HINT = Regex(
        "(код|code|otp|парол|password|passcode|verif|подтвержд|2fa|pin|пин|одноразов|login|вход|auth|token)",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT_NUMBER = Regex("(?<![\\d])\\d(?:[ -]?\\d){3,9}(?![\\d])")
    private val CARD_NUMBER = Regex("(?<![\\d])\\d(?:[ -]?\\d){12,18}(?![\\d])")

    fun redact(text: String?): String? {
        if (text == null) return null
        var out = CARD_NUMBER.replace(text) { mask(it.value) }
        if (CODE_HINT.containsMatchIn(out)) {
            out = SHORT_NUMBER.replace(out) { match ->
                val digits = match.value.count { it.isDigit() }
                if (digits in 4..8) mask(match.value) else match.value
            }
        }
        return out
    }

    private fun mask(value: String): String = value.map { if (it.isDigit()) '•' else it }.joinToString("")
}
