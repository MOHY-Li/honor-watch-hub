package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationHealthPolicyTest {

    @Test
    fun classify_off() {
        assertEquals(NotificationHealthPolicy.ChannelState.OFF, NotificationHealthPolicy.classify(0))
    }

    @Test
    fun classify_low() {
        assertEquals(NotificationHealthPolicy.ChannelState.LOW, NotificationHealthPolicy.classify(1))
        assertEquals(NotificationHealthPolicy.ChannelState.LOW, NotificationHealthPolicy.classify(2))
    }

    @Test
    fun classify_default_isAcceptable() {
        assertEquals(NotificationHealthPolicy.ChannelState.DEFAULT, NotificationHealthPolicy.classify(3))
        assertTrue(NotificationHealthPolicy.isAcceptable(NotificationHealthPolicy.ChannelState.DEFAULT))
    }

    @Test
    fun classify_high() {
        assertEquals(NotificationHealthPolicy.ChannelState.HIGH, NotificationHealthPolicy.classify(4))
        assertEquals(NotificationHealthPolicy.ChannelState.HIGH, NotificationHealthPolicy.classify(5))
        assertEquals(NotificationHealthPolicy.ChannelState.HIGH, NotificationHealthPolicy.classify(6))
    }

    @Test
    fun acceptable_onlyDefaultAndHigh() {
        assertFalse(NotificationHealthPolicy.isAcceptable(NotificationHealthPolicy.ChannelState.OFF))
        assertFalse(NotificationHealthPolicy.isAcceptable(NotificationHealthPolicy.ChannelState.LOW))
        assertTrue(NotificationHealthPolicy.isAcceptable(NotificationHealthPolicy.ChannelState.DEFAULT))
        assertTrue(NotificationHealthPolicy.isAcceptable(NotificationHealthPolicy.ChannelState.HIGH))
    }
}
