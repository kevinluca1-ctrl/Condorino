package com.condorino.weekend.data.update

import java.time.Instant

/**
 * The pure decision logic behind "is there an update, and which file do I download" — kept free of
 * Android and of network I/O so it is directly unit-testable, the same reasoning as
 * [com.condorino.weekend.data.source.FeedParser].
 */
object UpdateSelection {

    /** Drafts are excluded; the newest by publish time wins, not the first one GitHub happens to list. */
    fun pickLatestRelease(releases: List<GitHubRelease>): GitHubRelease? =
        releases.filterNot { it.draft }.maxByOrNull { parseInstant(it.timestamp) ?: Instant.EPOCH }

    /**
     * The plain release APK, never the `-debug` variant, if both were attached. Falls back to
     * whichever `.apk` asset exists so a release is never treated as "nothing to install" purely
     * because of a naming quirk.
     */
    fun pickApkAsset(release: GitHubRelease): GitHubReleaseAsset? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        return apks.firstOrNull { !it.name.endsWith("-debug.apk", ignoreCase = true) } ?: apks.firstOrNull()
    }

    /**
     * @param installedPublishedAt this build's own release timestamp, baked in at build time by
     *   the release workflow. Comparing timestamps rather than tag names holds even if a future
     *   release is not named in the `alpha-NN` scheme.
     */
    fun isNewer(candidatePublishedAt: Instant, installedPublishedAt: Instant): Boolean =
        candidatePublishedAt.isAfter(installedPublishedAt)

    fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }
}
