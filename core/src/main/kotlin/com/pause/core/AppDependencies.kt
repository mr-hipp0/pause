package com.pause.core

import com.pause.domain.*
import kotlinx.coroutines.CoroutineScope

interface AppDependencies {
    val protections: ProtectionRepository
    val preferences: PreferencesRepository
    val history: HistoryRepository
    val catalog: AppCatalog
    val quotes: QuoteRepository
    val engine: InterventionEngine
    val scope: CoroutineScope
}

interface DependencyOwner { val dependencies: AppDependencies }
