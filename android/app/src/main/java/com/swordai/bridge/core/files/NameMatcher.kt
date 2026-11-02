package com.swordai.bridge.core.files

/** Сопоставление имени файла: маска с * и ? либо подстрока (без учёта регистра). */
class NameMatcher(query: String) {
    private val trimmed = query.trim()
    private val regex: Regex? = if (trimmed.contains('*') || trimmed.contains('?')) {
        val pattern = buildString {
            for (ch in trimmed) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(ch.toString()))
                }
            }
        }
        Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    } else {
        null
    }
    private val needle = trimmed.lowercase()

    fun matches(name: String): Boolean = regex?.matches(name) ?: name.lowercase().contains(needle)
}
