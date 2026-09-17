package com.telenebula.app.platform

/**
 * Finds http(s) URLs in message text so a bubble can make them tappable.
 *
 * Deliberately mirrors the core's own extractor (`extract_urls` in `native/core/src/store.rs`), so
 * a link the bubble underlines is the same link the chat's Links screen lists. Two differences,
 * both intentional:
 *
 *  - it returns a range per occurrence rather than a de-duplicated list of URLs, because every
 *    occurrence has to be tappable on its own;
 *  - it matches the scheme case-insensitively in place instead of lowercasing the word first,
 *    since lowercasing can change a string's length and shift the offsets it is used to index.
 */
object LinkSpans {
    private const val TRAILING = ".,;:!?)”’\"'>]"
    private val MIN_LENGTH = "https://".length

    /** Character ranges of the URLs in [body], in the order they appear. */
    fun urlRangesIn(body: String): List<IntRange> {
        if (body.length <= MIN_LENGTH) return emptyList()
        val out = ArrayList<IntRange>(2)
        var i = 0
        while (i < body.length) {
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) break
            val wordStart = i
            while (i < body.length && !body[i].isWhitespace()) i++
            val word = body.substring(wordStart, i)
            val https = word.indexOf("https://", ignoreCase = true)
            val http = word.indexOf("http://", ignoreCase = true)
            val start = when {
                https >= 0 && http >= 0 -> minOf(https, http)
                https >= 0 -> https
                http >= 0 -> http
                else -> continue
            }
            var end = word.length
            while (end > start && TRAILING.indexOf(word[end - 1]) >= 0) end--
            if (end - start > MIN_LENGTH) out.add(wordStart + start until wordStart + end)
        }
        return out
    }
}
