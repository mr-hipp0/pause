package com.pause.data

import com.pause.domain.BreathingAnimation
import com.pause.domain.InterventionConfig
import org.json.JSONObject

internal object ConfigCodec {
    fun encode(value: InterventionConfig): String {
        val config = value.normalized()
        return JSONObject().apply {
            put("breathingSeconds", config.breathingSeconds)
            put("inhaleSeconds", config.inhaleSeconds)
            put("holdSeconds", config.holdSeconds)
            put("exhaleSeconds", config.exhaleSeconds)
            put("haptics", config.haptics)
            put("sound", config.sound)
            put("animation", config.animation.name)
            put("minUsageSeconds", config.minUsageSeconds)
            put("startUsageSeconds", config.startUsageSeconds)
            put("maxUsageSeconds", config.maxUsageSeconds)
            put("showQuotes", config.showQuotes)
        }.toString()
    }

    fun decode(raw: String?): InterventionConfig {
        if (raw == null) return InterventionConfig()
        return runCatching {
            val json = JSONObject(raw)
            val defaults = InterventionConfig()
            InterventionConfig(
                breathingSeconds = json.optInt("breathingSeconds", defaults.breathingSeconds),
                inhaleSeconds = json.optInt("inhaleSeconds", defaults.inhaleSeconds),
                holdSeconds = json.optInt("holdSeconds", defaults.holdSeconds),
                exhaleSeconds = json.optInt("exhaleSeconds", defaults.exhaleSeconds),
                haptics = json.optBoolean("haptics", defaults.haptics),
                sound = json.optBoolean("sound", defaults.sound),
                animation = enumValues<BreathingAnimation>().firstOrNull { it.name == json.optString("animation") } ?: defaults.animation,
                minUsageSeconds = json.optInt("minUsageSeconds", json.optInt("minWaitSeconds", defaults.minUsageSeconds)),
                startUsageSeconds = json.optInt("startUsageSeconds", json.optInt("startWaitSeconds", defaults.startUsageSeconds)),
                maxUsageSeconds = json.optInt("maxUsageSeconds", json.optInt("maxWaitSeconds", defaults.maxUsageSeconds)),
                showQuotes = json.optBoolean("showQuotes", defaults.showQuotes),
            ).normalized()
        }.getOrDefault(InterventionConfig())
    }
}
