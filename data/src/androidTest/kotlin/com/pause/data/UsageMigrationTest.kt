package com.pause.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pause.domain.AppCategory
import com.pause.domain.InterventionOutcome
import com.pause.domain.RecordKind
import com.pause.domain.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the exact on-disk v1 schema, production Room migration, and old DataStore keys. */
@RunWith(AndroidJUnit4::class)
class UsageMigrationTest {
    @Test fun upgradingAnExistingInstallPreservesSelectionsAndKeepsWaitHistoryLegacy() = runBlocking {
        // This module's instrumentation target is the isolated com.pause.data.test APK.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".test"))
        context.deleteDatabase("pause.db")
        createVersionOneDatabase(context)

        val oldPreferenceJob = SupervisorJob()
        val oldPreferences = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(oldPreferenceJob + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("pause_preferences") },
        )
        oldPreferences.edit { values ->
            values.clear()
            values[booleanPreferencesKey("protection_enabled")] = true
            values[booleanPreferencesKey("onboarding_complete")] = true
            values[booleanPreferencesKey("accessibility_disclosure_accepted")] = true
            values[stringPreferencesKey("theme")] = "LIGHT"
            values[stringPreferencesKey("intervention_defaults")] =
                """{"breathingSeconds":15,"inhaleSeconds":4,"holdSeconds":2,"exhaleSeconds":6,"minWaitSeconds":5,"startWaitSeconds":30,"maxWaitSeconds":900,"haptics":false,"sound":true}"""
        }
        oldPreferenceJob.cancelAndJoin()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PauseStore(context, scope)
            withTimeout(30_000) {
                val selected = store.protections.apps.first().single()
                assertEquals("test.protected", selected.packageName)
                assertTrue(selected.enabled)
                assertEquals(AppCategory.SHOPPING, selected.categoryOverride)
                val config = requireNotNull(selected.config)
                assertEquals(10, config.breathingSeconds)
                assertEquals(7, config.minUsageSeconds)
                assertEquals(45, config.startUsageSeconds)
                assertEquals(120, config.maxUsageSeconds)
                assertTrue(config.showQuotes)

                val preferences = store.preferences.preferences.first()
                assertEquals(ThemeMode.LIGHT, preferences.theme)
                assertTrue(preferences.protectionEnabled)
                assertTrue(preferences.onboardingComplete)
                assertTrue(preferences.accessibilityDisclosureAccepted)
                assertEquals(15, preferences.defaults.breathingSeconds)
                assertEquals(4, preferences.defaults.inhaleSeconds)
                assertEquals(2, preferences.defaults.holdSeconds)
                assertEquals(6, preferences.defaults.exhaleSeconds)
                assertEquals(5, preferences.defaults.minUsageSeconds)
                assertEquals(30, preferences.defaults.startUsageSeconds)
                assertEquals(300, preferences.defaults.maxUsageSeconds)
                assertFalse(preferences.defaults.haptics)
                assertTrue(preferences.defaults.sound)

                val history = store.history.records.first()
                assertEquals(2, history.size)
                assertTrue(history.all { it.kind == RecordKind.LEGACY_WAIT })
                assertTrue(history.all { it.allocatedSeconds == 0 && it.usedMillis == 0L && it.usageStartedAtMillis == null })
                val complete = history.single { it.id == "legacy-complete" }
                assertEquals(InterventionOutcome.CONTINUED, complete.outcome)
                assertEquals(30, complete.waitSeconds)
                assertEquals(2_000L, complete.completedAtMillis)
                assertEquals(3_000L, complete.finishedAtMillis)
                val interrupted = history.single { it.id == "legacy-unfinished" }
                assertEquals(InterventionOutcome.INTERRUPTED, interrupted.outcome)
                assertTrue(interrupted.finishedAtMillis != null)

                store.preferences.update { it.copy(defaults = it.defaults.copy(showQuotes = false)) }
                assertFalse(store.preferences.preferences.first().defaults.showQuotes)
                assertEquals(1, store.protections.apps.first().size)
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath("pause.db").absolutePath, null,
                SQLiteDatabase.OPEN_READONLY).use { database ->
                assertEquals(2, database.version)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun createVersionOneDatabase(context: Context) {
        context.openOrCreateDatabase("pause.db", Context.MODE_PRIVATE, null).use { database ->
            database.execSQL("CREATE TABLE protected_apps (packageName TEXT NOT NULL, enabled INTEGER NOT NULL, configJson TEXT, categoryOverride TEXT, PRIMARY KEY(packageName))")
            database.execSQL("CREATE TABLE interventions (id TEXT NOT NULL, packageName TEXT NOT NULL, appName TEXT NOT NULL, startedAtMillis INTEGER NOT NULL, finishedAtMillis INTEGER, outcome TEXT, waitSeconds INTEGER NOT NULL, completedAtMillis INTEGER, PRIMARY KEY(id))")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, '4af5977c75bc06e6eb94b6b4eebf14d4')")
            val override = """{"breathingSeconds":10,"minWaitSeconds":7,"startWaitSeconds":45,"maxWaitSeconds":120}"""
            database.execSQL("INSERT INTO protected_apps VALUES (?, ?, ?, ?)",
                arrayOf("test.protected", 1, override, "SHOPPING"))
            database.execSQL("INSERT INTO interventions VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-complete", "test.protected", "Protected", 1_000L, 3_000L, "CONTINUED", 30, 2_000L))
            database.execSQL("INSERT INTO interventions VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("legacy-unfinished", "test.protected", "Protected", 4_000L, null, null, 0, null))
            database.version = 1
        }
    }
}
