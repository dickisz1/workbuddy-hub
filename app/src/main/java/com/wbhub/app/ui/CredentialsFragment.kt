package com.wbhub.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.wbhub.app.App
import com.wbhub.app.R
import com.wbhub.app.bridge.BridgePrefs
import com.wbhub.app.data.Login
import com.wbhub.app.proto.Account
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 凭证页：选择国内版/国际版 → 浏览器登录 → 加密保存原生令牌。
 *
 * 流程与 WorkBuddy 官方客户端一致：申请 state → 打开官方登录页 → 轮询取回
 * accessToken / refreshToken → 读取 uid 与 enterpriseId。没有授权码回调，
 * 也不需要手工填写 API Key。
 */
class CredentialsFragment : Fragment(R.layout.fragment_credentials) {

    private lateinit var credentials: CredentialStore
    private lateinit var login: Login
    private lateinit var prefs: BridgePrefs
    private lateinit var statusView: TextView
    private lateinit var regionGroup: RadioGroup

    /** 一次登录的轮询任务，切换区域或退出页面时取消。 */
    private var signInJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as App
        credentials = app.credentials
        login = app.login
        prefs = BridgePrefs(requireContext())

        statusView = view.findViewById(R.id.token_status)
        regionGroup = view.findViewById(R.id.region_group)

        if (prefs.selectedRegion == Region.INTL.id) {
            regionGroup.check(R.id.region_intl)
        } else {
            regionGroup.check(R.id.region_cn)
        }
        regionGroup.setOnCheckedChangeListener { _, checkedId ->
            val region = if (checkedId == R.id.region_intl) Region.INTL else Region.CN
            prefs.selectedRegion = region.id
            signInJob?.cancel()
            renderStatus(region)
        }

        view.findViewById<MaterialButton>(R.id.btn_login).setOnClickListener { startBrowserSignIn() }
        view.findViewById<MaterialButton>(R.id.btn_refresh).setOnClickListener { refreshToken() }
        view.findViewById<MaterialButton>(R.id.btn_logout).setOnClickListener {
            val region = selectedRegion()
            signInJob?.cancel()
            credentials.clear(region)
            renderStatus(region)
        }

        renderStatus(selectedRegion())
    }

    override fun onDestroyView() {
        signInJob?.cancel()
        super.onDestroyView()
    }

    private fun selectedRegion(): Region =
        if (regionGroup.checkedRadioButtonId == R.id.region_intl) Region.INTL else Region.CN

    /** 申请 state → 打开官方登录页 → 后台轮询直到拿到令牌。 */
    private fun startBrowserSignIn() {
        val region = selectedRegion()
        signInJob?.cancel()
        signInJob = viewLifecycleOwner.lifecycleScope.launch {
            status("${region.displayName}：正在申请登录页…")
            val signIn = runCatching { login.beginSignIn(region) }.getOrElse {
                status("${region.displayName}：无法发起登录\n${it.message}")
                return@launch
            }
            val opened = runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(signIn.authUrl)))
            }.isSuccess
            if (!opened) {
                status("${region.displayName}：没有可用的浏览器，请手动打开\n${signIn.authUrl}")
                return@launch
            }
            status("${region.displayName}：已在浏览器打开登录页，完成登录后会自动取回凭证…")

            runCatching {
                login.awaitSignIn(region, signIn.state) { message ->
                    status("${region.displayName}：$message")
                }
            }.onSuccess {
                renderStatus(region)
                status("${region.displayName}：登录成功\n${summary(it)}")
            }.onFailure {
                status("${region.displayName}：登录失败\n${it.message}")
            }
        }
    }

    /** 用 refreshToken 静默续期。 */
    private fun refreshToken() {
        val region = selectedRegion()
        if (!credentials.isLoggedIn(region)) {
            status("${region.displayName}：未登录，请先使用浏览器登录")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            status("${region.displayName}：正在刷新 Token…")
            val token = login.refresh(region)
            if (token.isNullOrBlank()) {
                status("${region.displayName}：刷新失败，请重新登录")
            } else {
                val account = credentials.account(region)
                status("${region.displayName}：已刷新\n${account?.let { summary(it) }.orEmpty()}")
            }
        }
    }

    private fun renderStatus(region: Region) {
        val account = credentials.account(region)
        statusView.text = if (account == null) {
            "${region.displayName}：未登录"
        } else {
            "${region.displayName}：已登录\n${summary(account)}"
        }
    }

    private fun summary(account: Account): String = buildString {
        append("uid=")
        append(account.uid.ifBlank { "-" })
        if (account.enterpriseId.isNotBlank()) {
            append(" · 企业=")
            append(account.enterpriseId)
        }
        append("\naccess=")
        append(mask(account.accessToken))
        append("\n")
        append(expireHint(account))
    }

    private fun expireHint(account: Account): String {
        if (account.expiresAt <= 0L) return "有效期未知（由上游决定）"
        val remain = account.expiresAt - System.currentTimeMillis()
        return if (remain <= 0) "已过期，将自动刷新" else "剩余 ${remain / 60_000} 分钟"
    }

    private fun mask(token: String): String =
        if (token.length <= 8) "****" else "${token.take(4)}…${token.takeLast(4)}"

    /** 轮询回调可能来自 IO 线程，统一切回主线程更新界面。 */
    private fun status(text: String) {
        activity?.runOnUiThread {
            if (isAdded) statusView.text = text
        }
    }
}
