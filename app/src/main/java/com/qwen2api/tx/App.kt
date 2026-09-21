package com.qwen2api.tx

import android.app.Application
import com.qwen2api.tx.core.ConfigStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // 首次启动即生成 API 密钥并落盘
        ConfigStore.load(this)
    }

    companion object {
        lateinit var INSTANCE: App
            private set
    }
}
