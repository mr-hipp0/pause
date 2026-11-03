package com.pause.domain

const val MAX_USAGE_SECONDS = 300

enum class AppCategory { SOCIAL, VIDEO, GAMES, COMMUNICATION, SHOPPING, PRODUCTIVITY, OTHER }
enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class BreathingAnimation { CIRCLE, RIPPLE }

data class InstalledApp(val packageName: String, val name: String, val category: AppCategory)

data class InterventionConfig(
    val breathingSeconds: Int = 5,
    val inhaleSeconds: Int = 4,
    val holdSeconds: Int = 0,
    val exhaleSeconds: Int = 4,
    val haptics: Boolean = true,
    val sound: Boolean = false,
    val animation: BreathingAnimation = BreathingAnimation.CIRCLE,
    val minUsageSeconds: Int = 5,
    val startUsageSeconds: Int = 15,
    val maxUsageSeconds: Int = MAX_USAGE_SECONDS,
    val showQuotes: Boolean = true,
) {
    /** Every ingress uses this, including saved data, so no setting can bypass the five-minute cap. */
    fun normalized(): InterventionConfig {
        val maximum = maxUsageSeconds.coerceIn(1, MAX_USAGE_SECONDS)
        val minimum = minUsageSeconds.coerceIn(1, maximum)
        return copy(
            breathingSeconds = breathingSeconds.coerceIn(3, 120),
            inhaleSeconds = inhaleSeconds.coerceIn(1, 30),
            holdSeconds = holdSeconds.coerceIn(0, 30),
            exhaleSeconds = exhaleSeconds.coerceIn(1, 30),
            minUsageSeconds = minimum,
            startUsageSeconds = startUsageSeconds.coerceIn(minimum, maximum),
            maxUsageSeconds = maximum,
        )
    }
}

data class ProtectedApp(
    val packageName: String,
    val enabled: Boolean = true,
    val config: InterventionConfig? = null,
    val categoryOverride: AppCategory? = null,
)

data class UserPreferences(
    val protectionEnabled: Boolean = true,
    val theme: ThemeMode = ThemeMode.DARK,
    val onboardingComplete: Boolean = false,
    val accessibilityDisclosureAccepted: Boolean = false,
    val defaults: InterventionConfig = InterventionConfig(),
)

enum class InterventionOutcome { CONTINUED, CANCELLED, INTERRUPTED, LAUNCH_FAILED, EXPIRED, LEFT_APP }
enum class RecordKind { LEGACY_WAIT, USAGE }

data class InterventionRecord(
    val id: String,
    val packageName: String,
    val appName: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val outcome: InterventionOutcome? = null,
    val waitSeconds: Int = 0,
    val completedAtMillis: Long? = null,
    val kind: RecordKind = RecordKind.LEGACY_WAIT,
    val allocatedSeconds: Int = 0,
    val usedMillis: Long = 0,
    val usageStartedAtMillis: Long? = null,
)

enum class InterventionPhase { BREATHING, SELECTION, LAUNCHING, USING }

data class InterventionSession(
    val id: String,
    val target: InstalledApp,
    val config: InterventionConfig,
    val phase: InterventionPhase,
    val selectedSeconds: Int,
    val remainingMillis: Long,
    val startedAtMillis: Long,
    val isPreview: Boolean = false,
    val usedMillis: Long = 0,
)
