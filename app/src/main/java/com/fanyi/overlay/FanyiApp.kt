package com.fanyi.overlay

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.fanyi.overlay.data.SettingsStore
import java.util.Locale

class FanyiApp : Application() {
    lateinit var settings: SettingsStore
        private set
    var storageError: String? = null
        private set

    override fun attachBaseContext(base: Context) {
        val locale = Locale.SIMPLIFIED_CHINESE
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate() {
        super.onCreate()
        settings = try {
            SettingsStore.open(this)
        } catch (error: Throwable) {
            storageError = "加密存储没有初始化成功：${error.message ?: error.javaClass.simpleName}。密钥只留在本次进程的内存里，退出后需要重填。"
            SettingsStore.memoryOnly()
        }
    }
}
