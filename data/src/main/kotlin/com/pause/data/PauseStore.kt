package com.pause.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pause.domain.AppCatalog
import com.pause.domain.HistoryRepository
import com.pause.domain.InterventionOutcome
import com.pause.domain.InterventionRecord
import com.pause.domain.RecordKind
import com.pause.domain.MAX_USAGE_SECONDS
import com.pause.domain.PreferencesRepository
import com.pause.domain.ProtectedApp
import com.pause.domain.ProtectionRepository
import com.pause.domain.ThemeMode
import com.pause.domain.UserPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Application-scoped local storage; construct exactly once in Application. */
class PauseStore(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val database = Room.databaseBuilder(appContext, PauseDatabase::class.java, "pause.db")
        .addMigrations(MIGRATION_1_2)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // onOpen runs before queued Room writes, recovering only previous-process attempts.
                db.execSQL(
                    "UPDATE interventions SET outcome = 'INTERRUPTED', finishedAtMillis = ? WHERE outcome IS NULL",
                    arrayOf(System.currentTimeMillis()),
                )
            }
        }).build()

    val protections: ProtectionRepository = object : ProtectionRepository {
        private val dao = database.protectionDao()
        override val apps = dao.observe().map { rows ->
            rows.map(ProtectedAppEntity::toProtectedApp)
        }
        override suspend fun upsert(app: ProtectedApp) {
            require(app.packageName.isNotBlank())
            dao.upsert(app.toEntity())
        }
        override suspend fun remove(packageName: String) = dao.remove(packageName)
        override suspend fun clear() = dao.clear()
    }

    val history: HistoryRepository = object : HistoryRepository {
        private val dao = database.interventionDao()
        override val records = dao.observe().map { rows ->
            rows.map {
                InterventionRecord(
                    id = it.id, packageName = it.packageName, appName = it.appName,
                    startedAtMillis = it.startedAtMillis, finishedAtMillis = it.finishedAtMillis,
                    outcome = enumValues<InterventionOutcome>().firstOrNull { outcome -> outcome.name == it.outcome },
                    waitSeconds = it.waitSeconds.coerceIn(0, 300), completedAtMillis = it.completedAtMillis,
                    kind = enumValues<RecordKind>().firstOrNull { kind -> kind.name == it.kind } ?: RecordKind.LEGACY_WAIT,
                    allocatedSeconds = it.allocatedSeconds.coerceIn(0, MAX_USAGE_SECONDS),
                    usedMillis = it.usedMillis.coerceIn(0, it.allocatedSeconds.coerceIn(0, MAX_USAGE_SECONDS) * 1000L),
                    usageStartedAtMillis = it.usageStartedAtMillis,
                )
            }
        }
        override suspend fun upsert(record: InterventionRecord) = dao.upsert(
            InterventionEntity(
                id = record.id, packageName = record.packageName, appName = record.appName,
                startedAtMillis = record.startedAtMillis, finishedAtMillis = record.finishedAtMillis,
                outcome = record.outcome?.name, waitSeconds = record.waitSeconds.coerceIn(0, 300),
                completedAtMillis = record.completedAtMillis,
                kind = record.kind.name,
                allocatedSeconds = record.allocatedSeconds.coerceIn(0, MAX_USAGE_SECONDS),
                usedMillis = record.usedMillis.coerceIn(0, record.allocatedSeconds.coerceIn(0, MAX_USAGE_SECONDS) * 1000L),
                usageStartedAtMillis = record.usageStartedAtMillis,
            ),
        )
        override suspend fun clear() = dao.clear()
    }

    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
        produceFile = { appContext.preferencesDataStoreFile("pause_preferences") },
    )

    val preferences: PreferencesRepository = object : PreferencesRepository {
        private val enabled = booleanPreferencesKey("protection_enabled")
        private val theme = stringPreferencesKey("theme")
        private val onboarding = booleanPreferencesKey("onboarding_complete")
        private val disclosure = booleanPreferencesKey("accessibility_disclosure_accepted")
        private val defaults = stringPreferencesKey("intervention_defaults")

        override val preferences = dataStore.data.catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }.map(::read)

        private fun read(values: Preferences): UserPreferences = UserPreferences(
                protectionEnabled = values[enabled] ?: true,
                theme = enumValues<ThemeMode>().firstOrNull { it.name == values[theme] } ?: ThemeMode.DARK,
                onboardingComplete = values[onboarding] ?: false,
                accessibilityDisclosureAccepted = values[disclosure] ?: false,
                defaults = ConfigCodec.decode(values[defaults]),
            )

        override suspend fun update(value: UserPreferences) = update { value }

        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            dataStore.edit { values ->
                val value = transform(read(values))
                values[enabled] = value.protectionEnabled
                values[theme] = value.theme.name
                values[onboarding] = value.onboardingComplete
                values[disclosure] = value.accessibilityDisclosureAccepted
                values[defaults] = ConfigCodec.encode(value.defaults)
            }
        }

        override suspend fun clear() { dataStore.edit { it.clear() } }
    }

    val catalog: AppCatalog = AndroidAppCatalog(appContext)
}
