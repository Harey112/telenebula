package com.telenebula.core

import kotlinx.serialization.json.Json

/** One configuration for every JSON that hits disk or a database column. */
val CoreJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}
