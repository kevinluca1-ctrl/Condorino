package com.condorino.weekend.data.update

import com.condorino.weekend.domain.model.AppUpdate

/**
 * Outcome of asking GitHub whether a newer release exists. Mirrors
 * [com.condorino.weekend.data.source.FlightSearchResult]: [NotConfigured] is a setup fact, not an
 * error, and [Failure] always carries a reason a user can act on rather than a bare exception.
 */
sealed interface UpdateCheckResult {
    data class Available(val update: AppUpdate) : UpdateCheckResult

    /** @param tagName the newest release found, so the UI can say *which* version you are on.
     *   Null only if the check could not name it. */
    data class UpToDate(val tagName: String? = null) : UpdateCheckResult

    /** This build has no baked-in release timestamp, so it cannot honestly say what "newer" means. */
    data class NotConfigured(val reason: String) : UpdateCheckResult
    data class Failure(val reason: String, val detail: String? = null) : UpdateCheckResult
}
