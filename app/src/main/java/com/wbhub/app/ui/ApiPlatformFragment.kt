package com.wbhub.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.wbhub.app.App
import com.wbhub.app.R
import com.wbhub.app.bridge.BridgePrefs
import com.wbhub.app.bridge.BridgeService
import com.wbhub.app.bridge.CallLogStore
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * API 平台页：开关本地转发服务、配置端口与共享密钥、查看模型与调用日志。
 */
class ApiPlatformFragment : Fragment(R.layout.fragment_api_platform) {

    private lateinit var prefs: BridgePrefs
    private lateinit var credentials: CredentialStore
    private lateinit var callLog: CallLogStore

    private lateinit var serviceSwitch: MaterialSwitch
    private lateinit var overlaySwitch: MaterialSwitch
    private lateinit var portInput: TextInputEditText
    private lateinit var secretInput: TextInputEditText
    private lateinit var endpointView: TextView

    /** 悬浮窗权限申请结果：授权成功才真正打开悬浮窗。 */
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            applyOverlay(Settings.canDrawOverlays(requireContext()))
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as App
        prefs = BridgePrefs(requireContext())
        credentials = app.credentials
        callLog = CallLogStore(requireContext())

        serviceSwitch = view.findViewById(R.id.switch_service)
        overlaySwitch = view.findViewById(R.id.switch_overlay)
        portInput = view.findViewById(R.id.input_port)
        secretInput = view.findViewById(R.id.input_secret)
        endpointView = view.findViewById(R.id.endpoint_info)

        portInput.setText(prefs.port.toString())
        secretInput.setText(prefs.secret)
        serviceSwitch.isChecked = prefs.serviceEnabled || BridgeService.running
        overlaySwitch.isChecked = prefs.overlayEnabled
        renderEndpoint()

        serviceSwitch.setOnCheckedChangeListener { _, checked -> toggleService(checked) }
        overlaySwitch.setOnCheckedChangeListener { _, checked -> toggleOverlay(checked) }
        view.findViewById<MaterialButton>(R.id.btn_apply).setOnClickListener { applyConfig() }
        view.findViewById<MaterialButton>(R.id.btn_models).setOnClickListener { loadModels() }
    }

    override fun onResume() {
        super.onResume()
        renderLog()
        overlaySwitch.isChecked = prefs.overlayEnabled
    }

    private fun toggleService(checked: Boolean) {
        prefs.serviceEnabled = checked
        if (checked) {
            BridgeService.start(requireContext())
        } else {
            BridgeService.stop(requireContext())
        }
    }

    private fun toggleOverlay(checked: Boolean) {
        if (checked && !Settings.canDrawOverlays(requireContext())) {
            // 先申请系统悬浮窗权限，拿到后再开
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}"),
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        applyOverlay(checked)
    }

    private fun applyOverlay(granted: Boolean) {
        if (!granted) {
            overlaySwitch.isChecked = false
            prefs.overlayEnabled = false
            BridgeService.setOverlay(requireContext(), false)
            return
        }
        prefs.overlayEnabled = true
        overlaySwitch.isChecked = true
        BridgeService.setOverlay(requireContext(), true)
    }

    private fun applyConfig() {
        val port = portInput.text?.toString()?.toIntOrNull()
        if (port == null || port !in MIN_PORT..MAX_PORT) {
            endpointView.text = "端口需在 $MIN_PORT - $MAX_PORT 之间"
            return
        }
        val secret = secretInput.text?.toString().orEmpty()
        if (secret.isBlank()) {
            endpointView.text = "共享密钥不能为空"
            return
        }
        prefs.port = port
        prefs.secret = secret
        renderEndpoint()

        // 服务已在运行时重启一次以应用新配置
        if (BridgeService.running) {
            BridgeService.stop(requireContext())
            view?.postDelayed({ BridgeService.start(requireContext()) }, RESTART_DELAY_MS)
        }
    }

    private fun loadModels() {
        val region = currentRegion()
        endpointView.text = "正在拉取 ${region.displayName} 模型列表…"
        viewLifecycleOwner.lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                runCatching { UpstreamClient(credentials).fetchModels(region) }.getOrDefault(emptyList())
            }
            endpointView.text = if (models.isEmpty()) {
                "未取到模型列表（请确认 ${region.displayName} 已登录）"
            } else {
                buildString {
                    append(renderEndpointText())
                    append("\n\n可用模型：\n")
                    append(models.joinToString("\n"))
                }
            }
        }
    }

    private fun currentRegion(): Region {
        val selected = Region.fromId(prefs.selectedRegion)
        if (credentials.isLoggedIn(selected)) return selected
        return Region.entries.firstOrNull { credentials.isLoggedIn(it) } ?: Region.CN
    }

    private fun renderEndpoint() {
        endpointView.text = renderEndpointText()
    }

    private fun renderEndpointText(): String = buildString {
        append("API 端点：${prefs.endpoint()}\n")
        append("API 密钥：${prefs.secret}\n")
        append("模型列表：${prefs.modelsEndpoint()}\n")
        append("供应商：OpenAI 兼容")
    }

    private fun renderLog() {
        val text = callLog.formatted(LOG_LINES)
        view?.findViewById<TextView>(R.id.log_view)?.text =
            text.ifBlank { "暂无调用记录" }
    }

    private companion object {
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val RESTART_DELAY_MS = 300L
        const val LOG_LINES = 30
    }
}
