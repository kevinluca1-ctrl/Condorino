package com.condorino.weekend.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
