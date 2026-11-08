package com.openlauncher.app.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the in-app updater's pure logic: GitHub JSON parsing, the CI
 * version marker in release NOTES, asset picking and the update decision.
 * (Networking and the DownloadManager/installer plumbing are intentionally
 * outside this class and need a device.)
 */
class UpdateCheckerTest {

    private val gson = Gson()

    private val realReleaseJson = """
    {
      "tag_name": "dev-latest",
      "target_commitish": "75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6",
      "name": "dev-latest (rolling)",
      "body": "Rolling debug build of the integration branch dev.\nversionCode=7 versionName=0.0.6 commit=75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6",
      "published_at": "2026-11-08T06:53:17Z",
      "assets": [
        { "name": "unit-test-report-dev", "browser_download_url": "https://example.invalid/x.zip", "size": 100 },
        { "name": "openlauncher-dev-debug.apk", "browser_download_url": "https://example.invalid/app.apk", "size": 19618880 }
      ]
    }
    """

    @Test fun `parses a real-shaped GitHub release response`() {
        val release = gson.fromJson(realReleaseJson, GitHubRelease::class.java)
        assertEquals("dev-latest", release.tagName)
        assertEquals(2, release.assets.size)
        assertEquals("2026-11-08T06:53:17Z", release.publishedAt)
    }

    @Test fun `remote update combines asset, marker and marker commit`() {
        val release = gson.fromJson(realReleaseJson, GitHubRelease::class.java)
        val remote = buildRemoteUpdate(release, UpdateChannel.DEV)!!
        assertEquals(7, remote.versionCode)
        assertEquals("0.0.6", remote.versionName)
        assertEquals("75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6", remote.commit)
        assertEquals("openlauncher-dev-debug.apk", remote.fileName)
        assertEquals(19618880L, remote.sizeBytes)
    }

    @Test fun `marker missing falls back to sha-shaped target_commitish`() {
        val release = gson.fromJson(realReleaseJson.replace(
            "versionCode=7 versionName=0.0.6 commit=75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6", "no marker here"
        ), GitHubRelease::class.java)
        val remote = buildRemoteUpdate(release, UpdateChannel.DEV)!!
        assertNull(remote.versionCode)
        assertEquals("75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6", remote.commit)
    }

    @Test fun `branch-shaped target_commitish is not treated as a commit`() {
        val release = gson.fromJson(
            realReleaseJson.replace("\"75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6\", ", "\"dev\", ")
                .replace("commit=75bd52a1e0c0d1a2b3c4d5e6f7081920a3b4c5d6", "commit=75bd52a"),
            GitHubRelease::class.java
        )
        val remote = buildRemoteUpdate(release, UpdateChannel.DEV)!!
        assertEquals("75bd52a", remote.commit)   // still from the marker, not "dev"
    }

    @Test fun `asset pick prefers exact channel name then any apk then null`() {
        val assets = listOf(
            GitHubAsset("notes.txt", "https://x/notes.txt", 10),
            GitHubAsset("openlauncher-main-debug.apk", "https://x/main.apk", 20)
        )
        assertEquals("openlauncher-main-debug.apk", pickApkAsset(assets, UpdateChannel.STABLE)?.name)
        assertEquals("openlauncher-main-debug.apk", pickApkAsset(assets, UpdateChannel.DEV)?.name)
        assertNull(pickApkAsset(assets.take(1), UpdateChannel.DEV))
        assertNull(buildRemoteUpdate(GitHubRelease(tagName = "t", assets = emptyList()), UpdateChannel.DEV))
    }

    // ── decision matrix ───────────────────────────────────────────────────────
    private val local = LocalBuild(6, "0.0.5", "d4551e4")

    private fun remoteAt(code: Int?, commit: String?) = RemoteUpdate(
        code, null, commit, "https://x/app.apk", "app.apk", 0, "", ""
    )

    @Test fun `newer versionCode wins`() {
        assertEquals(UpdateDecision.Available(UpdateReason.NEWER_VERSION), decideUpdate(local, remoteAt(7, "abc1234")))
    }

    @Test fun `older versionCode is up to date`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(local, remoteAt(5, "abc1234")))
    }

    @Test fun `same code matching sha prefix is up to date`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(local, remoteAt(6, "d4551e4deadbeef")))
        assertEquals(UpdateDecision.UpToDate, decideUpdate(local, remoteAt(6, "d4551e4")))
    }

    @Test fun `same code different sha offers same-version-new-build`() {
        assertEquals(UpdateDecision.Available(UpdateReason.SAME_VERSION_NEW_BUILD), decideUpdate(local, remoteAt(6, "ffee123")))
    }

    @Test fun `same code no remote commit but known local sha stays quiet`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(local, remoteAt(6, null)))
    }

    @Test fun `no version marker but different commit offers unknown-version update`() {
        assertEquals(UpdateDecision.Available(UpdateReason.UNKNOWN_VERSION), decideUpdate(local, remoteAt(null, "ffee123")))
    }

    @Test fun `no marker and no commit anywhere stays quiet`() {
        assertEquals(UpdateDecision.UpToDate, decideUpdate(LocalBuild(6, "0.0.5", "unknown"), remoteAt(null, null)))
    }

    @Test fun `shaMatches handles short vs full and rejects unknown`() {
        assertTrue(shaMatches("d4551e4", "d4551e4abcdef1234567890abcdef123456789"))
        assertTrue(!shaMatches("unknown", "d4551e4"))
        assertTrue(!shaMatches(null, "d4551e4"))
        assertTrue(!shaMatches("d4551e4", "ffee123"))
    }
}
