package com.telenebula.core.engine

internal fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
