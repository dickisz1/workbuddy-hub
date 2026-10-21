# 挂起：转发服务稳定性缺陷（已定位，未修）

状态：**挂起**，等确认后在 `chore/resilient-bridge` 分支一次性修完。
记录时间：2026-02（0.4.0 版本）
触发现象：客户端「测试连接」报 `Failed to connect to /127.0.0.1:8765`，而 App 内开关仍显示已启动。

## D1 绑定失败会把运行状态永久锁死（最可能的主因）

- `BridgeServer.start()` 先 `running.compareAndSet(false, true)`，**之后**才 `ServerSocket(...)`；
  绑定抛 `BindException` 时 `running` 已是 `true`。
- `BridgeService.startServer()` 用 `runCatching` 吞掉异常，但仍然执行 `server = instance`；
  下一次进入 `if (server?.isRunning == true) return`，永远早退，不再重试绑定。
- 触发路径：改端口/密钥 → `stop()` → 仅 300ms 后重启，旧监听 socket 仍在 TIME_WAIT，
  且未设置 `SO_REUSEADDR` → `EADDRINUSE`。

涉及位置：

- `app/src/main/java/com/wbhub/app/bridge/BridgeServer.kt` `start()` / `stop()`
- `app/src/main/java/com/wbhub/app/bridge/BridgeService.kt` `startServer()`
- `app/src/main/java/com/wbhub/app/ui/ApiPlatformFragment.kt` `applyConfig()`

修法：`reuseAddress = true`；绑定失败时把 `running` 复位为 false 且不把实例登记为已启动；
端口占用时退避重试若干次；重启前显式等待 accept 线程结束。

## D2 accept 线程单次异常即永久退出，但 `isRunning` 仍为 true

- `acceptLoop` 中 `runCatching { socket.accept() }.getOrNull() ?: break`：
  EMFILE、系统打断、socket 被回收都会 `break`，无重试。
- 退出后 `running` 未复位，界面继续显示“已监听”。

修法：仅当 `running == false` 才退出；其余异常按指数退避重试；加看门狗检查
`socket.isClosed` 与实际监听状态。

## D3 进程被杀后界面仍显示“已启动”

- `BridgeService.running` 是内存静态变量，进程重启归 false；
  `prefs.serviceEnabled` 仍为 true，开关读两者 `||`，于是显示开但无监听。

修法：开关状态以实际探测为准（请求 `/health`），不再读静态变量。

## D4 上游读超时设为无限

- `UpstreamClient.open()` 中 `conn.readTimeout = 0`：上游卡住时请求永久挂起，
  连接与线程堆积，后续请求进不来。

修法：SSE 保留长连接但设空闲上限（如 300s）；一次性请求恢复有限读超时。

## 待排除的环境因素

- 状态栏 VPN 常开：部分“全局抓包/防泄漏”类 VPN 会拦截 loopback。需先关 VPN 复测。
- 厂商后台清理（vivo / OPPO / 小米）可能冻结前台服务，需在电池设置中放开限制。

## 现场确认手段

```
adb shell "grep -i ':223D' /proc/net/tcp"
```

`223D` 为 8765 的十六进制。出现 `0A`（LISTEN）行说明监听正常，问题在 VPN 或客户端；
无输出说明服务确实未监听，即 D1–D3 之一。
