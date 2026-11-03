package com.pause.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionConfigTest {
    @Test fun `maximum is capped at five minutes for untrusted configurations`() {
        val config = InterventionConfig(minUsageSeconds = 900, startUsageSeconds = Int.MAX_VALUE, maxUsageSeconds = Int.MAX_VALUE).normalized()
        assertEquals(300, config.maxUsageSeconds)
        assertEquals(300, config.minUsageSeconds)
        assertEquals(300, config.startUsageSeconds)
    }

    @Test fun `zero and inverted settings cannot create invalid ranges`() {
        val config = InterventionConfig(minUsageSeconds = 60, startUsageSeconds = -10, maxUsageSeconds = 0).normalized()
        assertEquals(1, config.minUsageSeconds)
        assertEquals(1, config.startUsageSeconds)
        assertEquals(1, config.maxUsageSeconds)
    }

    @Test fun `a user configured shorter maximum clamps the starting value`() {
        val config = InterventionConfig(minUsageSeconds = 5, startUsageSeconds = 90, maxUsageSeconds = 30).normalized()
        assertEquals(30, config.startUsageSeconds)
        assertEquals(30, config.maxUsageSeconds)
    }

    @Test fun `normalization preserves valid preferences and is idempotent`() {
        val config = InterventionConfig(breathingSeconds = 30, inhaleSeconds = 3, holdSeconds = 2, exhaleSeconds = 5, minUsageSeconds = 10, startUsageSeconds = 45, maxUsageSeconds = 120)
        assertEquals(config, config.normalized())
        assertEquals(config.normalized(), config.normalized().normalized())
        assertTrue(config.maxUsageSeconds <= MAX_USAGE_SECONDS)
    }
}

