package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {

    @Test
    fun clip_keepsShortText() {
        assertEquals("喝水", Text.clip("喝水", 40))
    }

    @Test
    fun clip_limitsToExactLength() {
        val s = "a".repeat(100)
        val out = Text.clip(s, 40)
        assertEquals(40, out.length)
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun titleAndBody_useWatchLimits() {
        val long = "长".repeat(500)
        assertEquals(Text.TITLE_MAX, Text.title(long).length)
        assertEquals(Text.BODY_MAX, Text.body(long).length)
    }

    @Test
    fun plain_stripsHtmlAndEntities() {
        assertEquals("a & b < c > d", Text.plain("<p>a &amp; b &lt; c &gt; d</p>"))
    }

    @Test
    fun plain_collapsesWhitespace() {
        assertEquals("hello world", Text.plain("hello\n\n   world\t"))
    }

    @Test
    fun key_removesWhitespace() {
        assertEquals("a-b-c", Text.key("a b  c"))
    }
}
