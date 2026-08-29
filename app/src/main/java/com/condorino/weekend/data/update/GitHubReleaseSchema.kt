package com.condorino.weekend.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of GitHub's `GET /repos/{owner}/{repo}/releases` response this app reads.
 *
 * Deliberately `ignoreUnknownKeys` at the call site: GitHub's release object has dozens of fields
 * this app has no use for, and a future field GitHub adds must never break parsing.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
) {
    /** GitHub sets `published_at` only once a release is actually published; fall back is defensive. */
    val timestamp: String? get() = publishedAt ?: createdAt
}

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
    @SerialName("content_type") val contentType: String = "",
)
