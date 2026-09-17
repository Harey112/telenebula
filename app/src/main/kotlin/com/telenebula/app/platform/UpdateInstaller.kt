package com.telenebula.app.platform

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface DownloadStep {
    class Progress(val fraction: Float) : DownloadStep
    class Done(val file: File) : DownloadStep
}

/** Fetches a release APK into the cache and hands it to the system package installer. */
class UpdateInstaller(context: Context, private val openWith: OpenWith, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val app = context.applicationContext
    private val dir = File(app.cacheDir, "updates")

    /** Progress in whole percent, then the finished file; throws with a readable message. */
    fun download(url: String, name: String, expectedBytes: Long): Flow<DownloadStep> = flow {
        dir.mkdirs()
        val target = File(dir, name.substringAfterLast('/'))
        val partial = File(dir, "${target.name}.part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("The download answered $status")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
            var read = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) {
                            val percent = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                emit(DownloadStep.Progress(percent / 100f))
                            }
                        }
                    }
                }
            }
            if (total > 0 && read < total) throw IOException("The download stopped early")
            if (target.exists() && !target.delete()) throw IOException("Couldn't replace the previous download")
            if (!partial.renameTo(target)) throw IOException("Couldn't finish writing the download")
            emit(DownloadStep.Done(target))
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }.flowOn(io)

    /** The system installer takes over from here; the user confirms on its own screen. */
    fun install(file: File) {
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(openWith.contentUri(file), APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
        const val BUFFER_BYTES = 64 * 1024
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}
