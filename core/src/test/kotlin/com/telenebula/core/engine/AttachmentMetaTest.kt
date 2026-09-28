package com.telenebula.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentMetaTest {
    @Test
    fun `a peer's name keeps what a person reads and loses what could split a header or spoof an extension`() {
        assertEquals("résumé final.pdf", AttachmentMeta.name("résumé final.pdf"))
        assertEquals("a__Set-Cookie: x.txt", AttachmentMeta.name("a\r\nSet-Cookie: x.txt"))
        assertEquals("invoice_fdp.exe", AttachmentMeta.name("invoice‮fdp.exe"))
        assertEquals("_etc_passwd", AttachmentMeta.name("/etc/passwd"))
        assertEquals("a_b", AttachmentMeta.name("a\\b"))
        assertEquals("x_y", AttachmentMeta.name("x\u0000y"))
        assertEquals(AttachmentMeta.DEFAULT_NAME, AttachmentMeta.name(" .. "))
        assertEquals(AttachmentMeta.DEFAULT_NAME, AttachmentMeta.name(""))
        assertEquals(Limits.MAX_ATTACHMENT_NAME_CHARS, AttachmentMeta.name("n".repeat(10_000)).length)
    }

    @Test
    fun `a peer's type is a bare type or nothing`() {
        assertEquals("image/jpeg", AttachmentMeta.mime("IMAGE/JPEG"))
        assertEquals("audio/webm", AttachmentMeta.mime("audio/webm; codecs=opus"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", AttachmentMeta.mime("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        for (bad in listOf("text/html\r\nSet-Cookie: x=1", "image/png\u0000", "nonsense", "", "a/b/c", "/png", "image/", "image/" + "x".repeat(200))) {
            assertEquals("'$bad'", AttachmentMeta.DEFAULT_MIME, AttachmentMeta.mime(bad))
        }
        assertTrue(AttachmentMeta.mime("x".repeat(60) + "/" + "y".repeat(60)).length <= Limits.MAX_ATTACHMENT_MIME_CHARS)
    }
}
