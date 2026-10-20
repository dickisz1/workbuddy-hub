package com.wbhub.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.wbhub.app.App
import com.wbhub.app.R
import com.wbhub.app.agent.AgentClient
import com.wbhub.app.bridge.BridgePrefs
import com.wbhub.app.proto.Region
import com.wbhub.app.proto.UpstreamClient
import kotlinx.coroutines.launch

/**
 * 内置对话页：直接用当前账号的模型聊天，工具调用由 [AgentClient] 自动编排。
 */
class ChatFragment : Fragment(R.layout.fragment_chat) {

    private lateinit var agent: AgentClient
    private lateinit var prefs: BridgePrefs
    private lateinit var transcriptView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var messageInput: TextInputEditText
    private lateinit var modelInput: TextInputEditText

    private val transcript = StringBuilder()
    private var busy = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val app = requireActivity().application as App
        prefs = BridgePrefs(requireContext())
        agent = AgentClient(requireContext(), app.credentials, UpstreamClient(app.credentials))

        transcriptView = view.findViewById(R.id.chat_transcript)
        scrollView = view.findViewById(R.id.chat_scroll)
        messageInput = view.findViewById(R.id.input_message)
        modelInput = view.findViewById(R.id.input_model)

        modelInput.setText(prefs.model)
        view.findViewById<MaterialButton>(R.id.btn_send).setOnClickListener { send() }
    }

    private fun send() {
        if (busy) return
        val prompt = messageInput.text?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) return

        val region = currentRegion()
        if (!credentialsLoggedIn(region)) {
            appendLine(getString(R.string.chat_need_login))
            return
        }

        val model = modelInput.text?.toString()?.trim().orEmpty().ifEmpty { prefs.model }
        prefs.model = model
        messageInput.setText("")
        appendLine("你：$prompt")

        val activity = requireActivity()
        busy = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                agent.ask(region, model, prompt) { delta ->
                    activity.runOnUiThread { appendRaw(delta) }
                }
            } catch (error: Exception) {
                activity.runOnUiThread { appendLine("出错：${error.message}") }
            } finally {
                busy = false
            }
        }
    }

    private fun currentRegion(): Region {
        val selected = Region.fromId(prefs.selectedRegion)
        if (credentialsLoggedIn(selected)) return selected
        return Region.entries.firstOrNull { credentialsLoggedIn(it) } ?: Region.CN
    }

    private fun credentialsLoggedIn(region: Region): Boolean =
        (requireActivity().application as App).credentials.isLoggedIn(region)

    private fun appendLine(text: String) {
        appendRaw("\n$text\n")
    }

    private fun appendRaw(text: String) {
        transcript.append(text)
        transcriptView.text = transcript.toString()
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
