package com.qwen2api.tx.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 外部浏览器登录回调的事件总线。
 *
 * 解决的问题：登录回调由 Activity 的 onNewIntent 触发，而消费方是
 * Compose 的 ViewModel —— 两者没有直接引用关系。用一条进程内的
 * SharedFlow 解耦，同时天然处理「回调先于订阅到达」的场景
 * （用 replay=1 + 显式清空，避免旧凭证被重复消费）。
 */
object LoginCallbackBus {

    /** 一次回调结果：凭证或错误信息（二选一） */
    data class Event(val credential: String?, val error: String?)

    private val _events = MutableSharedFlow<Event>(replay = 1, extraBufferCapacity = 4)

    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun publish(credential: String?, error: String? = null) {
        _events.tryEmit(Event(credential, error))
    }

    /** 消费掉当前事件（避免重复导入同一份凭证） */
    fun consume() {
        _events.resetReplayCache()
    }
}
