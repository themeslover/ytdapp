package com.themeslover.ytdapp.download

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadQueueTest {
    @Test
    fun retryPolicyStopsAfterConfiguredAttempts() {
        val policy = RetryPolicy(maxAttempts = 2)
        assertEquals(true, policy.shouldRetry(0))
        assertEquals(true, policy.shouldRetry(1))
        assertEquals(false, policy.shouldRetry(2))
    }
}
