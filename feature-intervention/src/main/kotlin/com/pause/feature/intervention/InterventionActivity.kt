package com.pause.feature.intervention

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pause.core.DependencyOwner
import com.pause.core.PauseTheme
import com.pause.domain.InterventionOutcome
import com.pause.domain.BreathingQuote
import com.pause.domain.InterventionPhase
import com.pause.domain.UserPreferences
import com.pause.domain.ZEN_QUOTES_SOURCE_URL

class InterventionActivity : ComponentActivity() {
    private val dependencies get() = (application as DependencyOwner).dependencies
    private var leavingIntentionally = false
    private var presentedSessionId: String? = null
    private var sourceNavigationPending = false
    private val presentationToken = Any()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enterFullscreen()
        if (savedInstanceState == null && dependencies.engine.state.value?.phase.isIntervention()) {
            dependencies.engine.restartBreathingForPresentation()
        }
        presentedSessionId = dependencies.engine.state.value?.id
        setContent {
            val preferences by dependencies.preferences.preferences.collectAsState(initial = UserPreferences())
            val session by dependencies.engine.state.collectAsState()
            PauseTheme(mode = preferences.theme) {
                LaunchedEffect(session?.id, session?.phase) {
                    if (session == null || !session?.phase.isIntervention()) {
                        leavingIntentionally = true
                        finish()
                    }
                }
                BackHandler { cancelIntervention() }
                session?.takeIf { it.phase.isIntervention() }?.let { active ->
                    // A timer tick must never select another quote. The repository also keeps this
                    // assignment across Activity recreation and separates preview rotation.
                    var quote by remember(active.id, active.isPreview) { mutableStateOf<BreathingQuote?>(null) }
                    LaunchedEffect(active.id, active.isPreview, active.config.showQuotes) {
                        if (active.config.showQuotes && quote == null) {
                            quote = dependencies.quotes.forSession(active.id, active.isPreview)
                        }
                    }
                    InterventionScreen(
                        session = active,
                        onDurationChange = dependencies.engine::selectDuration,
                        onStart = ::startSession,
                        onCancel = ::cancelIntervention,
                        onContinue = { continueToSelection(active.id) },
                        quote = quote,
                        onQuoteSource = { openQuoteSource(active.id) },
                    )
                }
            }
        }
    }

    private fun openQuoteSource(sessionId: String) {
        val current = dependencies.engine.state.value ?: return
        if (current.id != sessionId || current.id != presentedSessionId ||
            current.phase !in setOf(InterventionPhase.BREATHING, InterventionPhase.DECISION) || !current.config.showQuotes ||
            leavingIntentionally || sourceNavigationPending || !InterventionPresentation.isOwner(presentationToken)) return
        sourceNavigationPending = true
        try {
            // Successful navigation follows the normal onStop departure rules. Do not finish the
            // session before launching: a device without a browser must retain a usable screen.
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ZEN_QUOTES_SOURCE_URL)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: RuntimeException) {
            sourceNavigationPending = false
            Toast.makeText(this, "No browser is available to open ZenQuotes.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelIntervention() {
        if (leavingIntentionally || !InterventionPresentation.isOwner(presentationToken)) return
        leavingIntentionally = true
        val session = dependencies.engine.finish(InterventionOutcome.CANCELLED)
        if (session != null && !session.isPreview) returnHome()
        finish()
    }

    private fun continueToSelection(sessionId: String) {
        val current = dependencies.engine.state.value ?: return
        if (current.id != sessionId || current.id != presentedSessionId ||
            current.phase != InterventionPhase.DECISION || leavingIntentionally || sourceNavigationPending ||
            !InterventionPresentation.isOwner(presentationToken)) return
        dependencies.engine.continueToSelection()
    }

    private fun startSession() {
        val current = dependencies.engine.state.value ?: return
        if (current.phase != InterventionPhase.SELECTION || current.id != presentedSessionId ||
            leavingIntentionally || !InterventionPresentation.isOwner(presentationToken)) return
        val launch = if (current.isPreview) null else
            runCatching { packageManager.getLaunchIntentForPackage(current.target.packageName) }.getOrNull()
        if (!current.isPreview && launch == null) {
            leavingIntentionally = true
            dependencies.engine.finish(InterventionOutcome.LAUNCH_FAILED)
            Toast.makeText(this, "This app is no longer available to open.", Toast.LENGTH_LONG).show()
            returnHome()
            finish()
            return
        }
        val requested = dependencies.engine.requestUsage() ?: return
        leavingIntentionally = true
        InterventionPresentation.pause(presentationToken)
        if (!requested.isPreview) {
            try {
                // Wait for the service to confirm the target before spending its allowance.
                startActivity(launch!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: RuntimeException) {
                if (dependencies.engine.state.value?.id == requested.id) dependencies.engine.finish(InterventionOutcome.LAUNCH_FAILED)
                Toast.makeText(this, "Android could not open ${requested.target.name}.", Toast.LENGTH_LONG).show()
                returnHome()
            }
        }
        finish()
    }

    private fun returnHome() {
        runCatching {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun enterFullscreen() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onStop() {
        super.onStop()
        val owned = InterventionPresentation.release(presentationToken)
        val current = dependencies.engine.state.value
        if (owned && !isChangingConfigurations && !leavingIntentionally &&
            current?.id == presentedSessionId && current?.phase.isIntervention()) {
            dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        sourceNavigationPending = false
        val current = dependencies.engine.state.value
        if (current == null || !current.phase.isIntervention()) {
            leavingIntentionally = true
            finish()
            return
        }
        presentedSessionId = current.id
        InterventionPresentation.show(current.id, presentationToken)
    }

    override fun onPause() {
        InterventionPresentation.pause(presentationToken)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val current = dependencies.engine.state.value ?: return
        if (current.id != presentedSessionId && current.phase.isIntervention()) {
            leavingIntentionally = false
            dependencies.engine.restartBreathingForPresentation()
            presentedSessionId = current.id
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                InterventionPresentation.show(current.id, presentationToken)
            }
        }
    }

    private fun InterventionPhase?.isIntervention() =
        this == InterventionPhase.BREATHING || this == InterventionPhase.DECISION || this == InterventionPhase.SELECTION

    companion object {
        fun intent(context: Context): Intent = Intent(context, InterventionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
