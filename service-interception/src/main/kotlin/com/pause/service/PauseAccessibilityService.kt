package com.pause.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pause.core.DependencyOwner
import com.pause.domain.*
import com.pause.feature.intervention.InterventionActivity
import com.pause.feature.intervention.InterventionPresentation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Package/window transitions only: no screen text, hierarchy, gestures, or keystrokes. */
class PauseAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dependencies get() = (application as DependencyOwner).dependencies
    private var preferences: UserPreferences? = null
    private var protectedApps: Map<String, ProtectedApp> = emptyMap()
    private var lastForegroundPackage: String? = null
    private var foregroundObscured = false
    private var latestWindowEventTime = 0L
    private var pendingForeground: Pair<String, String?>? = null
    private var presentationJob: Job? = null
    private var presentationSessionId: String? = null
    private var foregroundRecheckJob: Job? = null
    private var homePackages = emptySet<String>()
    private var inputPackages = emptySet<String>()
    private var screenReceiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                endForScreenOff()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceScope.coroutineContext.cancelChildren()
        ProtectionServiceStatus.setConnected(false)
        preferences = null
        latestWindowEventTime = 0L
        refreshHomePackages()
        inputPackages = getSystemService(InputMethodManager::class.java)?.enabledInputMethodList
            ?.map { it.packageName }?.toSet().orEmpty()
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiverRegistered = true
        }
        createNotificationChannel()
        serviceScope.launch {
            combine(dependencies.preferences.preferences, dependencies.protections.apps) { prefs, apps -> prefs to apps }
                .collect { (prefs, apps) ->
                    preferences = prefs
                    protectedApps = apps.associateBy { it.packageName }
                    ProtectionServiceStatus.setConnected(true)
                    val active = dependencies.engine.state.value
                    if (!prefs.protectionEnabled || !prefs.accessibilityDisclosureAccepted ||
                        (active != null && !active.isPreview && protectedApps[active.target.packageName]?.enabled != true)) {
                        interruptRealSession()
                    }
                    pendingForeground?.also { (foreground, className) ->
                        pendingForeground = null
                        handleForeground(foreground, className)
                    }
                }
        }
        serviceScope.launch {
            dependencies.engine.state.collect { session ->
                if (session == null || session.phase == InterventionPhase.LAUNCHING || session.phase == InterventionPhase.USING) {
                    presentationJob?.cancel()
                    foregroundRecheckJob?.cancel()
                    presentationSessionId = null
                    NotificationManagerCompat.from(this@PauseAccessibilityService).cancel(NOTIFICATION_ID)
                } else if (!session.isPreview && session.phase == InterventionPhase.BREATHING &&
                    lastForegroundPackage == session.target.packageName && !foregroundObscured && screenUsable()) {
                    // Includes an automatic new breathing session when foreground usage expires.
                    presentIntervention(session.id)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val foreground = event.packageName?.toString() ?: return
        val className = event.className?.toString()
        // Guard lifecycle/pane callbacks are not foreground admissions or departures.
        // They must not advance ordering past a genuine target event queued by Android.
        if (foreground == packageName && className != "com.pause.app.MainActivity") return
        // Android may deliver an older launcher/pane event after a newer target event.
        // Replaying it would discard an allowance while the target is still on screen.
        if (event.eventTime < latestWindowEventTime) return
        latestWindowEventTime = event.eventTime
        val currentInput = currentInputPackage()
        val systemWindow = foreground in inputPackages || foreground == currentInput ||
            foreground == "com.android.systemui" || foreground.endsWith("permissioncontroller")
        if (preferences == null) {
            if (!systemWindow && (foreground != packageName || className == "com.pause.app.MainActivity")) {
                lastForegroundPackage = foreground
                pendingForeground = foreground to className
            }
            return
        }
        handleForeground(foreground, className)
    }

    private fun handleForeground(foreground: String, className: String?) {
        if (!screenUsable() || className?.contains("Emergency", ignoreCase = true) == true) {
            endForScreenOff()
            return
        }
        val keyboard = foreground in inputPackages || foreground == currentInputPackage()
        val systemOverlay = foreground == "com.android.systemui" || foreground.endsWith("permissioncontroller")
        if (keyboard || systemOverlay) {
            if (systemOverlay) foregroundObscured = true
            dependencies.engine.onForeground(foreground, isTransient = true, obscured = systemOverlay)
            return
        }
        if (foreground == packageName) {
            // Finishing a guard may emit delayed own-package events. Only opening MainActivity
            // is a genuine departure; MainActivity.onResume also reports it explicitly.
            if (className == "com.pause.app.MainActivity") {
                foregroundObscured = false
                lastForegroundPackage = foreground
                dependencies.engine.onForeground(foreground)
            }
            return
        }
        foregroundObscured = false
        lastForegroundPackage = foreground
        // Count confirmed target foreground time, or discard admission on an actual departure.
        val before = dependencies.engine.state.value
        if (before?.phase == InterventionPhase.LAUNCHING && foreground != before.target.packageName &&
            foreground !in homePackages && foreground != "com.android.settings") {
            dependencies.engine.finish(InterventionOutcome.LAUNCH_FAILED)
        } else {
            dependencies.engine.onForeground(foreground)
        }
        if (foreground in homePackages || foreground == "com.android.settings") return
        val prefs = preferences ?: return
        if (!prefs.protectionEnabled || !prefs.accessibilityDisclosureAccepted) return
        val protection = protectedApps[foreground]?.takeIf { it.enabled } ?: return
        refreshHomePackages()
        if (foreground in homePackages) return
        val current = dependencies.engine.state.value
        if (current?.target?.packageName == foreground &&
            (current.phase == InterventionPhase.LAUNCHING || current.phase == InterventionPhase.USING)) return

        if (current != null && !current.isPreview && InterventionPresentation.ownsSession(current.id)) {
            // Recheck an early foreground event after the old guard's pause/stop; stale events
            // must not close the guard, and genuine departures must not be dropped.
            foregroundRecheckJob?.cancel()
            foregroundRecheckJob = serviceScope.launch {
                delay(250)
                if (lastForegroundPackage != foreground || foregroundObscured ||
                    InterventionPresentation.visibleSessionId.value == current.id) return@launch
                val active = dependencies.engine.state.value
                // Start may have handed this exact generation to the target during the delay.
                // A stale guard recheck cannot revoke a confirmed usage allowance.
                if (active?.id == current.id &&
                    (active.phase == InterventionPhase.LAUNCHING || active.phase == InterventionPhase.USING)) return@launch
                if (active?.id == current.id) {
                    dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                }
                handleForeground(foreground, className)
            }
            return
        }
        val target = try {
            val info = packageManager.getApplicationInfo(foreground, 0)
            InstalledApp(foreground, packageManager.getApplicationLabel(info).toString(), AppCategory.OTHER)
        } catch (_: PackageManager.NameNotFoundException) { return }

        if (current == null || current.isPreview || current.target.packageName != foreground) {
            dependencies.engine.begin(target, (protection.config ?: prefs.defaults).normalized())
        }
        dependencies.engine.state.value?.takeIf { !it.isPreview &&
            (it.phase == InterventionPhase.BREATHING || it.phase == InterventionPhase.SELECTION) }
            ?.let { presentIntervention(it.id) }
    }

    private fun presentIntervention(sessionId: String) {
        val session = dependencies.engine.state.value?.takeIf { it.id == sessionId && !it.isPreview } ?: return
        if (session.phase != InterventionPhase.BREATHING && session.phase != InterventionPhase.SELECTION) return
        if (!screenUsable() || foregroundObscured) return
        if (InterventionPresentation.visibleSessionId.value == sessionId ||
            (presentationSessionId == sessionId && presentationJob?.isActive == true)) return
        presentationSessionId = sessionId
        performGlobalAction(GLOBAL_ACTION_HOME)
        presentationJob?.cancel()
        presentationJob = serviceScope.launch {
            var waited = 0
            while (lastForegroundPackage !in homePackages && waited < 600) {
                delay(40)
                waited += 40
            }
            val active = dependencies.engine.state.value?.takeIf { it.id == sessionId && !it.isPreview &&
                (it.phase == InterventionPhase.BREATHING || it.phase == InterventionPhase.SELECTION) } ?: return@launch
            try {
                startActivity(InterventionActivity.intent(this@PauseAccessibilityService))
            } catch (_: RuntimeException) {
                if (!showInterventionNotification(active.target.name)) dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                return@launch
            }
            delay(900)
            if (dependencies.engine.state.value?.id == sessionId && InterventionPresentation.visibleSessionId.value != sessionId) {
                if (!showInterventionNotification(active.target.name)) dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
            }
        }
    }

    private fun currentInputPackage() = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.let(ComponentName::unflattenFromString)?.packageName

    private fun refreshHomePackages() {
        homePackages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    private fun screenUsable() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun endForScreenOff() {
        lastForegroundPackage = null
        foregroundObscured = true
        foregroundRecheckJob?.cancel()
        val active = dependencies.engine.state.value
        if (active != null && !active.isPreview) {
            dependencies.engine.finish(if (active.phase == InterventionPhase.USING) InterventionOutcome.LEFT_APP else InterventionOutcome.INTERRUPTED)
        }
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.pause_intervention_channel), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun showInterventionNotification(targetName: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return false
        if (getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val pending = PendingIntent.getActivity(this, 1, InterventionActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.pause_intervention_notification_title))
                .setContentText(getString(R.string.pause_intervention_notification_body, targetName))
                .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            true
        } catch (_: SecurityException) { false }
    }

    override fun onInterrupt() = Unit

    private fun interruptRealSession() {
        if (dependencies.engine.state.value?.isPreview == false) dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ProtectionServiceStatus.setConnected(false)
        interruptRealSession()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ProtectionServiceStatus.setConnected(false)
        serviceScope.cancel()
        interruptRealSession()
        if (screenReceiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            screenReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pause_interventions"
        private const val NOTIFICATION_ID = 41
    }
}
