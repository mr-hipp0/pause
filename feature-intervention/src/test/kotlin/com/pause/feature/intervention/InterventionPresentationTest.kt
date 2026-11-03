package com.pause.feature.intervention

import org.junit.Assert.*
import org.junit.Test

class InterventionPresentationTest {
    @Test fun staleActivityCannotReleaseReplacementOfSameSession() {
        val old = Any()
        val replacement = Any()
        InterventionPresentation.show("attempt", old)
        InterventionPresentation.show("attempt", replacement)
        assertFalse(InterventionPresentation.release(old))
        assertEquals("attempt", InterventionPresentation.visibleSessionId.value)
        assertTrue(InterventionPresentation.isOwner(replacement))
        assertTrue(InterventionPresentation.release(replacement))
        assertNull(InterventionPresentation.visibleSessionId.value)
    }

    @Test fun pauseHidesWindowButRetainsOwnershipForStopCancellation() {
        val token = Any()
        InterventionPresentation.show("attempt", token)
        InterventionPresentation.pause(token)
        assertNull(InterventionPresentation.visibleSessionId.value)
        assertTrue(InterventionPresentation.isOwner(token))
        assertTrue(InterventionPresentation.release(token))
        assertFalse(InterventionPresentation.release(token))
    }

    @Test fun stalePauseCannotHideCurrentWindow() {
        val old = Any()
        val current = Any()
        InterventionPresentation.show("previous", old)
        InterventionPresentation.show("new", current)
        InterventionPresentation.pause(old)
        assertEquals("new", InterventionPresentation.visibleSessionId.value)
        InterventionPresentation.release(current)
    }
}
