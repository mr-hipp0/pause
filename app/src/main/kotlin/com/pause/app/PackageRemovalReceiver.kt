package com.pause.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.pause.core.DependencyOwner
import com.pause.domain.InterventionOutcome
import kotlinx.coroutines.launch

/** The protected system broadcast also arrives when the application process was not running. */
class PackageRemovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
        val removed = intent.data?.schemeSpecificPart ?: return
        val dependencies = (context.applicationContext as DependencyOwner).dependencies
        val result = goAsync()
        dependencies.scope.launch {
            try {
                dependencies.protections.remove(removed)
                if (dependencies.engine.state.value?.target?.packageName == removed) {
                    dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                    dependencies.engine.flushHistory()
                }
            } catch (error: Exception) {
                Log.e("Pause", "Could not clean up a removed application's protection", error)
            } finally { result.finish() }
        }
    }
}
