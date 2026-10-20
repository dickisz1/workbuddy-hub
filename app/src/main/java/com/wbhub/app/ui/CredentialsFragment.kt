package com.wbhub.app.ui

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
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 凭证页：选择国内版/国际版 → 走 OAuth 授权 → 加密保存 token。
 * 两个区域是独立账号体系，可同时登录并随时切换。
 */
class CredentialsFragment : Fragment(R.layout.fragment_credentials) {

    private lateinit var credentials: CredentialStore
    private lateinit var login: Login
    private lateinit var prefs: BridgePrefs
    private lateinit var statusView: TextView
    private lateinit var regionGroup: RadioGroup

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
            renderStatus(region)
        }

        view.findViewById<MaterialButton>(R.id.btn_login).setOnClickListener { startLogin() }
        view.findViewById<MaterialButton>(R.id.btn_refresh).setOnClickListener { refreshToken() }
        view.findViewById<MaterialButton>(R.id.btn_logout).setOnClickListener {
            credentials.clear(selectedRegion())
            renderStatus(selectedRegion())
        }

        renderStatus(selectedRegion())
    }

    private fun selectedRegion(): Region =
        if (regionGroup.checkedRadioButtonId == R.id.region_intl) Region.INTL else Region.CN

    private fun startLogin() {
        val region = selectedRegion()
        statusView.text = "${region.displayName}：正在打开浏览器授权…"
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { login.login(region) } }
                .onSuccess { statusView.text = "${region.displayName}：已登录" }
                .onFailure { statusView.text = "${region.displayName}：登录失败 ${it.message}" }
        }
    }

    private fun refreshToken() {
        val region = selectedRegion()
        viewLifecycleOwner.lifecycleScope.launch {
            statusView.text = "${region.displayName}：刷新 Token 中…"
            val token = withContext(Dispatchers.IO) { UpstreamClient(credentials).refresh(region) }
            statusView.text = if (token.isNullOrBlank()) {
                "${region.displayName}：刷新失败，请重新登录"
            } else {
                "${region.displayName}：已刷新，有效期 ${expireHint(region)}"
            }
        }
    }

    private fun renderStatus(region: Region) {
        val account = credentials.account(region)
        statusView.text = if (account == null) {
            "${region.displayName}：未登录"
        } else {
            "${region.displayName}：已登录\naccess=${mask(account.accessToken)}\n${expireHint(region)}"
        }
    }

    private fun expireHint(region: Region): String {
        val expiresAt = credentials.account(region)?.expiresAt ?: 0L
        if (expiresAt == 0L) return "有效期未知"
        val remain = expiresAt - System.currentTimeMillis()
        return if (remain <= 0) "已过期，将自动刷新" else "剩余 ${remain / 60_000} 分钟"
    }

    private fun mask(token: String): String =
        if (token.length <= 8) "****" else "${token.take(4)}…${token.takeLast(4)}"
}
