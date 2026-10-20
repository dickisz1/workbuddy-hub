package com.wbhub.app.proto

/**
 * 国内版 / 国际版是两套彼此独立的账号体系，可同时登录并随时切换。
 *
 * [platform] 是上游登录/授权接口要求的平台标识，与站点一一对应：
 * 国内版 `workbuddy`，国际版 `workbuddy-ai`。
 */
enum class Region(
    val id: String,
    val displayName: String,
    val apiDomain: String,
    val platform: String,
) {

    CN("cn", "国内版", "copilot.tencent.com", "workbuddy"),
    INTL("intl", "国际版", "www.workbuddy.ai", "workbuddy-ai");

    /** 上游站点根。聊天、授权、配置等接口都挂在它下面。 */
    val host: String get() = "https://$apiDomain"

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

    /**
     * 产品配置（模型清单）。
     *
     * 取自 WorkBuddy 客户端自身的 `GET /v3/config`：返回 `data.agents`（`cli` 一项的
     * `models` 是可用模型 id）与 `data.models`（每个模型的名称、上下文、价格）。需要携带
     * 与对话相同的身份头，未登录会 404/401。
     */
    const val UPSTREAM_MODELS_PATH = "/v3/config"

    /** 每日签到。 */
    const val UPSTREAM_SIGNIN_PATH = "/v2/billing/meter/daily-checkin"

    /** 待确证：上游未见「活跃上报」接口，此路径暂无依据。 */
    const val UPSTREAM_ACTIVE_PATH = "/api/growth/active-report"

    // ------------------------------------------------------------------
    // 浏览器登录（WorkBuddy 原生凭证流程）
    // ------------------------------------------------------------------

    /** 申请登录 state，返回 `{state, authUrl}`。 */
    const val AUTH_STATE_PATH = "/v2/plugin/auth/state"

    /** 轮询登录结果，返回 accessToken / refreshToken / 过期时间。 */
    const val AUTH_TOKEN_PATH = "/v2/plugin/auth/token"

    /** 用 refreshToken 换新令牌。 */
    const val AUTH_REFRESH_PATH = "/v2/plugin/auth/token/refresh"

    /** 用 accessToken 取账号信息（uid、企业 id）。 */
    const val LOGIN_ACCOUNT_PATH = "/v2/plugin/login/account"

    /** 登录页版本参数，WorkBuddy 客户端打开登录页时带的值。 */
    const val LOGIN_PAGE_VERSION = "2.0.0"

    /** 上游表示「用户还没在浏览器里完成登录」的业务码，需要继续轮询。 */
    const val CODE_SIGN_IN_PENDING = 11217
    const val CODE_ACCOUNT_PENDING = 12151

    /** 申请 state / 轮询账号时用来声明「本次请求不携带身份」。 */
    const val HEADER_NO_AUTHORIZATION = "X-No-Authorization"
    const val HEADER_NO_USER_ID = "X-No-User-Id"
    const val HEADER_NO_ENTERPRISE_ID = "X-No-Enterprise-Id"
    const val HEADER_NO_DEPARTMENT_INFO = "X-No-Department-Info"

    /** 刷新令牌用头。 */
    const val HEADER_REFRESH_TOKEN = "X-Refresh-Token"
    const val HEADER_AUTH_REFRESH_SOURCE = "X-Auth-Refresh-Source"
    const val AUTH_REFRESH_SOURCE_VALUE = "plugin"

    /** 企业（团队）账号必须带，否则上游按个人套餐作答。 */
    const val HEADER_ENTERPRISE_ID = "X-Enterprise-Id"
    const val HEADER_TENANT_ID = "X-Tenant-Id"

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
    const val REQUESTED_WITH_VALUE = "XMLHttpRequest"

    /** 上游要求以 WorkBuddy 客户端的 UA 发起，缺了会直接回错误码 10085。 */
    const val UPSTREAM_USER_AGENT = "WorkBuddy/$IDE_VERSION_VALUE"

    const val PRODUCT_NAME = "workbuddy-hub"
    const val APP_VERSION = "0.4.0"
    const val CLIENT_TYPE = "android-hub"

    fun upstreamUrl(region: Region, path: String): String = region.host + path

    fun localEndpoint(port: Int): String = "http://$LOOPBACK:$port$CHAT_COMPLETIONS_PATH"

    fun modelsEndpoint(port: Int): String = "http://$LOOPBACK:$port$MODELS_PATH"

    /** 给上游返回的登录页补上客户端参数，与 WorkBuddy 官方客户端一致。 */
    fun withLoginParams(authUrl: String, loginSessionId: String): String {
        val separator = if (authUrl.contains('?')) '&' else '?'
        return "$authUrl${separator}version=$LOGIN_PAGE_VERSION&loginSessionId=$loginSessionId"
    }

    /**
     * 每次转发都补上，用于通过上游的身份校验。
     *
     * - `X-Domain` 取令牌里的 domain，缺省回落到站点域名；
     * - 企业账号额外带 `X-Enterprise-Id` / `X-Tenant-Id`（两者同值），个人账号不带；
     * - `X-Agent-Type` 只有国际版要带。
     */
    fun identityHeaders(
        userId: String,
        domain: String,
        region: Region,
        enterpriseId: String = "",
    ): Map<String, String> = buildMap {
        put(HEADER_USER_ID, userId)
        put(HEADER_DOMAIN, domain.ifBlank { region.apiDomain })
        put(HEADER_PRODUCT, PRODUCT_VALUE)
        put(HEADER_IDE_TYPE, IDE_TYPE_VALUE)
        put(HEADER_IDE_NAME, IDE_NAME_VALUE)
        put(HEADER_IDE_VERSION, IDE_VERSION_VALUE)
        put(HEADER_AGENT_PURPOSE, AGENT_PURPOSE_VALUE)
        put(HEADER_AGENT_INTENT, AGENT_INTENT_VALUE)
        put(HEADER_REQUESTED_WITH, REQUESTED_WITH_VALUE)
        if (region == Region.INTL) put(HEADER_AGENT_TYPE, AGENT_TYPE_VALUE)
        if (enterpriseId.isNotBlank()) {
            put(HEADER_ENTERPRISE_ID, enterpriseId)
            put(HEADER_TENANT_ID, enterpriseId)
        }
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
