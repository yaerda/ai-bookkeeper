package com.aibookkeeper.core.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

internal const val MAX_APK_BYTES = 200L * 1024 * 1024
internal const val OFFICIAL_UPDATE_BASE =
    "https://aibookkeeper-sync-prod-yaerda.azurewebsites.net/api/app-updates"
internal const val GITHUB_REPOSITORY_URL = "https://github.com/yaerda/ai-bookkeeper"

data class ApkDownloadSource(val name: String, val url: String)

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val body: String,
    val publishedAt: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val downloadSourceName: String = "GitHub",
    val fallbackDownloadUrl: String? = null
) {
    val downloadSources: List<ApkDownloadSource>
        get() = listOfNotNull(
            ApkDownloadSource(downloadSourceName, downloadUrl),
            fallbackDownloadUrl?.let { ApkDownloadSource("GitHub", it) }
        ).distinctBy { it.url }
}

class UpdateChecker {
    companion object {
        internal const val GITHUB_API_URL =
            "https://api.github.com/repos/yaerda/ai-bookkeeper/releases/latest"
        private val versionPattern = Regex("""\d+(?:\.\d+){0,3}""")
        private val digestPattern = Regex("sha256:([0-9a-fA-F]{64})")
        private val logger = Logger.getLogger(UpdateChecker::class.java.name)
        private val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()

        suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? =
            checkForUpdate(currentVersion, client) { source, error ->
                logger.log(Level.WARNING, "$source update check failed", error)
            }

        internal suspend fun checkForUpdate(
            currentVersion: String,
            calls: Call.Factory,
            reportFailure: (String, IOException) -> Unit
        ): ReleaseInfo? = withContext(Dispatchers.IO) {
            val sources = listOf(
                ApkDownloadSource("官方源", "$OFFICIAL_UPDATE_BASE/latest"),
                ApkDownloadSource("GitHub", GITHUB_API_URL)
            )
            var lastFailure: IOException? = null
            for ((index, source) in sources.withIndex()) {
                ensureActive()
                try {
                    val request = Request.Builder()
                        .url(source.url)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "AI-Bookkeeper-Android")
                        .build()
                    val response = calls.newCall(request).execute().use { result ->
                        if (result.code != 200) {
                            throw IOException("读取更新信息失败（HTTP ${result.code}）")
                        }
                        val body = result.body ?: throw IOException("更新信息为空")
                        val input = body.source()
                        if (input.request(1024 * 1024 + 1L)) throw IOException("更新信息过大")
                        input.readUtf8()
                    }
                    ensureActive()
                    return@withContext parseReleaseResponse(response, currentVersion, index == 0)
                } catch (error: IOException) {
                    ensureActive()
                    lastFailure = error
                    reportFailure(source.name, error)
                }
            }
            throw IOException("暂时无法检查更新，请检查网络后重试", lastFailure)
        }

        internal fun parseReleaseResponse(
            response: String,
            currentVersion: String,
            fromOfficialSource: Boolean
        ): ReleaseInfo? {
            val json = try {
                Json.parseToJsonElement(response) as? JsonObject
                    ?: throw IOException("更新信息格式无效")
            } catch (error: SerializationException) {
                throw IOException("更新信息格式无效", error)
            }
            for (flag in listOf("draft", "prerelease")) {
                val value = json[flag] ?: continue
                val enabled = (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                    ?: throw IOException("更新版本状态无效")
                if (enabled) return null
            }

            val tagName = json.string("tag_name") ?: throw IOException("更新版本号为空")
            val version = tagName.removePrefix("v")
            if (!versionPattern.matches(version) || !version.contains('.')) throw IOException("更新版本号无效")
            val htmlUrl = json.string("html_url") ?: throw IOException("更新页面地址为空")
            if (htmlUrl != "$GITHUB_REPOSITORY_URL/releases/tag/$tagName") {
                throw IOException("更新页面不是官方发布地址")
            }
            val assets = (json["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?: throw IOException("更新包列表为空")
            val githubUrl = selectApkDownloadUrl(assets.map {
                it.string("name").orEmpty() to it.string("browser_download_url").orEmpty()
            })
            val asset = assets.firstOrNull { it.string("browser_download_url") == githubUrl }
                ?: throw IOException("没有可下载的 Android 更新包")
            val name = asset.string("name").orEmpty()
            if (!Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.[aA][pP][kK]""").matches(name) ||
                githubUrl != "$GITHUB_REPOSITORY_URL/releases/download/$tagName/$name"
            ) {
                throw IOException("更新包不是官方发布地址")
            }

            val size = asset["size"]?.let {
                val primitive = it as? JsonPrimitive
                val bytes = primitive?.takeUnless { value -> value.isString }?.longOrNull
                if (bytes == null || bytes <= 0 || bytes > MAX_APK_BYTES) {
                    throw IOException("更新包大小无效")
                }
                bytes
            }
            val digest = asset["digest"]?.takeUnless { it == JsonNull }?.let { value ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IOException("更新包校验值无效")
                digestPattern.matchEntire(text)?.groupValues?.get(1)?.lowercase()
                    ?: throw IOException("更新包校验值无效")
            }
            if (!isNewerVersion(version, currentVersion)) return null

            return ReleaseInfo(
                tagName = tagName,
                version = version,
                htmlUrl = htmlUrl,
                downloadUrl = if (fromOfficialSource) "$OFFICIAL_UPDATE_BASE/$tagName/apk" else githubUrl,
                body = json.string("body").orEmpty(),
                publishedAt = json.string("published_at").orEmpty(),
                sizeBytes = size,
                sha256 = digest,
                downloadSourceName = if (fromOfficialSource) "官方源" else "GitHub",
                fallbackDownloadUrl = githubUrl.takeIf { fromOfficialSource }
            )
        }

        fun isNewerVersion(remote: String, current: String): Boolean {
            if (!versionPattern.matches(remote) || !versionPattern.matches(current)) return false
            val remoteParts = remote.split(".").map { it.toLongOrNull() ?: return false }
            val currentParts = current.split(".").map { it.toLongOrNull() ?: return false }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (index in 0 until maxLen) {
                val remotePart = remoteParts.getOrElse(index) { 0L }
                val currentPart = currentParts.getOrElse(index) { 0L }
                if (remotePart > currentPart) return true
                if (remotePart < currentPart) return false
            }
            return false
        }

        internal fun selectApkDownloadUrl(assets: List<Pair<String, String>>): String =
            assets.firstOrNull { (name, url) ->
                name == "ai-bookkeeper-latest.apk" && url.isNotBlank()
            }?.second ?: assets.firstOrNull { (name, url) ->
                name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()
            }?.second.orEmpty()

        private fun JsonObject.string(name: String): String? =
            (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}

internal fun isTrustedApkUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.query != null || url.fragment != null
    ) return false
    val tag = """v?\d+(?:\.\d+){1,3}"""
    return when (url.host) {
        "github.com" -> Regex(
            """/yaerda/ai-bookkeeper/releases/download/$tag/[A-Za-z0-9][A-Za-z0-9._-]*\.[aA][pP][kK]"""
        ).matches(url.encodedPath)
        "aibookkeeper-sync-prod-yaerda.azurewebsites.net" ->
            Regex("/api/app-updates/$tag/apk").matches(url.encodedPath)
        else -> false
    }
}
