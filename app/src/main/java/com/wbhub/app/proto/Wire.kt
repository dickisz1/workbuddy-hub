package com.wbhub.app.proto

/**
 * 国内版 / 国际版是两套彼此独立的账号体系，可同时登录并随时切换。
 */
enum class Region(val id: String, val displayName: String) {

    CN("cn", "国内版"),
    INTL("intl", "国际版");

    /**
     * 上游站点域名，同时也是身份头 `X-Domain` 的取值。
     *
     * 这里给的是默认值。真实客户端并不写死：它从登录态里的 `auth.domain` 读，不同版本、
     * 不同地区取值可能是 copilot.tencent.com / www.codebuddy.cn 等。拿到账号信息后应以
     * 登录态为准，此处仅作未登录时的兜底。
     */
    val apiDomain: String
        get() = when (this) {
            CN -> "copilot.tencent.com"
            INTL -> "www.workbuddy.ai"
        }

    /** 上游站点根。聊天、签到、模型配置都挂在它下面，各带自己的版本前缀。 */
    val host: String get() = "https://$apiDomain"

    /** OAuth 授权页（App 内通过系统浏览器打开）。 */
    val authorizeUrl: String
        get() = "$host/oauth/authorize"

    /** 换取 access_token / refresh_token。 */
    val tokenUrl: String
        get() = "$host/oauth/token"

    /**
     * client_id。
     *
     * 待确证：个人账号登录不是公开的 PKCE 流程（桌面端走扫码 / 浏览器登录，凭证由客户端
     * 写入本地 auth 文件），目前唯一有文档的 OAuth 是企业 Client Credentials，端点为
     * `https://copilot.tencent.com/oauth2/token`。此值暂无依据，替换真实登录流程时一并修正。
     */
    val clientId: String
        get() = "workbuddy-cli"

    companion object {
        fun fromId(id: String?): Region = entries.firstOrNull { it.id == id } ?: CN
    }
}

/**
 * 与上游协议相关的常量集合：本地端点、上游路径、专有身份头。
 */
object Wire {

    /** 本地监听：仅 loopback。 */
    const val LOOPBACK = "127.0.0.1"
    const val DEFAULT_PORT = 8765
    const val DEFAULT_SECRET = "wb-local"

    /** 本地 OpenAI 兼容端点。 */
    const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
    const val MODELS_PATH = "/v1/models"
    const val HEALTH_PATH = "/health"

    /**
     * 上游私有接口路径。直接拼在 [Region.host] 之后，各自带版本前缀。
     */
    const val UPSTREAM_CHAT_PATH = "/v2/chat/completions"

    /** 待确证：模型列表来自上游给 CLI Agent 的产品配置，是否在 /v2 之下未确认。 */
    const val UPSTREAM_MODELS_PATH = "/v3/config"

    /** 每日签到。 */
    const val UPSTREAM_SIGNIN_PATH = "/v2/billing/meter/daily-checkin"

    /** 待确证：上游未见「活跃上报」接口，此路径暂无依据。 */
    const val UPSTREAM_ACTIVE_PATH = "/api/growth/active-report"

    /** 上游身份头名称。每次转发都要带齐。 */
    const val HEADER_USER_ID = "X-User-Id"
    const val HEADER_DOMAIN = "X-Domain"
    const val HEADER_PRODUCT = "X-Product"
    const val HEADER_IDE_TYPE = "X-IDE-Type"
    const val HEADER_IDE_NAME = "X-IDE-Name"
    const val HEADER_IDE_VERSION = "X-IDE-Version"
    const val HEADER_AGENT_PURPOSE = "X-Agent-Purpose"
    const val HEADER_AGENT_INTENT = "X-Agent-Intent"
    const val HEADER_AGENT_TYPE = "X-Agent-Type"
    const val HEADER_REQUESTED_WITH = "X-Requested-With"

    /**
     * 请求级的会话 id 头。由转发层按请求生成，取值见 [conversationHeaders]。
     */
    const val HEADER_CONVERSATION_ID = "X-Conversation-ID"
    const val HEADER_CONVERSATION_REQUEST_ID = "X-Conversation-Request-ID"
    const val HEADER_CONVERSATION_MESSAGE_ID = "X-Conversation-Message-ID"
    const val HEADER_REQUEST_ID = "X-Request-ID"

    /** 身份头的固定取值：上游按这一组把请求当成 WorkBuddy 客户端。 */
    const val PRODUCT_VALUE = "SaaS"
    const val IDE_TYPE_VALUE = "WorkBuddy"
    const val IDE_NAME_VALUE = "WorkBuddy"
    const val IDE_VERSION_VALUE = "5.5.6"
    const val AGENT_PURPOSE_VALUE = "conversation"
    const val AGENT_INTENT_VALUE = "craft"
    const val AGENT_TYPE_VALUE = "main"

    /** 上游要求以 WorkBuddy 客户端的 UA 发起，缺了会直接回错误码 10085。 */
    const val UPSTREAM_USER_AGENT = "WorkBuddy/$IDE_VERSION_VALUE"

    const val PRODUCT_NAME = "workbuddy-hub"
    const val APP_VERSION = "0.3.0"
    const val CLIENT_TYPE = "android-hub"

    /** OAuth 回调：优先 loopback，失败时回落到自定义 scheme。 */
    const val REDIRECT_SCHEME = "http://127.0.0.1"
    const val REDIRECT_CUSTOM_SCHEME = "wbhub://oauth"

    fun upstreamUrl(region: Region, path: String): String = region.host + path

    fun localEndpoint(port: Int): String = "http://$LOOPBACK:$port$CHAT_COMPLETIONS_PATH"

    fun modelsEndpoint(port: Int): String = "http://$LOOPBACK:$port$MODELS_PATH"

    /**
     * 每次转发都补上，用于通过上游的身份校验。
     *
     * `X-Agent-Type` 只有国际版要带；企业账号还需要 `X-Enterprise-Id` / `X-Tenant-Id`，
     * 个人账号不带即按个人套餐计。
     */
    fun identityHeaders(userId: String, region: Region): Map<String, String> = buildMap {
        put(HEADER_USER_ID, userId)
        put(HEADER_DOMAIN, region.apiDomain)
        put(HEADER_PRODUCT, PRODUCT_VALUE)
        put(HEADER_IDE_TYPE, IDE_TYPE_VALUE)
        put(HEADER_IDE_NAME, IDE_NAME_VALUE)
        put(HEADER_IDE_VERSION, IDE_VERSION_VALUE)
        put(HEADER_AGENT_PURPOSE, AGENT_PURPOSE_VALUE)
        put(HEADER_AGENT_INTENT, AGENT_INTENT_VALUE)
        // 取值来源未确证，这里取该头的惯用值
        put(HEADER_REQUESTED_WITH, "XMLHttpRequest")
        if (region == Region.INTL) put(HEADER_AGENT_TYPE, AGENT_TYPE_VALUE)
    }

    /**
     * 请求级的会话 id 头。
     *
     * 上游按 `X-Conversation-Request-ID` 把「一次用户发送」算作一个请求，所以同一次发送的
     * 所有步骤（工具结果回灌、重试）必须传同一个值，用户下一条消息才换新的。取值怎么算
     * 由转发层决定，见 `BridgeServer.deriveConversationHeaders`。
     */
    fun conversationHeaders(
        conversationId: String,
        conversationRequestId: String,
        conversationMessageId: String,
        requestId: String,
    ): Map<String, String> = mapOf(
        HEADER_CONVERSATION_ID to conversationId,
        HEADER_CONVERSATION_REQUEST_ID to conversationRequestId,
        HEADER_CONVERSATION_MESSAGE_ID to conversationMessageId,
        HEADER_REQUEST_ID to requestId,
    )

    /** 从 `Authorization: Bearer xxx` 中取出密钥。 */
    fun extractToken(headerValue: String?): String? {
        if (headerValue.isNullOrBlank()) return null
        val trimmed = headerValue.trim()
        return if (trimmed.startsWith("Bearer ", ignoreCase = true)) {
            trimmed.substring(7).trim()
        } else {
            trimmed
        }
    }
}
