package com.telenebula.app.platform

import android.content.Context
import com.telenebula.dex.DexAsset
import com.telenebula.dex.DexAssets
import java.io.ByteArrayInputStream
import java.io.IOException

class WebAssets(context: Context) : DexAssets {
    private val manager = context.applicationContext.assets
    private val cache = HashMap<String, ByteArray?>()
    private val files: Set<String> = manager.open("$DIR/dex-assets.txt").use { stream ->
        stream.bufferedReader().readLines().filter { it.isNotBlank() }.toSet()
    }.also { listed ->
        require("index.html" in listed && "emoji_catalog.json" in listed) { "Dex asset manifest is incomplete" }
        require(listed.all { name -> name.split('/').all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it != ".." } }) {
            "Dex asset manifest has an invalid path"
        }
    }

    override fun open(path: String): DexAsset? {
        val name = path.trimStart('/').ifEmpty { "index.html" }
        if (name !in files) return null
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
        "map", "json" -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        const val DIR = "dex"
    }
}
