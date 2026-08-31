package com.condorino.weekend.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class UpdateSelectionTest {

    private fun release(
        tag: String,
        publishedAt: String?,
        draft: Boolean = false,
        assets: List<GitHubReleaseAsset> = emptyList(),
    ) = GitHubRelease(tagName = tag, publishedAt = publishedAt, draft = draft, assets = assets)

    @Test
    fun `the newest published release wins, not the first one listed`() {
        val releases = listOf(
            release("alpha-01", "2026-08-01T00:00:00Z"),
            release("alpha-03", "2026-08-20T00:00:00Z"),
            release("alpha-02", "2026-08-10T00:00:00Z"),
        )
        assertEquals("alpha-03", UpdateSelection.pickLatestRelease(releases)?.tagName)
    }

    @Test
    fun `drafts are never offered as an update`() {
        val releases = listOf(
            release("alpha-01", "2026-08-01T00:00:00Z"),
            release("alpha-02", "2026-09-01T00:00:00Z", draft = true),
        )
        assertEquals("alpha-01", UpdateSelection.pickLatestRelease(releases)?.tagName)
    }

    @Test
    fun `no releases at all means no candidate`() {
        assertNull(UpdateSelection.pickLatestRelease(emptyList()))
    }

    @Test
    fun `the plain apk is preferred over the debug variant`() {
        val release = release(
            "alpha-02",
            "2026-09-01T00:00:00Z",
            assets = listOf(
                GitHubReleaseAsset("condorino-alpha-02-debug.apk", "https://example/debug.apk"),
                GitHubReleaseAsset("condorino-alpha-02.apk", "https://example/release.apk"),
            ),
        )
        assertEquals("condorino-alpha-02.apk", UpdateSelection.pickApkAsset(release)?.name)
    }

    @Test
    fun `the debug apk is offered if it is the only one attached`() {
        val release = release(
            "alpha-02",
            "2026-09-01T00:00:00Z",
            assets = listOf(GitHubReleaseAsset("condorino-alpha-02-debug.apk", "https://example/debug.apk")),
        )
        assertEquals("condorino-alpha-02-debug.apk", UpdateSelection.pickApkAsset(release)?.name)
    }

    @Test
    fun `a release with no apk asset at all offers nothing to install`() {
        val release = release(
            "alpha-02",
            "2026-09-01T00:00:00Z",
            assets = listOf(GitHubReleaseAsset("release-notes.pdf", "https://example/notes.pdf")),
        )
        assertNull(UpdateSelection.pickApkAsset(release))
    }

    @Test
    fun `newer-than comparison is a strict publish-time ordering`() {
        val installed = Instant.parse("2026-08-01T00:00:00Z")
        assertTrue(UpdateSelection.isNewer(Instant.parse("2026-08-02T00:00:00Z"), installed))
        assertTrue(!UpdateSelection.isNewer(installed, installed))
        assertTrue(!UpdateSelection.isNewer(Instant.parse("2026-07-01T00:00:00Z"), installed))
    }

    @Test
    fun `an unparseable or missing timestamp is null, never a guess`() {
        assertNull(UpdateSelection.parseInstant(null))
        assertNull(UpdateSelection.parseInstant(""))
        assertNull(UpdateSelection.parseInstant("not a date"))
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), UpdateSelection.parseInstant("2026-08-01T00:00:00Z"))
    }

    @Test
    fun `a release with the installed build's own tag is not an update`() {
        // The bug this prevents: the release workflow bakes RELEASE_PUBLISHED_AT when the build
        // starts, but GitHub stamps the release when it is created minutes later — so the release
        // always looked "newer" than the build inside it and was offered to its own users.
        assertTrue(UpdateSelection.isSameRelease("alpha-09", "alpha-09"))
    }

    @Test
    fun `tag matching ignores case and padding`() {
        assertTrue(UpdateSelection.isSameRelease(" Alpha-09 ", "alpha-09"))
    }

    @Test
    fun `a different tag is not the installed release`() {
        assertFalse(UpdateSelection.isSameRelease("alpha-10", "alpha-09"))
    }

    @Test
    fun `a build with no tag of its own can never claim to be a release`() {
        // A CI or local build has no baked-in tag; identity is unknowable, so the timestamp
        // comparison is left to decide rather than silently reporting "up to date".
        assertFalse(UpdateSelection.isSameRelease("alpha-09", ""))
        assertFalse(UpdateSelection.isSameRelease("alpha-09", null))
        assertFalse(UpdateSelection.isSameRelease("", ""))
        assertFalse(UpdateSelection.isSameRelease(null, null))
    }

    @Test
    fun `the timestamp skew that caused the bug no longer decides the outcome`() {
        // Real values from the alpha-08 release: baked at build start, published four minutes on.
        val bakedAtBuildTime = Instant.parse("2026-08-30T22:05:00Z")
        val githubPublishedAt = Instant.parse("2026-08-30T22:09:48Z")

        // By timestamp alone this release still looks newer than the build running it...
        assertTrue(UpdateSelection.isNewer(githubPublishedAt, bakedAtBuildTime))
        // ...which is exactly why identity is checked first.
        assertTrue(UpdateSelection.isSameRelease("alpha-08", "alpha-08"))
    }
}
