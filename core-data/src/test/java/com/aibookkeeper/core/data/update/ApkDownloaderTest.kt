package com.aibookkeeper.core.data.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class ApkDownloaderTest {
    @TempDir lateinit var directory: Path
    private val bytes = apkBytes()
    private val githubApk =
        "$GITHUB_REPOSITORY_URL/releases/download/v1.0.8/ai-bookkeeper-latest.apk"
    private val mediaType = "application/vnd.android.package-archive".toMediaType()

    private fun hash(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun apkBytes(payloadSize: Int = 20): ByteArray {
        val output = ByteArrayOutputStream()
        val content = ByteArray(payloadSize) { 42 }
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("AndroidManifest.xml").apply {
                method = ZipEntry.STORED
                size = content.size.toLong()
                crc = CRC32().apply { update(content) }.value
            })
            archive.write(content)
            archive.closeEntry()
        }
        return output.toByteArray()
    }

    private fun info(
        data: ByteArray = bytes,
        size: Long? = data.size.toLong(),
        digest: String? = hash(data),
        fallback: Boolean = false
    ) = ReleaseInfo(
        "v1.0.8", "1.0.8", "$GITHUB_REPOSITORY_URL/releases/tag/v1.0.8",
        "$OFFICIAL_UPDATE_BASE/v1.0.8/apk", "", "",
        size, digest, "官方源", githubApk.takeIf { fallback }
    )

    private fun body(
        data: ByteArray = bytes,
        declaredLength: Long = data.size.toLong()
    ): ResponseBody = object : ResponseBody() {
        private val input = Buffer().write(data)
        override fun contentType(): MediaType = mediaType
        override fun contentLength(): Long = declaredLength
        override fun source(): BufferedSource = input
    }

    @Test
    fun `progress shows received bytes and verified completion creates one APK`() = runTest {
        val large = apkBytes(150_000)
        var clock = 0L
        val downloader = ApkDownloader(
            updateCalls { request -> updateResponse(request, body(large)) },
            StandardTestDispatcher(testScheduler),
            clockMillis = { clock += 201; clock }
        )

        val events = downloader.download(info(large), directory.toFile()).toList()
        val progress = events.filterIsInstance<ApkDownloadEvent.Progress>().map { it.value }
        val complete = events.last() as ApkDownloadEvent.Complete

        assertTrue(progress.any { it.downloadedBytes > 0 && it.downloadedBytes < large.size })
        assertTrue(progress.all { it.totalBytes == large.size.toLong() })
        assertEquals(progress.map { it.downloadedBytes }.sorted(), progress.map { it.downloadedBytes })
        assertEquals(100, complete.progress.percent)
        assertEquals(ApkDownloadPhase.COMPLETE, complete.progress.phase)
        assertArrayEquals(large, complete.file.readBytes())
        assertEquals(listOf(complete.file), directory.toFile().listFiles().orEmpty().toList())
    }

    @Test
    fun `unknown total keeps byte progress indeterminate until completion`() = runTest {
        val downloader = ApkDownloader(
            updateCalls { request -> updateResponse(request, body(declaredLength = -1)) },
            StandardTestDispatcher(testScheduler)
        )

        val events = downloader.download(info(size = null), directory.toFile()).toList()
        val progress = events.filterIsInstance<ApkDownloadEvent.Progress>().map { it.value }

        assertTrue(progress.all { it.totalBytes == null && it.fraction == null })
        assertEquals(100, (events.last() as ApkDownloadEvent.Complete).progress.percent)
    }

    @Test
    fun `release size supplies progress when the response length is unknown`() = runTest {
        val downloader = ApkDownloader(
            updateCalls { request -> updateResponse(request, body(declaredLength = -1)) },
            StandardTestDispatcher(testScheduler)
        )
        val events = downloader.download(info(), directory.toFile()).toList()
        assertTrue(events.filterIsInstance<ApkDownloadEvent.Progress>().all {
            it.value.totalBytes == bytes.size.toLong()
        })
    }

    @Test
    fun `corrupt primary download is discarded and GitHub fallback is visible`() = runTest {
        val requests = mutableListOf<String>()
        val corrupt = bytes.copyOf().apply { this[50] = (this[50].toInt() xor 1).toByte() }
        val downloader = ApkDownloader(
            updateCalls { request ->
                requests += request.url.toString()
                updateResponse(request, body(if (requests.size == 1) corrupt else bytes))
            },
            StandardTestDispatcher(testScheduler)
        )

        val events = downloader.download(info(fallback = true), directory.toFile()).toList()
        val complete = events.last() as ApkDownloadEvent.Complete

        assertEquals(listOf("$OFFICIAL_UPDATE_BASE/v1.0.8/apk", githubApk), requests)
        assertEquals("GitHub", complete.progress.sourceName)
        assertTrue(events.filterIsInstance<ApkDownloadEvent.Progress>().any {
            it.value.sourceName == "GitHub" && !it.value.notice.isNullOrBlank()
        })
        assertArrayEquals(bytes, complete.file.readBytes())
        assertEquals(1, directory.toFile().listFiles().orEmpty().size)
    }

    @Test
    fun `HTTP failure tries the official fallback without publishing the error body`() = runTest {
        var requests = 0
        val downloader = ApkDownloader(
            updateCalls { request ->
                requests++
                if (requests == 1) updateResponse(request, "error".toResponseBody(), 503)
                else updateResponse(request, body())
            },
            StandardTestDispatcher(testScheduler)
        )

        val complete = downloader.download(info(fallback = true), directory.toFile())
            .toList().last() as ApkDownloadEvent.Complete
        assertEquals(2, requests)
        assertArrayEquals(bytes, complete.file.readBytes())
    }

    @Test
    fun `truncated overlong empty and non APK bodies never become installable files`() = runTest {
        val invalidBodies = listOf(
            { body(declaredLength = bytes.size + 2L) } to info(size = bytes.size + 2L),
            { body(declaredLength = bytes.size - 1L) } to info(size = bytes.size - 1L),
            { body(byteArrayOf(), -1) } to info(size = null, digest = null),
            { body(bytes.copyOf(64), -1) } to info(size = null, digest = null),
            { body("<html>error</html>".toByteArray(), -1) } to info(size = null, digest = null),
            { "<html>error</html>".toResponseBody("text/html".toMediaType()) } to info(size = null, digest = null)
        )
        for ((createBody, release) in invalidBodies) {
            val downloader = ApkDownloader(
                updateCalls { request -> updateResponse(request, createBody()) },
                StandardTestDispatcher(testScheduler)
            )
            val events = mutableListOf<ApkDownloadEvent>()
            val error = runCatching { downloader.download(release, directory.toFile()).toList(events) }
                .exceptionOrNull()

            assertTrue(error is IOException)
            assertFalse(events.any { it is ApkDownloadEvent.Complete })
            assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `unknown length is still bounded by maximum download size`() = runTest {
        val downloader = ApkDownloader(
            updateCalls { request -> updateResponse(request, body(declaredLength = -1)) },
            StandardTestDispatcher(testScheduler),
            maximumBytes = 8
        )

        val error = runCatching {
            downloader.download(info(size = null), directory.toFile()).toList()
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation interrupts an in flight read and removes partial files without fallback`() = runTest {
        val reading = CompletableDeferred<Unit>()
        val cancelled = CountDownLatch(1)
        var requests = 0
        val input = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.complete(Unit)
                check(cancelled.await(5, TimeUnit.SECONDS)) { "Download did not cancel its network call" }
                throw IOException("Socket cancelled")
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() {}
        }.buffer()
        val blockedBody = object : ResponseBody() {
            override fun contentType(): MediaType = mediaType
            override fun contentLength(): Long = bytes.size.toLong()
            override fun source(): BufferedSource = input
        }
        val calls = updateCalls(onCancel = { cancelled.countDown() }) { request ->
            requests++
            updateResponse(request, blockedBody)
        }
        val job = backgroundScope.launch {
            ApkDownloader(calls).download(info(fallback = true), directory.toFile()).collect()
        }

        reading.await()
        job.cancelAndJoin()

        assertEquals(1, requests)
        assertEquals(0L, cancelled.count)
        assertTrue(directory.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `progress percentage is accurate and handles unavailable totals`() {
        assertEquals(58, ApkDownloadProgress(58, 100, "test").percent)
        assertEquals(100, ApkDownloadProgress(150, 100, "test").percent)
        assertNull(ApkDownloadProgress(10, null, "test").percent)
        assertNull(ApkDownloadProgress(10, 0, "test").fraction)
    }
}
