package dev.watchhub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreModelsTest {

    @Test
    fun tags_roundTrip() {
        val tags = listOf("anime", "番剧", "high")
        val json = tagsToJson(tags)
        assertEquals(tags, tagsFromJson(json))
    }

    @Test
    fun tags_invalidJsonGivesEmpty() {
        assertTrue(tagsFromJson("not json").isEmpty())
        assertTrue(tagsFromJson(null).isEmpty())
        assertTrue(tagsFromJson("").isEmpty())
    }

    @Test
    fun placeholders_matchCount() {
        assertEquals("?,?", placeholders(2))
        assertEquals("?", placeholders(1))
    }

    @Test
    fun sourceState_healthFlag() {
        assertTrue(SourceState("a", lastOk = 1, lastError = null).healthy)
        assertEquals(false, SourceState("a", lastOk = 1, lastError = "boom").healthy)
        assertEquals(false, SourceState("a").healthy)
    }
}
