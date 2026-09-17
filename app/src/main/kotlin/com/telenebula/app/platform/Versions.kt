package com.telenebula.app.platform

/** Numeric dotted-version compare: positive when [a] is newer than [b]. Non-numeric parts count as 0. */
fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.')
    val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i)?.toIntOrNull() ?: 0) - (pb.getOrNull(i)?.toIntOrNull() ?: 0)
        if (d != 0) return d
    }
    return 0
}

/** The message a user can read out of any failure. */
fun Throwable.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
