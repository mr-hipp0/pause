package com.pause.data

import com.pause.domain.BreathingAnimation
import com.pause.domain.InterventionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {
    @Test fun `per-app preferences roundtrip without losing optional choices`() {
        val original = InterventionConfig(
            breathingSeconds = 30, inhaleSeconds = 3, holdSeconds = 2, exhaleSeconds = 5,
            haptics = false, sound = true, animation = BreathingAnimation.RIPPLE,
            minUsageSeconds = 10, startUsageSeconds = 45, maxUsageSeconds = 180,
        )
        assertEquals(original, ConfigCodec.decode(ConfigCodec.encode(original)))
    }

    @Test fun `older or absent saved preferences receive safe current defaults`() {
        assertEquals(InterventionConfig(), ConfigCodec.decode(null))
        assertEquals(InterventionConfig(), ConfigCodec.decode("{}"))
        assertEquals(InterventionConfig(breathingSeconds = 15), ConfigCodec.decode("{\"breathingSeconds\":15}"))
    }

    @Test fun `corrupt preferences and unknown animations cannot crash loading`() {
        assertEquals(InterventionConfig(), ConfigCodec.decode("not valid json"))
        assertEquals(BreathingAnimation.CIRCLE, ConfigCodec.decode("{\"animation\":\"REMOVED_STYLE\"}").animation)
    }

    @Test fun `saved values above five minutes normalize every duration boundary`() {
        val loaded = ConfigCodec.decode("{\"minUsageSeconds\":600,\"startUsageSeconds\":900,\"maxUsageSeconds\":1800}")
        assertEquals(300, loaded.minUsageSeconds)
        assertEquals(300, loaded.startUsageSeconds)
        assertEquals(300, loaded.maxUsageSeconds)
    }

    @Test fun `encoding untrusted settings also applies cap before writing storage`() {
        val invalid = InterventionConfig(minUsageSeconds = -1, startUsageSeconds = 1000, maxUsageSeconds = 1000)
        assertEquals(invalid.normalized(), ConfigCodec.decode(ConfigCodec.encode(invalid)))
    }

    @Test fun `old wait settings migrate to usage without losing breathing or timing choices`() {
        val old = """{"breathingSeconds":10,"inhaleSeconds":2,"holdSeconds":1,"exhaleSeconds":4,"haptics":false,"sound":true,"animation":"RIPPLE","minWaitSeconds":7,"startWaitSeconds":45,"maxWaitSeconds":180}"""
        val loaded = ConfigCodec.decode(old)
        assertEquals(10, loaded.breathingSeconds)
        assertEquals(7, loaded.minUsageSeconds)
        assertEquals(45, loaded.startUsageSeconds)
        assertEquals(180, loaded.maxUsageSeconds)
        assertFalse(loaded.haptics)
        assertTrue(loaded.sound)
        assertTrue(loaded.showQuotes)
        assertEquals(BreathingAnimation.RIPPLE, loaded.animation)
    }

    @Test fun `new usage fields override legacy values and quote toggle persists`() {
        val loaded = ConfigCodec.decode("""{"minWaitSeconds":30,"startWaitSeconds":60,"maxWaitSeconds":120,"minUsageSeconds":5,"startUsageSeconds":15,"maxUsageSeconds":300,"showQuotes":false}""")
        assertEquals(5, loaded.minUsageSeconds)
        assertEquals(15, loaded.startUsageSeconds)
        assertEquals(300, loaded.maxUsageSeconds)
        assertFalse(loaded.showQuotes)
        assertEquals(loaded, ConfigCodec.decode(ConfigCodec.encode(loaded)))
        val encoded = ConfigCodec.encode(loaded)
        assertFalse(encoded.contains("WaitSeconds"))
        assertTrue(encoded.contains("UsageSeconds"))
    }
}

