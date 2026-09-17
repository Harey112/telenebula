package com.telenebula.core.model

import kotlinx.serialization.Serializable

/** One group of the emoji catalog asset. */
@Serializable
data class EmojiGroup(val title: String, val emojis: List<String>)
