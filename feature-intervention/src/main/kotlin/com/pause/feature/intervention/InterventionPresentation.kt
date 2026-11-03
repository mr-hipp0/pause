package com.pause.feature.intervention

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The service verifies the intervention itself appeared, rather than any Pause window. */
object InterventionPresentation {
    private val visible = MutableStateFlow<String?>(null)
    val visibleSessionId: StateFlow<String?> = visible.asStateFlow()
    private var owner: Any? = null
    private var ownedSessionId: String? = null

    internal fun show(sessionId: String?, token: Any) {
        owner = token
        ownedSessionId = sessionId
        visible.value = sessionId
    }

    internal fun isOwner(token: Any) = owner === token
    fun ownsSession(sessionId: String) = owner != null && ownedSessionId == sessionId

    internal fun pause(token: Any) {
        if (isOwner(token)) visible.value = null
    }

    /** Older instances must not clear or finish a replacement presentation of the same attempt. */
    internal fun release(token: Any): Boolean {
        if (!isOwner(token)) return false
        visible.value = null
        owner = null
        ownedSessionId = null
        return true
    }
}
