package com.aibookkeeper.core.data.update

import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UpdateCheckerTest {
    private val githubApk =
        "$GITHUB_REPOSITORY_URL/releases/download/v1.0.8/ai-bookkeeper-latest.apk"
    private val digest = "a".repeat(64)

    private fun releaseJson(
        tag: String = "v1.0.8",
        url: String = githubApk,
        extraAssetFields: String = """"size": 1024, "digest": "sha256:$digest""""
    ) = """
        {
          "tag_name": "$tag",
          "html_url": "$GITHUB_REPOSITORY_URL/releases/tag/$tag",
          "body": "Update notes",
          "published_at": "2026-09-06T00:00:00Z",
          "assets": [{
            "name": "ai-bookkeeper-latest.apk",
            "browser_download_url": "$url",
            $extraAssetFields
          }]
        }
    """.trimIndent()

    @Test
    fun `first party metadata selects the relay and retains official GitHub fallback`() {
        val info = requireNotNull(UpdateChecker.parseReleaseResponse(releaseJson(), "1.0.7", true))

        assertEquals("$OFFICIAL_UPDATE_BASE/v1.0.8/apk", info.downloadUrl)
        assertEquals(githubApk, info.fallbackDownloadUrl)
        assertEquals(1024L, info.sizeBytes)
        assertEquals(digest, info.sha256)
        assertEquals(listOf("官方源", "GitHub"), info.downloadSources.map { it.name })
    }

    @Test
    fun `GitHub fallback metadata does not retry an unavailable relay`() {
        val info = requireNotNull(UpdateChecker.parseReleaseResponse(releaseJson(), "1.0.7", false))

        assertEquals(githubApk, info.downloadUrl)
        assertNull(info.fallbackDownloadUrl)
        assertEquals(1, info.downloadSources.size)
    }

    @Test
    fun `checking starts with first party and falls back on a network failure`() = runTest {
        val requests = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val calls = updateCalls { request ->
            requests += request.url.toString()
            if (requests.size == 1) throw IOException("Primary unavailable")
            updateResponse(request, releaseJson().toResponseBody())
        }

        val info = requireNotNull(UpdateChecker.checkForUpdate("1.0.7", calls) { name, _ -> failures += name })

        assertEquals(listOf("$OFFICIAL_UPDATE_BASE/latest", UpdateChecker.GITHUB_API_URL), requests)
        assertEquals(listOf("官方源"), failures)
        assertEquals(githubApk, info.downloadUrl)
    }

    @Test
    fun `valid current release does not cause a needless fallback request`() = runTest {
        var requests = 0
        val calls = updateCalls { request ->
            requests++
            updateResponse(request, releaseJson().toResponseBody())
        }

        assertNull(UpdateChecker.checkForUpdate("1.0.8", calls) { _, _ -> error("Unexpected failure") })
        assertEquals(1, requests)
    }

    @Test
    fun `all sources failing is not reported as already up to date`() = runTest {
        val failures = mutableListOf<String>()
        val calls = updateCalls { request -> updateResponse(request, "unavailable".toResponseBody(), 503) }

        val error = runCatching {
            UpdateChecker.checkForUpdate("1.0.7", calls) { name, _ -> failures += name }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(listOf("官方源", "GitHub"), failures)
    }

    @Test
    fun `malformed or untrusted releases cannot become installable update information`() {
        for (invalid in listOf(
            "not-json",
            "[]",
            releaseJson(tag = "v../1.0.8"),
            releaseJson(url = "http://github.com/yaerda/ai-bookkeeper/releases/download/v1.0.8/ai-bookkeeper-latest.apk"),
            releaseJson(url = "https://github.com.evil.test/ai-bookkeeper-latest.apk"),
            releaseJson(url = "$GITHUB_REPOSITORY_URL/releases/download/v1.0.7/ai-bookkeeper-latest.apk"),
            releaseJson(extraAssetFields = """"size": -1"""),
            releaseJson(extraAssetFields = """"size": ${MAX_APK_BYTES + 1}"""),
            releaseJson(extraAssetFields = """"size": "1024""""),
            releaseJson(extraAssetFields = """"digest": "sha256:broken""""),
            releaseJson(extraAssetFields = """"digest": 123""")
        )) {
            assertThrows(IOException::class.java) {
                UpdateChecker.parseReleaseResponse(invalid, "1.0.7", true)
            }
        }
    }

    @Test
    fun `missing size and a legacy null digest remain explicitly unknown`() {
        val info = requireNotNull(UpdateChecker.parseReleaseResponse(
            releaseJson(extraAssetFields = """"digest": null"""), "1.0.7", true
        ))
        assertNull(info.sizeBytes)
        assertNull(info.sha256)
    }

    @Test
    fun `download URLs are restricted to official HTTPS paths`() {
        assertTrue(isTrustedApkUrl(githubApk))
        assertTrue(isTrustedApkUrl("$OFFICIAL_UPDATE_BASE/v1.0.8/apk"))
        for (url in listOf(
            githubApk.replace("https:", "http:"),
            githubApk.replace("github.com", "github.com.evil.test"),
            githubApk.replace("github.com", "user:password@github.com"),
            "$OFFICIAL_UPDATE_BASE/../../admin",
            "$OFFICIAL_UPDATE_BASE/v1.0.8/apk?url=http://localhost",
            "$GITHUB_REPOSITORY_URL/releases/download/v1.0.8/../../../../other/repository.apk",
            "$GITHUB_REPOSITORY_URL/releases/download/v1.0.8/file.html"
        )) {
            assertFalse(isTrustedApkUrl(url), url)
        }
    }

    @Nested
    inner class IsNewerVersion {

        @Test
        fun should_returnTrue_when_remoteVersionIsHigher() {
            assertTrue(UpdateChecker.isNewerVersion("1.0.2", "1.0.1"))
        }

        @Test
        fun should_returnFalse_when_versionsAreEqual() {
            assertFalse(UpdateChecker.isNewerVersion("1.0.1", "1.0.1"))
        }

        @Test
        fun should_returnFalse_when_remoteVersionIsLower() {
            assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"))
        }

        @Test
        fun should_treatMissingSegmentsAsZero() {
            assertTrue(UpdateChecker.isNewerVersion("1.1", "1.0.9"))
            assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.1"))
        }

        @Test
        fun should_rejectMalformedVersionSegments() {
            assertFalse(UpdateChecker.isNewerVersion("1.bad.99", "1.0.7"))
            assertFalse(UpdateChecker.isNewerVersion("99999999999999999999999.0", "1.0.7"))
            assertFalse(UpdateChecker.isNewerVersion("../2.0", "1.0.7"))
        }
    }

    @Test
    fun should_preferStableApkAsset_when_releaseHasMultipleApks() {
        val assets = listOf(
            "ai-bookkeeper-1.0.4-release.apk" to "https://example.test/versioned.apk",
            "ai-bookkeeper-latest.apk" to "https://example.test/latest.apk"
        )

        assertEquals(
            "https://example.test/latest.apk",
            UpdateChecker.selectApkDownloadUrl(assets)
        )
    }
}
