package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ServicePolicyTest {

    private val now = 10_000_000_000L

    @Test
    fun tickInterval_fastWhenDueWithinTwoMinutes() {
        assertEquals(ServicePolicy.FAST_TICK_MS, ServicePolicy.tickIntervalMs(now, now + 90_000))
    }

    @Test
    fun tickInterval_slowWhenNextDueFar() {
        assertEquals(ServicePolicy.SLOW_TICK_MS, ServicePolicy.tickIntervalMs(now, now + 10 * 60_000))
    }

    @Test
    fun tickInterval_slowWhenNoDue() {
        assertEquals(ServicePolicy.SLOW_TICK_MS, ServicePolicy.tickIntervalMs(now, null))
    }

    @Test
    fun tickInterval_fastWhenOverdue() {
        assertEquals(ServicePolicy.FAST_TICK_MS, ServicePolicy.tickIntervalMs(now, now - 1))
    }

    @Test
    fun materialize_whenNeverRun() {
        assertEquals(true, ServicePolicy.shouldMaterialize(now, 0L))
    }

    @Test
    fun materialize_skipsWithinInterval() {
        assertEquals(false, ServicePolicy.shouldMaterialize(now, now - 59 * 60_000))
    }

    @Test
    fun materialize_runsAtInterval() {
        assertEquals(true, ServicePolicy.shouldMaterialize(now, now - 60 * 60_000))
    }

    @Test
    fun materialize_afterClockRollback() {
        assertEquals(true, ServicePolicy.shouldMaterialize(now, now + 5 * 60_000))
    }

    @Test
    fun rearm_falseWhenBothNull() {
        assertEquals(false, ServicePolicy.shouldRearm(null, null))
    }

    @Test
    fun rearm_falseWhenTargetUnchanged() {
        assertEquals(false, ServicePolicy.shouldRearm(1234L, 1234L))
    }

    @Test
    fun rearm_trueWhenTargetChanged() {
        assertEquals(true, ServicePolicy.shouldRearm(1234L, 5678L))
    }

    @Test
    fun rearm_trueWhenTargetAppears() {
        assertEquals(true, ServicePolicy.shouldRearm(null, 5678L))
    }

    @Test
    fun rearm_trueWhenTargetDisappears() {
        assertEquals(true, ServicePolicy.shouldRearm(5678L, null))
    }

    @Test
    fun notify_falseWhenTextUnchanged() {
        assertEquals(false, ServicePolicy.shouldUpdateNotification("运行中 · 今日 1/3", "运行中 · 今日 1/3"))
    }

    @Test
    fun notify_trueWhenTextChanged() {
        assertEquals(true, ServicePolicy.shouldUpdateNotification("运行中 · 今日 1/3", "运行中 · 今日 2/3"))
    }

    @Test
    fun notify_trueWhenFirstTime() {
        assertEquals(true, ServicePolicy.shouldUpdateNotification(null, "中枢已启动"))
    }

    @Test
    fun tickInterval_boundaryAtTwoMinutes() {
        assertEquals(ServicePolicy.FAST_TICK_MS, ServicePolicy.tickIntervalMs(now, now + 120_000))
        assertEquals(ServicePolicy.SLOW_TICK_MS, ServicePolicy.tickIntervalMs(now, now + 120_001))
    }
}
