package com.swordai.bridge.core.security

import com.swordai.bridge.core.protocol.Command

/**
 * Защита от повторного выполнения:
 * - каждая команда выполняется не более одного раза (по command_id);
 * - seq строго растёт: старая перехваченная команда с новым id не пройдёт;
 * - просроченные команды (по часам сервера) не выполняются.
 * Состояние сохраняется, чтобы пережить перезапуск приложения.
 */
class ReplayGuard(private val persistence: Persistence? = null, private val maxRemembered: Int = 300) {

    interface Persistence {
        fun load(): Snapshot?
        fun save(snapshot: Snapshot)
    }

    data class Snapshot(val lastSeq: Long, val recentIds: List<String>)

    enum class Verdict { ACCEPT, DUPLICATE, REPLAYED, EXPIRED, FROM_FUTURE }

    private var lastSeq: Long = 0
    private val recent = LinkedHashSet<String>()

    init {
        persistence?.load()?.let { snapshot ->
            lastSeq = snapshot.lastSeq
            recent.addAll(snapshot.recentIds.takeLast(maxRemembered))
        }
    }

    @Synchronized
    fun lastSeq(): Long = lastSeq

    /** [serverNow] — текущее время по часам сервера (локальное время + смещение). */
    @Synchronized
    fun check(command: Command, serverNow: Double): Verdict {
        if (recent.contains(command.commandId)) return Verdict.DUPLICATE
        if (command.seq <= lastSeq) return Verdict.REPLAYED
        if (serverNow > command.expiresAt) return Verdict.EXPIRED
        if (command.issuedAt > serverNow + FUTURE_TOLERANCE_SEC) return Verdict.FROM_FUTURE
        return Verdict.ACCEPT
    }

    @Synchronized
    fun accept(command: Command) {
        lastSeq = maxOf(lastSeq, command.seq)
        recent.add(command.commandId)
        while (recent.size > maxRemembered) {
            recent.remove(recent.first())
        }
        persistence?.save(Snapshot(lastSeq, recent.toList()))
    }

    @Synchronized
    fun reset() {
        lastSeq = 0
        recent.clear()
        persistence?.save(Snapshot(0, emptyList()))
    }

    companion object {
        const val FUTURE_TOLERANCE_SEC = 300.0
    }
}
