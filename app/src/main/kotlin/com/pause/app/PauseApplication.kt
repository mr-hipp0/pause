package com.pause.app

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.pause.core.AppDependencies
import com.pause.core.DependencyOwner
import com.pause.data.PauseStore
import com.pause.data.OfflineQuoteRepository
import com.pause.domain.*
import kotlinx.coroutines.*

class PauseApplication : Application(), DependencyOwner {
    override lateinit var dependencies: AppDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error ->
            Log.e("Pause", "A local operation failed", error)
        })
        val store = PauseStore(this, applicationScope)
        val quoteRepository = OfflineQuoteRepository(this, applicationScope)
        dependencies = object : AppDependencies {
            override val scope = applicationScope
            override val protections = store.protections
            override val preferences = store.preferences
            override val history = store.history
            override val catalog = store.catalog
            override val quotes = quoteRepository
            override val engine = InterventionEngine(scope, history, object : MonotonicClock {
                override fun elapsedRealtimeMillis() = SystemClock.elapsedRealtime()
            })
        }
    }
}
