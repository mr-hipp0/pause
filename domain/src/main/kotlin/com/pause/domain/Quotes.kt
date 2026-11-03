package com.pause.domain

/** The text and its attribution always travel as a single immutable record. */
data class BreathingQuote(val id: String, val text: String, val author: String)

const val ZEN_QUOTES_SOURCE_NAME = "ZenQuotes API"
const val ZEN_QUOTES_SOURCE_URL = "https://zenquotes.io/"

interface QuoteRepository {
    /** Stable for the active session and its recreated presentation; previews do not advance rotation. */
    suspend fun forSession(sessionId: String, isPreview: Boolean = false): BreathingQuote?
    /** Removes local rotation progress while retaining the bundled collection. */
    suspend fun clear()
}
