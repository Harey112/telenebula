package com.telenebula.app.platform

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Links {
    const val DOCS_URL = "https://github.com/Harey112/telenebula#readme"
    const val ISSUES_URL = "https://github.com/Harey112/telenebula/issues"
    const val RELEASES_URL = "https://github.com/Harey112/telenebula/releases"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/Harey112/telenebula/releases/latest"
}

/** The newest published release and the APK asset built for this device, when the release carries one. */
class Release(val version: String, val apkUrl: String?, val apkName: String?, val apkBytes: Long)

/** One GET per check; a full HTTP client would be dead weight. */
class UpdateChecker(private val json: Json, private val io: CoroutineDispatcher = Dispatchers.IO) {
    suspend fun latestVersion(): String = latestRelease(emptyList()).version

    /** Throws with a readable message. [abis] in preference order picks the asset; "universal" is the fallback. */
    suspend fun latestRelease(abis: List<String>): Release = withContext(io) {
        val connection = (URL(Links.LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered $status")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val release = json.parseToJsonElement(body).jsonObject
            val tag = release["tag_name"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: throw IOException("No release tag in the response")
            val assets = release["assets"]?.jsonArray.orEmpty().mapNotNull { element ->
                val asset = element.jsonObject
                val name = asset["name"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
                val size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                Triple(name, url, size)
            }.filter { it.first.endsWith(".apk", ignoreCase = true) }
            val apk = abis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.first.contains(abi, ignoreCase = true) } }
                ?: assets.firstOrNull { it.first.contains("universal", ignoreCase = true) }
                ?: assets.firstOrNull()
            Release(
                version = tag.removePrefix("v").removePrefix("V"),
                apkUrl = apk?.second,
                apkName = apk?.first,
                apkBytes = apk?.third ?: 0L,
            )
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
