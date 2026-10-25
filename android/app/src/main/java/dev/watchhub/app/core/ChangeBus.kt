package dev.watchhub.app.core

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程内变更总线：UI 写操作完成后通知所有已订阅页面即时刷新。
 *
 * 学的是 FlClash「写操作 → 状态变 → 页面自动刷」的思路，但用 40 行手写实现
 * 而不是引 Riverpod——投递线程由构造注入（生产传主线程 Handler，测试传直接执行器），
 * core 保持不依赖 Android。监听者异常互不影响；register/unregister 必须成对
 * （Fragment 在 onResume/onPause 配对调用，避免泄漏）。
 */
class ChangeBus(private val post: (Runnable) -> Unit) {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 通知一次变更。可在任意线程调用；监听者统一在注入的执行器上跑。 */
    fun post() {
        post(Runnable {
            listeners.forEach { l -> runCatching(l) }
        })
    }
}
