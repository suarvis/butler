package com.suarvis.butler.core.domain.session

import com.suarvis.butler.core.domain.generic.SessionId

/**
 * How a user points at a session: an explicit id, or "the most recently updated one".
 * `latest`, `last` and `recent` are aliases, as in Clawcode.
 */
sealed interface SessionReference {
    data object Latest : SessionReference

    data class ById(val id: SessionId) : SessionReference

    fun describe(): String =
        when (this) {
            Latest -> "latest"
            is ById -> id.value
        }

    companion object {
        private val LATEST_ALIASES = setOf("latest", "last", "recent")

        fun parse(raw: String): SessionReference {
            val trimmed = raw.trim()
            return if (trimmed.lowercase() in LATEST_ALIASES) Latest else ById(SessionId(trimmed))
        }
    }
}
