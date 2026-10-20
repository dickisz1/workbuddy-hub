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
     * 官方 IAM 文档：国内站走 `copilot.tencent.com`（`CODEBUDDY_INTERNET_ENVIRONMENT=internal`），
     * 国际站走 `codebuddy.ai`（默认 `public`）。Project 名沿用 WorkBuddy，`www.workbuddy.ai`
     * 与 `www.codebuddy.ai` 是同一套服务的两个品牌域，实测两者的 `/oauth2/token` 都回
     * `invalid_client`，即端点为真。
     */
    val apiDomain: String
        get() = when (this) {
            CN -> "copilot.tencent.com"
            INTL -> "www.workbuddy.ai"
        }

    /** 上游站点根。聊天、签到等接口都挂在它下面，各带自己的版本前缀。 */
    val host: String get() = "https://$apiDomain"

    /**
     * 企业凭证换取 access_token 的端点（OAuth2 `client_credentials`）。
     *
     * 官方 IAM 文档写的就是这一条：POST `grant_type=client_credentials` + `client_id` +
     * `client_secret`。实测假凭证回 `{"error":"invalid_client"}`，端点为真。
     * 个人账号不经过这里，直接用静态 API Key 调上游（`X-Api-Key` 头）。
     */
    val tokenUrl: String
        get() = "$host/oauth2/token"

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
     * 待确证：官方没有公开「拉取模型列表」的私有接口。
     *
     * `/v3/config` 实测 404，其余候选路径（/v2/models、/v1/models、/v2/model/config 等）同样 404。
     * CLI 的模型列表实际来自本地 `models.json`（见官方 models.json 配置指南），因此本地
     * `/v1/models` 拉不到上游时返回空列表即可，不影响对话。
     */
    const val UPSTREAM_MODELS_PATH = "/v3/config"

    /** 每日签到。 */
    const val UPSTREAM_SIGNIN_PATH = "/v2/billing/meter/daily-checkin"

    /** 待确证：上游未见「活跃上报」接口，此路径暂无依据。 */
    const val UPSTREAM_ACTIVE_PATH = "/api/growth/active-report"

    /**
     * 个人账号的静态 API Key 头。
     *
     * 官方 IAM 文档 `CODEBUDDY_API_KEY` 一项：Auth Type = API Key（`X-Api-Key` header）。
     * 企业账号走 `Authorization: Bearer <access_token>`，两者互斥。
     */
    const val HEADER_API_KEY = "X-Api-Key"

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
    const val APP_VERSION = "0.3.1"
    const val CLIENT_TYPE = "android-hub"

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
