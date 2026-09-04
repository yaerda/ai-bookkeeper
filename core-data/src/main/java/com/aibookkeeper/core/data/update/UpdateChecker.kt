package com.aibookkeeper.core.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val body: String,
    val publishedAt: String
)

class UpdateChecker {
    companion object {
        private const val API_URL = "https://api.github.com/repos/yaerda/ai-bookkeeper/releases/latest"

        suspend fun checkForUpdate(currentVersion: String): ReleaseInfo? = withContext(Dispatchers.IO) {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "AI-Bookkeeper-Android")
                connectTimeout = 5000
                readTimeout = 5000
            }

            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                val tagName = json.optString("tag_name", "")
                val version = tagName.removePrefix("v")
                val htmlUrl = json.optString("html_url", "")
                val assets = json.optJSONArray("assets")
                val apkAssets = buildList {
                    if (assets != null) {
                        for (index in 0 until assets.length()) {
                            val asset = assets.optJSONObject(index) ?: continue
                            add(asset.optString("name") to asset.optString("browser_download_url"))
                        }
                    }
                }
                val downloadUrl = selectApkDownloadUrl(apkAssets)
                val body = json.optString("body", "")
                val publishedAt = json.optString("published_at", "")

                if (version.isBlank() || htmlUrl.isBlank() || downloadUrl.isBlank()) {
                    return@withContext null
                }

                if (isNewerVersion(version, currentVersion)) {
                    ReleaseInfo(tagName, version, htmlUrl, downloadUrl, body, publishedAt)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

        fun isNewerVersion(remote: String, current: String): Boolean {
            val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (index in 0 until maxLen) {
                val remotePart = remoteParts.getOrElse(index) { 0 }
                val currentPart = currentParts.getOrElse(index) { 0 }
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
    }
}
