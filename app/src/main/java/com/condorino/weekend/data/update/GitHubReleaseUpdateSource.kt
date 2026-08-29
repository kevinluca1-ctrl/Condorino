package com.condorino.weekend.data.update

import com.condorino.weekend.BuildConfig
import com.condorino.weekend.R
import com.condorino.weekend.data.source.SourceStrings
import com.condorino.weekend.domain.model.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Checks this app's own GitHub Releases for a newer build than the one currently running.
 *
 * The endpoint is public and unauthenticated — `GET /repos/{owner}/{repo}/releases` — which only
 * works because the repository is public. If it is not, GitHub answers 404 to every unauthenticated
 * request, indistinguishable from "no releases exist yet"; the 404 branch below is worded to cover
 * both honestly rather than guess which one it is. Nothing here embeds a GitHub token: shipping a
 * credential inside a client APK that anyone can extract would defeat the point of keeping it
 * secret, so this source simply does not support private repositories.
 *
 * "Newer" is decided by publish timestamp, not by comparing tag names — see [UpdateSelection]. That
 * timestamp only exists for a build the release workflow produced; a CI or manually built APK has
 * no baked-in value to compare against, see [BuildConfig.RELEASE_PUBLISHED_AT] in `build.gradle.kts`.
 */
class GitHubReleaseUpdateSource(
    private val client: OkHttpClient,
    private val strings: SourceStrings,
    private val owner: String = BuildConfig.UPDATE_REPO_OWNER,
    private val repo: String = BuildConfig.UPDATE_REPO_NAME,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val installedAt = UpdateSelection.parseInstant(BuildConfig.RELEASE_PUBLISHED_AT)
        if (installedAt == null) {
            return@withContext UpdateCheckResult.NotConfigured(strings.get(R.string.update_not_a_release_build))
        }

        try {
            val url = "https://api.github.com/repos/$owner/$repo/releases"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> return@withContext UpdateCheckResult.Failure(strings.get(R.string.update_not_found))
                    403 -> return@withContext UpdateCheckResult.Failure(strings.get(R.string.update_rate_limited))
                }
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Failure(
                        strings.get(R.string.update_http, response.code),
                        response.message,
                    )
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return@withContext UpdateCheckResult.Failure(strings.get(R.string.update_empty_response))
                }

                val releases = json.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
                val latest = UpdateSelection.pickLatestRelease(releases)
                    ?: return@withContext UpdateCheckResult.Failure(strings.get(R.string.update_no_releases))
                val publishedAt = UpdateSelection.parseInstant(latest.timestamp)
                    ?: return@withContext UpdateCheckResult.Failure(strings.get(R.string.update_unparseable_date))

                if (!UpdateSelection.isNewer(publishedAt, installedAt)) {
                    return@withContext UpdateCheckResult.UpToDate
                }

                val asset = UpdateSelection.pickApkAsset(latest)
                    ?: return@withContext UpdateCheckResult.Failure(
                        strings.get(R.string.update_no_apk_asset, latest.tagName),
                    )

                UpdateCheckResult.Available(
                    AppUpdate(
                        tagName = latest.tagName,
                        releaseName = latest.name?.takeIf { it.isNotBlank() } ?: latest.tagName,
                        notes = latest.body?.trim()?.take(MAX_NOTES_LENGTH)?.takeIf { it.isNotBlank() },
                        htmlUrl = latest.htmlUrl,
                        publishedAt = publishedAt,
                        apkAssetName = asset.name,
                        apkDownloadUrl = asset.browserDownloadUrl,
                        apkSizeBytes = asset.size,
                    ),
                )
            }
        } catch (e: IOException) {
            UpdateCheckResult.Failure(strings.get(R.string.update_offline), e.message)
        } catch (e: Exception) {
            UpdateCheckResult.Failure(strings.get(R.string.update_parse_failed), e.message)
        }
    }

    private companion object {
        const val MAX_NOTES_LENGTH = 2000
    }
}
