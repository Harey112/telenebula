package com.telenebula.dex

import java.io.InputStream

class DexAsset(val mime: String, val length: Long, val open: () -> InputStream)

/** The web bundle the app ships; [open] sees only normalised relative paths with no `..` segment. */
interface DexAssets {
    fun open(path: String): DexAsset?
}
