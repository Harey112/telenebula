package com.telenebula.core

/** Where user-facing engine failures go (the app's error modal). */
fun interface CoreErrorSink {
    fun report(message: String)
}
