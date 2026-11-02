package com.swordai.bridge.core.net

import kotlin.math.min
import kotlin.random.Random

/** Экспоненциальная задержка переподключения с джиттером ±20%: 1 с, 2 с, 4 с … максимум 60 с. */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    val attempts: Int get() = attempt

    fun nextDelayMs(): Long {
        val shift = min(attempt, 16)
        val exp = min(maxMs, baseMs shl shift)
        attempt++
        val jitter = 0.8 + random.nextDouble() * 0.4
        return (exp * jitter).toLong().coerceIn(baseMs / 2, maxMs)
    }

    fun reset() {
        attempt = 0
    }
}
