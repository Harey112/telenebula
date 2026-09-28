package com.telenebula.dex.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTypesTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    private val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 0, 0, 0)
    private val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypmp42".toByteArray()
    private val ogg = "OggS".toByteArray() + ByteArray(8)
    private val html = "<!doctype html><script>".toByteArray()

    @Test
    fun `passive media whose bytes agree is inline under its canonical type`() {
        assertEquals("image/png", MediaTypes.inlineType("image/png", png))
        assertEquals("image/jpeg", MediaTypes.inlineType("IMAGE/JPEG; charset=x", jpeg))
        assertEquals("audio/webm", MediaTypes.inlineType("audio/webm;codecs=opus", webm))
        assertEquals("video/mp4", MediaTypes.inlineType("video/mp4", mp4))
        assertEquals("audio/mp4", MediaTypes.inlineType("audio/mp4", mp4))
        assertEquals("audio/ogg", MediaTypes.inlineType("audio/ogg", ogg))
    }

    @Test
    fun `active or unverified content is never inline`() {
        assertNull(MediaTypes.inlineType("text/html", html))
        assertNull(MediaTypes.inlineType("image/svg+xml", "<svg onload=alert(1)>".toByteArray()))
        assertNull(MediaTypes.inlineType("application/javascript", html))
        assertNull(MediaTypes.inlineType("text/xml", html))
        assertNull(MediaTypes.inlineType("application/pdf", "%PDF-1.7".toByteArray()))
        assertNull(MediaTypes.inlineType("text/plain", "hello".toByteArray()))
        // a peer that calls a page a picture does not get it rendered
        assertNull(MediaTypes.inlineType("image/png", html))
        assertNull(MediaTypes.inlineType("video/mp4", html))
        assertNull(MediaTypes.inlineType("image/png", ByteArray(0)))
        assertNull(MediaTypes.inlineType("image/png\r\nSet-Cookie: x=1", png))
    }

    @Test
    fun `only a well-formed type survives normalising`() {
        assertEquals("audio/webm", MediaTypes.normalise(" Audio/WebM ; codecs=opus"))
        assertNull(MediaTypes.normalise("text/html\r\nX: y"))
        assertNull(MediaTypes.normalise("nonsense"))
        assertNull(MediaTypes.normalise("a/b/c"))
        assertNull(MediaTypes.normalise(""))
    }
}
