package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdTest {

    @Test
    fun sameTagIsStable() {
        assertEquals(NotificationId.of("wh:feed:qbitai"), NotificationId.of("wh:feed:qbitai"))
    }

    @Test
    fun differentTagsDifferForRealisticInputs() {
        assertNotEquals(NotificationId.of("wh:feed:qbitai"), NotificationId.of("wh:reminder:喝水"))
        assertNotEquals(NotificationId.of("wh:reminder:喝水"), NotificationId.of("wh:reminder:日报"))
    }

    @Test
    fun neverNegative() {
        listOf("wh:feed:a", "wh:reminder:喝水", "", "😀").forEach {
            assertTrue(NotificationId.of(it) >= 0)
        }
    }
}
