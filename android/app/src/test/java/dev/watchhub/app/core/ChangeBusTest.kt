package dev.watchhub.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** 期望语义来自 SPEC 2026-10-25-watchhub-2.6.0 §3 A5。 */
class ChangeBusTest {

    /** 测试用直接执行器：post 立刻在当前线程跑监听者。 */
    private val direct = ChangeBus { it.run() }

    @Test
    fun deliversToRegisteredListeners() {
        val bus = direct
        val hits = AtomicInteger(0)
        val l1: () -> Unit = { hits.incrementAndGet() }
        val l2: () -> Unit = { hits.incrementAndGet() }
        bus.register(l1)
        bus.register(l2)
        bus.post()
        assertEquals(2, hits.get())
    }

    @Test
    fun unregisteredListenerNotCalled() {
        val bus = direct
        val hits = AtomicInteger(0)
        val l: () -> Unit = { hits.incrementAndGet() }
        bus.register(l)
        bus.unregister(l)
        bus.post()
        assertEquals(0, hits.get())
    }

    @Test
    fun postingUsesInjectedExecutor() {
        // 生产语义：无论哪个线程 post，监听者都以 Runnable 形式交给注入的执行器
        val sb = StringBuffer()
        val bus = ChangeBus { r -> sb.append("executor:"); r.run() }
        bus.register { sb.append("listener") }
        bus.post()
        assertEquals("executor:listener", sb.toString())
    }

    @Test
    fun throwingListenerDoesNotBlockOthers() {
        val bus = direct
        var secondCalled = false
        bus.register { throw IllegalStateException("boom") }
        bus.register { secondCalled = true }
        bus.post()
        assertTrue(secondCalled)
    }
}
