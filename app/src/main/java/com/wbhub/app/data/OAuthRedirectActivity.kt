package com.wbhub.app.data

import android.app.Activity
import android.os.Bundle
import com.wbhub.app.App

/**
 * 自定义 scheme 回调兜底入口：部分 ROM 不会把 127.0.0.1 回调转成浏览器跳转，
 * 此时由 OAuth 授权页重定向到 wbhub://oauth 触发本 Activity。
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as App).login.handleRedirect(it) }
        finish()
    }
}
