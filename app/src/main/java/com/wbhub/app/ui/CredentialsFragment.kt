package com.wbhub.app.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.wbhub.app.App
import com.wbhub.app.R
import com.wbhub.app.bridge.BridgePrefs
import com.wbhub.app.data.Login
import com.wbhub.app.proto.AuthMode
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import kotlinx.coroutines.launch

/**
 * 凭证页：选择国内版/国际版 → 填凭证 → 加密保存。
 *
 * 两条官方路径：
 * - 个人账号：静态 API Key（`X-Api-Key` 头），本地直接保存，不联网校验；
 * - 企业账号：Client ID / Secret 走 `client_credentials` 换 access_token。
 * 两个区域是独立账号体系，可同时登录并随时切换。
 */
class CredentialsFragment : Fragment(R.layout.fragment_credentials) {

    private lateinit var credentials: CredentialStore
    private lateinit var login: Login
    private lateinit var prefs: BridgePrefs
    private lateinit var statusView: TextView
    private lateinit var regionGroup: RadioGroup
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var clientIdInput: TextInputEditText
    private lateinit var clientSecretInput: TextInputEditText

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as App
        credentials = app.credentials
        login = app.login
        prefs = BridgePrefs(requireContext())

        statusView = view.findViewById(R.id.token_status)
        regionGroup = view.findViewById(R.id.region_group)
        apiKeyInput = view.findViewById(R.id.api_key_input)
        clientIdInput = view.findViewById(R.id.client_id_input)
        clientSecretInput = view.findViewById(R.id.client_secret_input)

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

        view.findViewById<MaterialButton>(R.id.btn_save_api_key).setOnClickListener { saveApiKey() }
        view.findViewById<MaterialButton>(R.id.btn_save_client).setOnClickListener { saveClientCredentials() }
        view.findViewById<MaterialButton>(R.id.btn_refresh).setOnClickListener { refreshToken() }
        view.findViewById<MaterialButton>(R.id.btn_logout).setOnClickListener {
            credentials.clear(selectedRegion())
            renderStatus(selectedRegion())
        }

        renderStatus(selectedRegion())
    }

    private fun selectedRegion(): Region =
        if (regionGroup.checkedRadioButtonId == R.id.region_intl) Region.INTL else Region.CN

    /** 个人账号：API Key 直接落盘，不需要联网。 */
    private fun saveApiKey() {
        val region = selectedRegion()
        val key = apiKeyInput.text?.toString().orEmpty().trim()
        if (key.isEmpty()) {
            statusView.text = "${region.displayName}：请先填写 API Key"
            return
        }
        runCatching { login.loginWithApiKey(region, key) }
            .onSuccess {
                renderStatus(region)
                statusView.text = "${region.displayName}：已保存 API Key（个人账号）"
            }
            .onFailure { statusView.text = "${region.displayName}：保存失败 ${it.message}" }
    }

    /** 企业账号：先换 token，成功才落盘。 */
    private fun saveClientCredentials() {
        val region = selectedRegion()
        val id = clientIdInput.text?.toString().orEmpty().trim()
        val secret = clientSecretInput.text?.toString().orEmpty().trim()
        if (id.isEmpty() || secret.isEmpty()) {
            statusView.text = "${region.displayName}：请填写 Client ID 与 Client Secret"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            statusView.text = "${region.displayName}：正在换取 Token…"
            runCatching { login.loginWithClientCredentials(region, id, secret) }
                .onSuccess {
                    clientSecretInput.text?.clear()
                    renderStatus(region)
                    statusView.text = "${region.displayName}：已登录（企业账号）"
                }
                .onFailure { statusView.text = "${region.displayName}：登录失败 ${it.message}" }
        }
    }

    /** 仅企业账号需要刷新；个人 API Key 模式无 token 可刷新。 */
    private fun refreshToken() {
        val region = selectedRegion()
        if (credentials.mode(region) != AuthMode.CLIENT_CREDENTIALS) {
            statusView.text = "${region.displayName}：当前不是企业账号，无需刷新 Token"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            statusView.text = "${region.displayName}：刷新 Token 中…"
            val token = login.refresh(region)
            statusView.text = if (token.isNullOrBlank()) {
                "${region.displayName}：刷新失败，请重新登录"
            } else {
                "${region.displayName}：已刷新，${expireHint(region)}"
            }
        }
    }

    private fun renderStatus(region: Region) {
        // 回填已保存的标识类字段，方便查看与修改；Secret 不回填。
        if (apiKeyInput.text.isNullOrEmpty()) {
            apiKeyInput.setText(credentials.apiKey(region).orEmpty())
        }
        if (clientIdInput.text.isNullOrEmpty()) {
            clientIdInput.setText(credentials.clientId(region).orEmpty())
        }

        statusView.text = when (credentials.mode(region)) {
            AuthMode.NONE -> "${region.displayName}：未登录"
            AuthMode.API_KEY ->
                "${region.displayName}：已登录（个人账号）\napiKey=${mask(credentials.apiKey(region).orEmpty())}"
            AuthMode.CLIENT_CREDENTIALS ->
                "${region.displayName}：已登录（企业账号）\naccess=${mask(credentials.accessToken(region).orEmpty())}\n${expireHint(region)}"
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
