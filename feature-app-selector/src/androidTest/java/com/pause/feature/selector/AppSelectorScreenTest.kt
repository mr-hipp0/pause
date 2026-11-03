package com.pause.feature.selector

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pause.core.AppDependencies
import com.pause.domain.AppCatalog
import com.pause.domain.AppCategory
import com.pause.domain.HistoryRepository
import com.pause.domain.InstalledApp
import com.pause.domain.InterventionEngine
import com.pause.domain.InterventionRecord
import com.pause.domain.PreferencesRepository
import com.pause.domain.ProtectedApp
import com.pause.domain.ProtectionRepository
import com.pause.domain.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises selection through the UI, including the scope of a filtered bulk action. */
@RunWith(AndroidJUnit4::class)
class AppSelectorScreenTest {
    @get:Rule val compose = createComposeRule()
    private val dependencies = FakeDependencies()

    @After fun tearDown() { dependencies.scope.cancel() }

    @Test fun selectingMultipleAppsAndDeselectingSearchResultsPreservesOtherSelections() {
        compose.setContent { MaterialTheme { AppSelectorScreen(dependencies, onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Protect Alpha").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithContentDescription("Protect Alpha").performClick()
        compose.onNodeWithContentDescription("Protect Beta").performClick()
        compose.waitUntil(5_000) { dependencies.protections.apps.value.size == 2 }
        compose.onNodeWithContentDescription("Protect Alpha").assertIsOn()
        compose.onNodeWithContentDescription("Protect Beta").assertIsOn()

        compose.onNodeWithText("Search apps").performTextInput("Alpha")
        compose.onNodeWithText("Deselect visible apps").performClick()
        compose.waitUntil(5_000) { dependencies.protections.apps.value.map { it.packageName } == listOf("test.beta") }
        compose.onNodeWithText("Search apps").performTextClearance()
        compose.onNodeWithContentDescription("Protect Alpha").assertIsOff()
        compose.onNodeWithContentDescription("Protect Beta").assertIsOn()

        compose.onNodeWithText("Refresh").performClick()
        compose.waitForIdle()
        assertEquals(listOf("test.beta"), dependencies.protections.apps.value.map { it.packageName })
        compose.onNodeWithContentDescription("Protect Beta").assertIsOn()
    }

    @Test fun manualCategoryOverridesAndroidMetadataInFilters() {
        dependencies.protections.apps.value = listOf(ProtectedApp("test.beta", categoryOverride = AppCategory.SHOPPING))
        compose.setContent { MaterialTheme { AppSelectorScreen(dependencies, onBack = {}) } }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Shopping").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("Shopping").performClick()
        compose.onNodeWithContentDescription("Protect Beta").assertIsOn()
        assertEquals(0, compose.onAllNodesWithContentDescription("Protect Alpha").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Communication").fetchSemanticsNodes().size)
    }

    private class FakeDependencies : AppDependencies {
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        override val protections = FakeProtections()
        override val preferences = object : PreferencesRepository {
            override val preferences = MutableStateFlow(UserPreferences())
            override suspend fun update(value: UserPreferences) { preferences.value = value }
            override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
                preferences.value = transform(preferences.value)
            }
            override suspend fun clear() { preferences.value = UserPreferences() }
        }
        override val history = object : HistoryRepository {
            override val records = MutableStateFlow<List<InterventionRecord>>(emptyList())
            override suspend fun upsert(record: InterventionRecord) {
                records.value = records.value.filterNot { it.id == record.id } + record
            }
            override suspend fun clear() { records.value = emptyList() }
        }
        override val catalog = object : AppCatalog {
            override suspend fun listApps() = listOf(
                InstalledApp("test.alpha", "Alpha", AppCategory.SOCIAL),
                InstalledApp("test.beta", "Beta", AppCategory.COMMUNICATION),
            )
        }
        override val engine = InterventionEngine(scope, history)
    }

    private class FakeProtections : ProtectionRepository {
        override val apps = MutableStateFlow<List<ProtectedApp>>(emptyList())
        override suspend fun upsert(app: ProtectedApp) {
            apps.value = apps.value.filterNot { it.packageName == app.packageName } + app
        }
        override suspend fun remove(packageName: String) { apps.value = apps.value.filterNot { it.packageName == packageName } }
        override suspend fun clear() { apps.value = emptyList() }
    }
}

