package dev.watchhub.app.core

import dev.watchhub.app.core.model.MetricType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 范围边界来自 SPEC 2026-10-25-watchhub-2.5.0 §3 A7 的手工算例。 */
class MetricTypeTest {

    @Test
    fun heartRateRange() {
        assertFalse(MetricType.HEART_RATE.isValid(29.0))
        assertTrue(MetricType.HEART_RATE.isValid(30.0))
        assertTrue(MetricType.HEART_RATE.isValid(220.0))
        assertFalse(MetricType.HEART_RATE.isValid(221.0))
    }

    @Test
    fun spo2Range() {
        assertFalse(MetricType.SPO2.isValid(69.0))
        assertTrue(MetricType.SPO2.isValid(70.0))
        assertTrue(MetricType.SPO2.isValid(100.0))
        assertFalse(MetricType.SPO2.isValid(101.0))
    }

    @Test
    fun bloodPressureRanges() {
        assertFalse(MetricType.BP_SYS.isValid(79.0))
        assertTrue(MetricType.BP_SYS.isValid(80.0))
        assertTrue(MetricType.BP_SYS.isValid(200.0))
        assertFalse(MetricType.BP_SYS.isValid(201.0))
        assertFalse(MetricType.BP_DIA.isValid(49.0))
        assertTrue(MetricType.BP_DIA.isValid(50.0))
        assertTrue(MetricType.BP_DIA.isValid(130.0))
        assertFalse(MetricType.BP_DIA.isValid(131.0))
    }

    @Test
    fun stressRange() {
        assertFalse(MetricType.STRESS.isValid(-1.0))
        assertTrue(MetricType.STRESS.isValid(0.0))
        assertTrue(MetricType.STRESS.isValid(100.0))
        assertFalse(MetricType.STRESS.isValid(101.0))
    }

    @Test
    fun parseToleratesCaseAndJunk() {
        assertEquals(MetricType.HEART_RATE, MetricType.parse("heart_rate"))
        assertEquals(MetricType.SPO2, MetricType.parse("SPO2"))
        assertNull(MetricType.parse("pulse"))
        assertNull(MetricType.parse(null))
    }
}
