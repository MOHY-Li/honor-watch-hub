package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TtlCacheTest {

    private fun counter(): Pair<() -> Int, () -> Int> {
        var calls = 0
        return { calls++; 7 } to { calls }
    }

    @Test
    fun get_computesFirstTime() {
        val (compute, calls) = counter()
        val cache = TtlCache<Int>(ttlMs = 1_000)
        assertEquals(7, cache.get(100, compute))
        assertEquals(1, calls())
    }

    @Test
    fun get_reusesWithinTtl() {
        val (compute, calls) = counter()
        val cache = TtlCache<Int>(ttlMs = 1_000)
        cache.get(100, compute)
        assertEquals(7, cache.get(999, compute))
        assertEquals(1, calls())
    }

    @Test
    fun get_recomputesAtTtlBoundary() {
        val (compute, calls) = counter()
        val cache = TtlCache<Int>(ttlMs = 1_000)
        cache.get(100, compute)
        cache.get(1_100, compute)
        assertEquals(2, calls())
    }

    @Test
    fun get_recomputesAfterClockRollback() {
        val (compute, calls) = counter()
        val cache = TtlCache<Int>(ttlMs = 1_000)
        cache.get(1_000, compute)
        cache.get(200, compute)
        assertEquals(2, calls())
    }

    @Test
    fun invalidate_forcesRecompute() {
        val (compute, calls) = counter()
        val cache = TtlCache<Int>(ttlMs = 60_000)
        cache.get(100, compute)
        cache.invalidate()
        cache.get(101, compute)
        assertEquals(2, calls())
    }
}
