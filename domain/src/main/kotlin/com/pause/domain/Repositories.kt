package com.pause.domain

import kotlinx.coroutines.flow.Flow

interface ProtectionRepository {
    val apps: Flow<List<ProtectedApp>>
    suspend fun upsert(app: ProtectedApp)
    suspend fun remove(packageName: String)
    suspend fun clear()
}

interface PreferencesRepository {
    val preferences: Flow<UserPreferences>
    suspend fun update(value: UserPreferences)
    suspend fun update(transform: (UserPreferences) -> UserPreferences)
    suspend fun clear()
}

interface HistoryRepository {
    val records: Flow<List<InterventionRecord>>
    suspend fun upsert(record: InterventionRecord)
    suspend fun clear()
}

interface AppCatalog {
    suspend fun listApps(): List<InstalledApp>
}

interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
    fun epochMillis(): Long = System.currentTimeMillis()
}

object SystemMonotonicClock : MonotonicClock {
    override fun elapsedRealtimeMillis(): Long = System.nanoTime() / 1_000_000L
}
