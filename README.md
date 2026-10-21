# WorkBuddy Hub

在 Android 手机上把 WorkBuddy 账号变成 **本地 OpenAI 兼容 API**：App 起一个只监听 `127.0.0.1` 的反向代理，任何支持 OpenAI Chat Completions 的客户端（ChatBox、NextChat、LobeChat、各类 IDE 插件、脚本）把 Base URL 指过来，就能直接使用 WorkBuddy 的模型。

- **语言/形态**：Kotlin 原生 Android App，无 WebView、无 NDK、无外部二进制
- **凭证**：复刻 WorkBuddy 原生浏览器登录（`accessToken` + `refreshToken`），不需要手工粘贴 API Key
- **流式**：手写 `ServerSocket` + 原始字节透传，SSE 不二次编码、不乱码、不截断
- **常驻**：前台服务 + 悬浮窗双保险保活，内置每 6 小时自动签到

---

## 1. 能力一览

| 能力 | 说明 | 入口 |
| --- | --- | --- |
| 本地 OpenAI 兼容代理 | `POST /v1/chat/completions`、`GET /v1/models`、`GET /health` | [BridgeServer.kt](file:///workspace/app/src/main/java/com/wbhub/app/bridge/BridgeServer.kt) |
| 原生凭证登录 | 申请 state → 浏览器登录 → 轮询令牌 → 取 uid/企业 id | [Login.kt](file:///workspace/app/src/main/java/com/wbhub/app/data/Login.kt) · [AuthApi.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/AuthApi.kt) |
| 令牌静默续期 | 过期前 5 分钟用 refreshToken 换新，失败退回旧 token | [UpstreamClient.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/UpstreamClient.kt#L150-L179) |
| 双区域并存 | 国内版 / 国际版各自独立登录、独立存储、可同时在线 | [Wire.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/Wire.kt#L9-L25) |
| 模型列表 | 从上游 `GET /v3/config` 取 `agents[cli].models` | [UpstreamClient.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/UpstreamClient.kt#L77-L113) |
| 内置对话 + 工具 | 直接聊天，支持 `read_file` / `write_file` / `bash` 工具编排 | [AgentClient.kt](file:///workspace/app/src/main/java/com/wbhub/app/agent/AgentClient.kt) |
| 用量记录 | 每次调用记录模型、状态码、Token 用量、积分消耗 | [CallLogStore.kt](file:///workspace/app/src/main/java/com/wbhub/app/bridge/CallLogStore.kt) |
| 自动任务 | 每 6 小时对已登录区域执行签到（+ 未验证的活跃上报） | [GrowthClient.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/GrowthClient.kt) |
| 保活 | 前台服务（`specialUse`）+ 可选悬浮窗 | [BridgeService.kt](file:///workspace/app/src/main/java/com/wbhub/app/bridge/BridgeService.kt) · [BridgeOverlay.kt](file:///workspace/app/src/main/java/com/wbhub/app/bridge/BridgeOverlay.kt) |
| 自动更新 | 启动时比对 Release 的 `version.json`，有新版本自动下载并拉起系统安装器 | [Updater.kt](file:///workspace/app/src/main/java/com/wbhub/app/data/Updater.kt) |

---

## 2. 快速开始

### 2.1 安装

- 直接装 GitHub Release 里的 `app-debug.apk`（每个 CI 构建自动发布，tag 为 `v<构建号>`）
- 或本地构建，见 [第 8 节](#8-构建与发布)

系统要求：Android 8.0（API 26）及以上。

### 2.2 登录

1. 打开 App → **凭证** 页 → 选「国内版」或「国际版」
2. 点 **使用浏览器登录**，在打开的官方登录页完成登录
3. 返回 App，页面自动显示 `uid`（企业账号额外显示企业 id）与剩余有效期

无需填写任何 Key。令牌临近过期会自动续期，「刷新 Token」按钮可手动触发一次。

### 2.3 启动转发

1. 切到 **API 平台** 页，按需修改监听端口（默认 `8765`）与共享密钥（默认 `wb-local`）
2. 打开 **启动转发服务** 开关
3. 需要更强保活时再打开 **悬浮窗保活**（会先请求悬浮窗权限）
4. 页面会显示当前端点，点 **拉取模型列表** 可确认模型可用

服务只绑定 `127.0.0.1`，不监听局域网地址，外部设备无法直接访问。

### 2.4 接入客户端

在任意 OpenAI 兼容客户端里填：

| 字段 | 值 |
| --- | --- |
| Base URL / API 地址 | `http://127.0.0.1:8765/v1` |
| API Key | `wb-local`（即共享密钥） |
| Model | `auto`，或 `/v1/models` 返回的任意 id |

> 供应商类型必须选 **OpenAI 通用 / Chat Completions**。本项目只实现了 `POST /v1/chat/completions`，选「OpenAI Responses」「Anthropic」「Gemini」会直接 404。

命令行验证：

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Authorization: Bearer wb-local" \
  -H "Content-Type: application/json" \
  -d '{"model":"auto","messages":[{"role":"user","content":"你好"}],"stream":true}'
```

```python
from openai import OpenAI

client = OpenAI(base_url="http://127.0.0.1:8765/v1", api_key="wb-local")
for chunk in client.chat.completions.create(
    model="auto",
    messages=[{"role": "user", "content": "你好"}],
    stream=True,
):
    print(chunk.choices[0].delta.content or "", end="")
```

---

## 3. 架构

```
┌──────────────┐   OpenAI 协议    ┌──────────────────┐   WorkBuddy 私有协议   ┌──────────────┐
│  外部客户端   │ ───────────────▶ │  BridgeServer     │ ────────────────────▶ │  上游站点     │
│ (ChatBox 等) │ ◀─────────────── │  (127.0.0.1:8765) │ ◀──────────────────── │ copilot /    │
└──────────────┘   SSE 原始字节    └──────────────────┘    Bearer + 身份头      │ workbuddy.ai │
                                            │                                 └──────────────┘
                                            │ 调用记录
                                            ▼
                                   ┌──────────────────┐
                                   │  CallLogStore     │
                                   │  (call_log.jsonl) │
                                   └──────────────────┘
```

### 模块职责

| 层 | 文件 | 职责 |
| --- | --- | --- |
| 入口 | [MainActivity.kt](file:///workspace/app/src/main/java/com/wbhub/app/MainActivity.kt) | 单 Activity + 三个页签（凭证 / API 平台 / 对话） |
| 全局 | [App.kt](file:///workspace/app/src/main/java/com/wbhub/app/App.kt) | 进程级单例：`CredentialStore` / `Login` |
| 协议常量 | [Wire.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/Wire.kt) | 区域定义、本地与上游路径、身份头与固定取值 |
| 认证 | [AuthApi.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/AuthApi.kt) | 登录四个步骤的 HTTP 调用与响应解析 |
| 登录编排 | [Login.kt](file:///workspace/app/src/main/java/com/wbhub/app/data/Login.kt) | 申请 state、轮询、落盘、静默续期 |
| 凭证存储 | [CredentialStore.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/CredentialStore.kt) | 加密持久化，按区域分键 |
| 上游客户端 | [UpstreamClient.kt](file:///workspace/app/src/main/java/com/wbhub/app/proto/UpstreamClient.kt) | 补身份头、按需刷新、流式透传、配置拉取 |
| 代理服务 | [BridgeServer.kt](file:///workspace/app/src/main/java/com/wbhub/app/bridge/BridgeServer.kt) | HTTP 解析、鉴权、请求改写、转发、SSE 透传 |
| 前台服务 | [BridgeService.kt](file:///workspace/app/src/main/java/com/wbhub/app/bridge/BridgeService.kt) | 承载代理、保活通知、定时任务、悬浮窗 |
| 内置 Agent | [AgentClient.kt](file:///workspace/app/src/main/java/com/wbhub/app/agent/AgentClient.kt) · [AgentTools.kt](file:///workspace/app/src/main/java/com/wbhub/app/agent/AgentTools.kt) | 工具调用循环与本地工具实现 |
| 界面 | [ui/](file:///workspace/app/src/main/java/com/wbhub/app/ui) | 凭证页、API 平台页、对话页 |

### 一次转发的链路

1. 校验共享密钥（`Authorization: Bearer <secret>`，不匹配直接 401）
2. 解析请求体 JSON，改写（见 5.3）
3. 选区域：优先 `x-wb-region` 头，其次同名 query，否则用已登录区域（国内版优先）
4. `UpstreamClient` 补 `Authorization: Bearer <access>` 与全套身份头，必要时先刷新令牌
5. 上游返回后立即回写响应头（含 `X-Credits-Consumed`），随后逐块透传字节
6. 结束后从 SSE 尾部提取 `usage`，写一条调用记录

---

## 4. 认证：WorkBuddy 原生凭证

### 4.1 这不是 API Key

WorkBuddy 客户端用的是**浏览器登录换来的令牌对**：`accessToken`（短期）+ `refreshToken`（长期），并附带 `uid` / `domain` / `enterpriseId` 三个身份要素。本项目完全复刻这条路径，没有静态 Key、没有 `client_credentials`、没有本地回调端口。

### 4.2 四步流程

| 步骤 | 请求 | 关键响应 |
| --- | --- | --- |
| ① 申请 state | `POST /v2/plugin/auth/state?platform=workbuddy\|workbuddy-ai` | `data.state`、`data.authUrl` |
| ② 浏览器登录 | 打开 `authUrl` + `version=2.0.0&loginSessionId=<32位hex>` | 用户在官方页面完成登录 |
| ③ 轮询令牌 | `GET /v2/plugin/auth/token?state=<state>` | `accessToken`、`refreshToken`、`expiresAt` / `expiresIn`、`domain` |
| ④ 取账号 | `GET /v2/plugin/login/account?state=<state>` + `Authorization: Bearer <access>` | `uid`、`enterpriseId`、`nickname` |

- `①④` 会带 `X-No-Authorization` / `X-No-User-Id` / `X-No-Enterprise-Id` / `X-No-Department-Info: true`，声明该请求不携带既有身份
- `③` 返回业务码 **11217** 表示「还没登录完」，继续轮询；`④` 返回 **12151** 同理
- 轮询间隔 1.5 秒，整体超时 5 分钟
- 过期时间三种字段都兼容：毫秒时间戳、秒级时间戳、相对秒数

### 4.3 刷新

```
POST /v2/plugin/auth/token/refresh
X-Refresh-Token: <refreshToken>
X-Auth-Refresh-Source: plugin
X-Domain: <domain>
X-Enterprise-Id / X-Tenant-Id: <仅企业账号>
```

- 触发条件：`now >= expiresAt - 5 分钟`
- 用 `AtomicBoolean` 防并发重复刷新，刷新期间其他请求继续用旧 token
- 刷新失败**不报错、不中断**，退回旧 token 让上游自己判定

### 4.4 每次转发必带的身份头

| 头 | 取值 |
| --- | --- |
| `Authorization` | `Bearer <accessToken>` |
| `X-User-Id` | 登录返回的 `uid` |
| `X-Domain` | 令牌里的 `domain`，缺省回落站点域名 |
| `X-Product` | `SaaS` |
| `X-IDE-Type` / `X-IDE-Name` | `WorkBuddy` |
| `X-IDE-Version` | `5.5.6` |
| `X-Agent-Purpose` / `X-Agent-Intent` | `conversation` / `craft` |
| `X-Requested-With` | `XMLHttpRequest` |
| `X-Agent-Type` | `main`，**仅国际版** |
| `X-Enterprise-Id` / `X-Tenant-Id` | 企业账号同值，个人账号不发 |
| `User-Agent` | `WorkBuddy/5.5.6`（缺失上游会回错误码 10085） |

### 4.5 存储

`EncryptedSharedPreferences`（AES256-SIV 键 / AES256-GCM 值），键按区域隔离：`access_cn`、`refresh_intl`、`expires_cn` …… 国内版与国际版互不覆盖。退出登录只清当前区域。

---

## 5. 本地 OpenAI 兼容 API

### 5.1 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/v1/chat/completions` | 对话，按请求的 `stream` 返回 SSE 或单条 JSON |
| `GET` | `/v1/models` | 模型 id 列表 |
| `GET` | `/health` | 存活检查，返回 `{"status":"ok"}`（同样需要密钥） |
| `OPTIONS` | 任意 | CORS 预检，返回 204 |

未实现 `/v1/responses`、`/v1/messages`、`/v1/embeddings` 等，一律 404。

### 5.2 鉴权与区域

- 鉴权头：`Authorization: Bearer <共享密钥>`，缺失或不匹配返回 401
- 区域：`x-wb-region: cn|intl`（头或 query 均可），缺省用已登录区域，国内版优先
- 未登录区域会返回 401 并提示先在凭证页登录

错误响应统一为：

```json
{"error": {"message": "……", "type": "bridge_error", "code": 401}}
```

### 5.3 请求体改写规则

| 改写 | 原因 |
| --- | --- |
| `stream` 恒置 `true` 发给上游 | 上游只支持流式；客户端要非流式时由本地聚合，见 5.4 |
| `developer` 角色 → `system` | 上游不认识 `developer` |
| 删除 `stream_options` / `logprobs` / `top_logprobs` | 上游不接受 |
| 其余字段（含多模态内容） | 原样透传 |

### 5.4 响应

两种形态，由请求里的 `stream` 决定：

- **`stream: true`**：`Content-Type: text/event-stream`，上游字节原样透传，`[DONE]`、`usage` 帧不改写
- **`stream: false`（或缺省）**：本地把 SSE 增量聚合成单条 `chat.completion` 再返回
  `Content-Type: application/json`；`content` 拼接、`reasoning_content` 单列、
  `tool_calls` 按 `index` 归并（`arguments` 逐帧拼接）、`finish_reason` 与 `usage` 取末帧

上游错误体（非 2xx）原样回传，避免包装后丢掉上游错误码。响应头含 `X-Credits-Consumed`（本次消耗积分）。本地不缓存、不改写模型输出。

### 5.5 会话头派生（推断实现）

上游用 `X-Conversation-*` 系列头把多次请求归并成一次对话。本地 OpenAI 协议没有会话概念，因此按以下规则**推导**（非上游规定算法）：

- 会话 id：客户端显式带的 `X-Conversation-ID` 优先，否则取首条 user 消息
- 一次发送：`user 消息条数 + 最后一条 user 消息`，同一次发送的工具回灌/重试因此复用同值
- 消息 / 请求 id：按消息条数与上游调用序号递增

没有 user 消息时不发这些头——编造会话会让上游把一次对话在用量明细里拆开。

### 5.6 监听端口与共享密钥解决什么

这两个字段只在**本机回环**上生效，但作用不同，都不能省：

| 字段 | 解决的问题 | 换掉会怎样 |
| --- | --- | --- |
| 监听端口 `8765` | 决定客户端往哪个本机地址发请求；同一设备上多个本地服务靠端口区分 | 旧地址立刻失效，客户端要跟着改；端口被别的 App 占用时绑定会失败 |
| 共享密钥 `wb-local` | 门禁：本机其它 App 也能访问 `127.0.0.1`，没有密钥等于把账号额度公开给任何装在你手机上的程序 | 密钥一改，所有客户端配置里的 `Authorization` 都要同步改 |

改完必须点「应用端口与密钥」，服务会重启并重新绑定；只用旧值继续请求会得到 401（密钥错）或连接被拒（端口错）。

信息面板下方三个按钮直接写入剪贴板：**复制端点**（完整的 `http://127.0.0.1:<端口>/v1/chat/completions`）、**复制密钥**、**复制全部**（端点 + 密钥 + 模型列表 + 供应商四行）。客户端只接受 `base_url` 时，填端点去掉 `/chat/completions` 即可。

### 5.7 调用日志

`filesDir/call_log.jsonl`，一行一次调用，界面上自动每 2 秒刷新（新增 40 条在内存视图中展示）。

| 字段 | 含义 |
| --- | --- |
| `kind` | `api` 本地转发、`chat` 内置对话、`svc` 服务启停事件 |
| `status` / `duration_ms` | 上游状态码与耗时；`status=0` 表示请求在到达上游前就失败 |
| `in` / `out` / `积分` | 上游末帧的 `usage` 与响应头 `X-Credits-Consumed` |
| `note` | 失败原因、工具调用轮次等本地说明 |
| `raw` | **上游响应原文**（SSE 帧或错误体），上限 2000 字符 |

`raw` 是判断「这句话谁说的」的依据：`data: {...}` 帧是上游原样输出，`[tool] bash {...}` 这类标记是本机生成的，不在上游响应里。日志同时覆盖失败请求——401 未登录、400 请求体非法、中途断开都会留痕，不再只有成功调用才有记录。

---

## 6. 内置对话与工具

**对话** 页可直接用当前账号的模型聊天，走 [AgentClient](file:///workspace/app/src/main/java/com/wbhub/app/agent/AgentClient.kt)：

- 单轮最多 6 次工具调用循环：请求 → 流式解析 → 执行工具 → 结果回灌 → 继续
- 工具集（[AgentTools.kt](file:///workspace/app/src/main/java/com/wbhub/app/agent/AgentTools.kt)）：

| 工具 | 说明 | 限制 |
| --- | --- | --- |
| `read_file` | 读工作区文本文件 | 超过 256 KB 截断 |
| `write_file` | 覆盖写工作区文件 | — |
| `bash` | 在 `/system/bin/sh` 执行命令，工作目录为工作区 | 30 秒超时，输出截断到 8 KB |

工作区固定为应用私有目录 `files/agent-workspace`，所有路径做 canonical 校验，越界直接拒绝。

---

## 7. 保活与自动任务

| 手段 | 实现 | 说明 |
| --- | --- | --- |
| 前台服务 | `foregroundServiceType="specialUse"` + 常驻通知 | 需 `FOREGROUND_SERVICE_SPECIAL_USE`，Manifest 内已声明子类型 |
| 悬浮窗 | `TYPE_APPLICATION_OVERLAY` 小标签 | 让系统认为 App 仍可见，提升进程优先级；需用户授权 |
| 定时任务 | 服务内协程，每 6 小时 | 对每个已登录区域执行每日签到 |
| 自恢复 | `START_STICKY` | 被系统杀掉后尝试重启 |

自动任务调用：

- 签到：`POST /v2/billing/meter/daily-checkin`
- 活跃上报：`POST /api/growth/active-report` —— **该路径无上游依据，属占位实现**，失败不影响签到

界面上的操作会同时写进调用日志，便于排查。

---

## 8. 构建与发布

### 本地构建

```bash
# 需要 JDK 17+ 与 Android SDK 35
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### CI

[.github/workflows/android.yml](file:///workspace/.github/workflows/android.yml)：推送到 `main` 或手动触发 → JDK 21 + SDK 35 → `assembleDebug` → 生成 `version.json` → 自动创建 Release（tag `v<run_number>`）并上传 APK 与 `version.json`。

### 自动更新

App 每次进程启动时（API 平台页首次 `onResume`）请求
`GET https://api.github.com/repos/dickisz1/workbuddy-hub/releases/latest`：

1. 只认 Release 资产里的 `version.json`，读 `versionCode` 与本机比对，**必须严格大于本机**才提示
2. 未发现新版本：静默，不打扰；手动点「检查更新」才显示「已是最新版本（x.y.z）」
3. 发现新版本：弹窗确认 → 从同一 Release 下载 `app-debug.apk` 到 `cacheDir/updates/` → 通过 `FileProvider` 拉起系统安装器
4. 未授予「安装未知应用」时引导到系统设置页，授权后回来再点一次

两点系统限制如实说明：

- **无法静默安装**。Android 要求用户在系统弹窗里确认，App 只能把安装器拉起来
- 下载源是 `github.com`，国内网络需代理；失败时界面提示「下载失败，请检查网络或 VPN 后重试」

### 签名

`keystore/wbhub.jks` 提交在仓库里，debug 与 release 共用同一签名。

这是自动更新能覆盖安装的前提：debug 包默认用构建机自动生成的临时 keystore，CI 每次构建都不同，安装时会报「软件包与现有软件包存在冲突（签名异常）」。

| 项 | 值 |
| --- | --- |
| 别名 | `wbhub` |
| 口令 | `wbhub-local`（store 与 key 相同） |
| 证书 SHA-256 | `5B:75:B2:97:A1:9A:8E:8F:CD:69:78:94:F3:AC:F7:AB:B0:F0:F3:2E:EE:05:DF:9C:EC:61:C4:74:25:00:60:1D` |
| 有效期 | 至 2056-10-13 |

只用于自用分发自签，不构成任何信任背书；密码随仓库公开。

> 从临时签名版本迁移：**必须先卸载旧版再装 0.6.2**，否则签名冲突无法覆盖。卸载会清掉凭证，需要重新浏览器登录一次；此后签名固定，不再有这个问题。

### 工程配置

| 项 | 值 |
| --- | --- |
| applicationId / namespace | `com.wbhub.app` |
| minSdk / targetSdk / compileSdk | 26 / 35 / 35 |
| versionCode / versionName | 64 / 0.6.4 |
| Java / Kotlin JVM target | 17 |
| 主要依赖 | AndroidX Core、AppCompat、Material、Fragment、Coroutines、Security-Crypto |

---

## 9. 目录结构

```
app/src/main/
├── AndroidManifest.xml
├── java/com/wbhub/app/
│   ├── App.kt                  # 进程级单例
│   ├── MainActivity.kt         # 三页签容器
│   ├── agent/                  # 内置对话 Agent 与工具
│   ├── bridge/                 # 代理服务、前台服务、悬浮窗、日志、参数
│   ├── data/Login.kt           # 登录编排
│   ├── proto/                  # 协议常量、认证、凭证、上游客户端、自动任务
│   └── ui/                     # 三个页面的 Fragment
└── res/                        # 布局、菜单、图标、文案
```

---

## 10. 数据与隐私

| 数据 | 位置 | 是否加密 |
| --- | --- | --- |
| 令牌、uid、企业 id、domain | `EncryptedSharedPreferences`（`wbhub_credentials`） | 是 |
| 端口、共享密钥、区域、模型名 | `SharedPreferences`（`wbhub_bridge`） | 否 |
| 调用日志 | `files/call_log.jsonl` | 否 |
| Agent 工作区文件 | `files/agent-workspace/` | 否 |

- 代理只监听 loopback，不接受外部连接；共享密钥用于防止本机其他 App 随意调用
- `allowBackup="false"`，凭证不进入系统备份
- 日志最多保留 2000 行，超出自动裁剪

---

## 11. 已知限制与未验证项

| 项 | 现状 |
| --- | --- |
| 协议范围 | 仅 OpenAI Chat Completions；Responses / Anthropic / Gemini 协议未实现 |
| 非流式请求 | 一律按流式返回，客户端若强依赖非流式 JSON 会不兼容 |
| 模型元数据 | `/v1/models` 只返回 id，无上下文长度、能力、价格标记 |
| 活跃上报 | 路径 `/api/growth/active-report` 无上游依据，未验证 |
| Release 签名 | CI 只构建 debug APK，未接正式签名与混淆产物 |
| 自动化测试 | 无单元/仪器测试，验证依赖手工联调 |
| 会话头算法 | 基于推断，非上游公开规则 |

---

## 12. 版本

| 版本 | 要点 |
| --- | --- |
| 0.6.4 | 端点信息旁加「复制端点 / 复制密钥 / 复制全部」，配置客户端时不必手抄 |
| 0.6.3 | 调用日志记上游原文、失败请求也留痕、页面每 2 秒自动刷新；内置对话同样入日志 |
| 0.6.2 | 固定签名（仓库内 keystore），修复「软件包与现有软件包存在冲突」导致无法覆盖安装 |
| 0.6.1 | 内置对话补会话头；429/401 报错带上游原文与 `Retry-After`，不再只显示状态码 |
| 0.6.0 | 应用内自动更新（比对 `version.json`、下载 APK、拉起安装器）；CI 发布 `version.json` |
| 0.5.0 | `stream:false` 请求在本地把 SSE 聚合成单条 `chat.completion`，修复非流式客户端首字符解析失败 |
| 0.4.0 | 凭证改为 WorkBuddy 原生浏览器登录流程；新增 refreshToken 静默续期；模型列表按 `agents[cli].models` 解析 |
| 0.3.x | 初版本地反向代理、双区域凭证、保活与自动签到 |
