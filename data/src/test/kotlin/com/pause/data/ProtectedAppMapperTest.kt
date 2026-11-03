package com.pause.data

import com.pause.domain.AppCategory
import com.pause.domain.InterventionConfig
import com.pause.domain.ProtectedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectedAppMapperTest {
    @Test fun `manual communication and shopping assignments survive storage mapping`() {
        listOf(AppCategory.COMMUNICATION, AppCategory.SHOPPING).forEach { category ->
            val app = ProtectedApp("example.target", config = InterventionConfig(breathingSeconds = 10), categoryOverride = category)
            val entity = app.toEntity()
            assertEquals(category.name, entity.categoryOverride)
            assertEquals(app, entity.toProtectedApp())
        }
    }

    @Test fun `absent override retains automatic category selection`() {
        val app = ProtectedApp("example.target", enabled = false)
        assertNull(app.toEntity().categoryOverride)
        assertEquals(app, app.toEntity().toProtectedApp())
    }

    @Test fun `unrecognized saved category safely returns to automatic selection`() {
        val entity = ProtectedAppEntity("example.target", enabled = true, configJson = null, categoryOverride = "REMOVED_CATEGORY")
        val app = entity.toProtectedApp()
        assertEquals("example.target", app.packageName)
        assertNull(app.categoryOverride)
    }
}
