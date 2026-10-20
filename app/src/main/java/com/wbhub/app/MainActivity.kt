package com.wbhub.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.wbhub.app.ui.ApiPlatformFragment
import com.wbhub.app.ui.ChatFragment
import com.wbhub.app.ui.CredentialsFragment

/**
 * 单 Activity + 三个页签（凭证 / API 平台 / 对话）。
 * 原生 View 实现，不依赖 WebView。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener { item ->
            show(item.itemId)
            true
        }

        if (savedInstanceState == null) show(R.id.nav_credential)
        requestNotificationPermission()
    }

    private fun show(itemId: Int) {
        val tag = tagOf(itemId)
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()
        manager.fragments.forEach(transaction::hide)
        val existing = manager.findFragmentByTag(tag)
        if (existing != null) {
            transaction.show(existing)
        } else {
            transaction.add(R.id.fragment_container, create(tag), tag)
        }
        transaction.commit()
    }

    private fun tagOf(itemId: Int): String = when (itemId) {
        R.id.nav_platform -> TAG_PLATFORM
        R.id.nav_chat -> TAG_CHAT
        else -> TAG_CREDENTIAL
    }

    private fun create(tag: String): Fragment = when (tag) {
        TAG_PLATFORM -> ApiPlatformFragment()
        TAG_CHAT -> ChatFragment()
        else -> CredentialsFragment()
    }

    /** 前台服务通知在 Android 13+ 需要运行时授权。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private companion object {
        const val TAG_CREDENTIAL = "credential"
        const val TAG_PLATFORM = "platform"
        const val TAG_CHAT = "chat"
    }
}
