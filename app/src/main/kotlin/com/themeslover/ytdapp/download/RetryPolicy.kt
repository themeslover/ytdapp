package com.themeslover.ytdapp.download

import kotlin.math.min
import kotlin.random.Random

class RetryPolicy(
    private val maxAttempts: Int = 4,
    private val baseDelayMs: Long = 750L,
    private val maxDelayMs: Long = 15_000L
) {
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    fun delayMs(attempt: Int): Long {
        val exponential = min(maxDelayMs, baseDelayMs * (1L shl attempt.coerceIn(0, 10)))
        return exponential + Random.nextLong(0, (exponential / 4).coerceAtLeast(1))
    }
}
