package com.wbhub.app

import android.app.Application
import com.wbhub.app.data.Login
import com.wbhub.app.proto.CredentialStore

/**
 * 全局单例入口：持有凭证存储与登录入口，供 UI 与前台服务共享。
 */
class App : Application() {

    val credentials: CredentialStore by lazy { CredentialStore(this) }

    val login: Login by lazy { Login(credentials) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
