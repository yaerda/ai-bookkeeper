package com.aibookkeeper.core.data.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

enum class ApkDownloadPhase { CONNECTING, DOWNLOADING, VERIFYING, COMPLETE }

data class ApkDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val sourceName: String,
    val phase: ApkDownloadPhase = ApkDownloadPhase.DOWNLOADING,
    val notice: String? = null
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f)
        }

    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (downloadedBytes.toDouble() * 100 / it).toInt().coerceIn(0, 100)
        }
}

sealed interface ApkDownloadEvent {
    data class Progress(val value: ApkDownloadProgress) : ApkDownloadEvent
    data class Complete(val file: File, val progress: ApkDownloadProgress) : ApkDownloadEvent
}

private class ApkSourceException(message: String, cause: IOException? = null) :
    IOException(message, cause)

class ApkDownloader internal constructor(
    private val calls: Call.Factory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maximumBytes: Long = MAX_APK_BYTES
) {
    constructor() : this(defaultClient)

    fun download(info: ReleaseInfo, cacheDirectory: File): Flow<ApkDownloadEvent> = flow {
        var failure: IOException? = null
        var previousSource: String? = null
        for (source in info.downloadSources) {
            currentCoroutineContext().ensureActive()
            try {
                emitAll(downloadFromSource(
                    info,
                    source,
                    cacheDirectory,
                    previousSource?.let { "$it 下载失败，已切换备用源" }
                ))
                return@flow
            } catch (error: ApkSourceException) {
                currentCoroutineContext().ensureActive()
                failure = error
                previousSource = source.name
            }
        }
        throw IOException("更新包下载失败：${failure?.message ?: "没有可用下载源"}", failure)
    }

    private fun downloadFromSource(
        info: ReleaseInfo,
        source: ApkDownloadSource,
        cacheDirectory: File,
        notice: String?
    ): Flow<ApkDownloadEvent> = callbackFlow {
        if (!isTrustedApkUrl(source.url)) throw ApkSourceException("更新包来源无效")
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", "AI-Bookkeeper-Android")
            .header("Accept-Encoding", "identity")
            .build()
        val call = calls.newCall(request)
        val worker = launch(ioDispatcher) {
            try {
                if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) {
                    throw IOException("无法创建更新缓存目录")
                }
                val temporary = File.createTempFile("ai-bookkeeper-update-", ".download", cacheDirectory)
                try {
                    send(ApkDownloadEvent.Progress(ApkDownloadProgress(
                        0, info.sizeBytes, source.name, ApkDownloadPhase.CONNECTING, notice
                    )))
                    val response = try {
                        call.execute()
                    } catch (error: IOException) {
                        throw ApkSourceException("连接${source.name}失败", error)
                    }
                    response.use {
                        if (response.code != 200) {
                            throw ApkSourceException("HTTP ${response.code}")
                        }
                        val body = response.body ?: throw ApkSourceException("更新包为空")
                        val mediaType = body.contentType()?.let { "${it.type}/${it.subtype}" }
                        if (mediaType != null && mediaType !in apkMediaTypes) {
                            throw ApkSourceException("下载源返回了网页而不是更新包")
                        }
                        val declaredSize = body.contentLength().takeIf { it >= 0 }
                        if (declaredSize != null && info.sizeBytes != null && declaredSize != info.sizeBytes) {
                            throw ApkSourceException("更新包大小与发布信息不一致")
                        }
                        val total = declaredSize ?: info.sizeBytes
                        if (total != null && (total <= 0 || total > maximumBytes)) {
                            throw ApkSourceException("更新包大小无效")
                        }
                        send(ApkDownloadEvent.Progress(ApkDownloadProgress(
                            0, total, source.name, notice = notice
                        )))
                        val digest = MessageDigest.getInstance("SHA-256")
                        var downloaded = 0L
                        var lastProgress = clockMillis()
                        body.byteStream().use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    ensureActive()
                                    val count = try {
                                        input.read(buffer)
                                    } catch (error: IOException) {
                                        throw ApkSourceException("读取${source.name}中断", error)
                                    }
                                    if (count < 0) break
                                    if (count == 0) continue
                                    if (count > maximumBytes - downloaded) {
                                        throw ApkSourceException("更新包超过大小限制")
                                    }
                                    if (total != null && count > total - downloaded) {
                                        throw ApkSourceException("更新包超出发布大小")
                                    }
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    downloaded += count
                                    val now = clockMillis()
                                    if (now - lastProgress >= 200 || downloaded == total) {
                                        send(ApkDownloadEvent.Progress(ApkDownloadProgress(
                                            downloaded, total, source.name, notice = notice
                                        )))
                                        lastProgress = now
                                    }
                                }
                            }
                        }
                        ensureActive()
                        send(ApkDownloadEvent.Progress(ApkDownloadProgress(
                            downloaded, total, source.name, ApkDownloadPhase.VERIFYING, notice
                        )))
                        if (downloaded < 4 || (total != null && downloaded != total)) {
                            throw ApkSourceException("更新包不完整，请重试")
                        }
                        try {
                            ZipFile(temporary).use { archive ->
                                if (archive.getEntry("AndroidManifest.xml") == null) {
                                    throw ApkSourceException("下载内容不是 Android 安装包")
                                }
                            }
                        } catch (error: ZipException) {
                            throw ApkSourceException("更新包压缩文件不完整", error)
                        }
                        val actualDigest = digest.digest().joinToString("") { "%02x".format(it) }
                        if (info.sha256 != null && !actualDigest.equals(info.sha256, ignoreCase = true)) {
                            throw ApkSourceException("更新包 SHA-256 校验失败")
                        }
                        ensureActive()
                        val target = File(cacheDirectory, "${temporary.nameWithoutExtension}.apk")
                        if (!temporary.renameTo(target)) throw IOException("无法保存更新包")
                        send(ApkDownloadEvent.Complete(
                            target,
                            ApkDownloadProgress(downloaded, downloaded, source.name, ApkDownloadPhase.COMPLETE, notice)
                        ))
                    }
                } finally {
                    Files.deleteIfExists(temporary.toPath())
                }
                close()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                ensureActive()
                close(error)
            }
        }
        awaitClose {
            call.cancel()
            worker.cancel()
        }
    }

    private companion object {
        val apkMediaTypes = setOf(
            "application/vnd.android.package-archive",
            "application/octet-stream",
            "application/zip"
        )
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }
}
