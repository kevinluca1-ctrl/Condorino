package com.condorino.weekend.domain.model

import java.time.Instant

/**
 * A GitHub Release the app can offer to install: this is not "a new version exists somewhere",
 * it is one specific release with a specific APK attached, already resolved.
 */
data class AppUpdate(
    val tagName: String,
    val releaseName: String,
    /** Release-notes body, trimmed to a sane length for display. Null if the release had none. */
    val notes: String?,
    val htmlUrl: String,
    val publishedAt: Instant,
    val apkAssetName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
)
