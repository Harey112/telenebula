package com.telenebula.app

import com.telenebula.app.platform.LinkSpans
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cases the core's own extractor is tested against (`native/core/src/store.rs`), so a link a
 * bubble underlines and a link the Links screen lists can never drift apart, plus the two places
 * the Kotlin side deliberately differs.
 */
class LinkSpansTest {
    private fun urlsIn(body: String) = LinkSpans.urlRangesIn(body).map { body.substring(it.first, it.last + 1) }

    @Test
    fun `finds both schemes and trims trailing punctuation`() {
        assertEquals(
            listOf("https://example.com/a", "http://x.io/b"),
            urlsIn("see https://example.com/a, and http://x.io/b."),
        )
    }

    @Test
    fun `skips a wrapping bracket and keeps the original case`() {
        assertEquals(listOf("https://a.b/c", "HTTPS://D.E"), urlsIn("(https://a.b/c) HTTPS://D.E"))
    }

    @Test
    fun `text without a link yields nothing`() {
        assertEquals(emptyList<String>(), urlsIn("no link here"))
        assertEquals(emptyList<String>(), urlsIn(""))
    }

    @Test
    fun `a bare scheme is not a link`() {
        assertEquals(emptyList<String>(), urlsIn("https:// http://"))
    }

    /** Unlike the core's de-duplicated list, each occurrence needs its own tappable range. */
    @Test
    fun `a repeated url is reported once per occurrence`() {
        assertEquals(listOf("https://a.io", "https://a.io"), urlsIn("https://a.io and https://a.io"))
    }

    @Test
    fun `ranges land on the right offsets`() {
        val body = "see https://example.com/a, ok"
        val range = LinkSpans.urlRangesIn(body).single()
        assertEquals(4, range.first)
        assertEquals("https://example.com/a", body.substring(range.first, range.last + 1))
    }
}
