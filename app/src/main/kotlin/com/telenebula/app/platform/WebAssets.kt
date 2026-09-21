package com.telenebula.app.platform

import android.content.Context
import com.telenebula.dex.DexAsset
import com.telenebula.dex.DexAssets
import java.io.ByteArrayInputStream
import java.io.IOException

/** The web frontend bundled under `assets/dex/`; a handful of small files, read once and kept. */
class WebAssets(context: Context) : DexAssets {
    private val manager = context.applicationContext.assets
    private val cache = HashMap<String, ByteArray?>()

    override fun open(path: String): DexAsset? {
        val name = path.trimStart('/').ifEmpty { "index.html" }
        if (name !in FILES) return null
        val bytes = synchronized(cache) {
            cache.getOrPut(name) {
                try {
                    manager.open("$DIR/$name").use { it.readBytes() }
                } catch (e: IOException) {
                    null
                }
            }
        } ?: return null
        return DexAsset(mimeOf(name), bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "svg" -> "image/svg+xml"
        "map" -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        const val DIR = "dex"
        val FILES: Set<String> = setOf("index.html", "web.js", "app.css", "favicon.svg")
    }
}
