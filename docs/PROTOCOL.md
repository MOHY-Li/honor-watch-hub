# WatchHub 协议 v1

手机 App 与自建服务端之间的唯一契约。**任何一端改动都必须同步本文件。**

默认 Base URL: `http://<host>:8787/api/v1`

## 认证
三种等价写法（任选其一）：

- `Authorization: Bearer <token>`
- `X-Token: <token>`
- `?token=<token>`  ← 仅给 EventSource / 无法设置 header 的客户端用

失败返回 `401` + `{"error":"unauthorized"}`。

## 数据对象

### Notification
``json
{
  "id": 128,
  "ts": "2026-02-11T09:30:00+08:00",
  "title": "进击的巨人 第12话",
  "body": "已更新 · bangumi",
  "detail": "完整正文（手机端可展开，可空）",
  "priority": "low | default | high",
  "tags": ["anime"],
  "url": "https://example.com/ep12",
  "source": "bangumi",
  "key": "bangumi:ep:12345"
}
``

**手表可见性约束（重要）**：通知最终靠「手机通知 → 荣耀运动健康镜像到手表」投递。
- `title` ≤ 30 字符、`body` ≤ 120 字符（服务端已截断，客户端不要再拼长）。
- 不要依赖 emoji/图片/按钮，镜像后大概率丢失。

### SourceStatus
``json
{
  "name": "bangumi",
  "kind": "rss",
  "enabled": true,
  "interval": 600,
  "last_check": "2026-02-11T09:25:00+08:00",
  "last_ok": "2026-02-11T09:25:03+08:00",
  "last_error": null,
  "ok": true,
  "items_seen": 412,
  "new_last_run": 0,
  "next_check": "2026-02-11T09:35:00+08:00"
}
``

## 端点

### GET /health
``json
{"ok": true, "version": "1.0.0", "uptime": 3600, "now": "...", "sources_total": 3, "sources_ok": 3, "last_notification": "..."}
``
无需 token，用于 App 的「连接测试」和探活。

### GET /events  (Server-Sent Events)
长连接，保持心跳。

- 连接后立刻下发一条 `event: hello`，`data` 为 `{"server":"1.0.0","now":"...","cursor":128}`
- 之后每条通知：`event: notification`，`id: <id>`，`data: <Notification JSON>`
- 心跳：每 15s 一行 `: ping`（已注释，客户端忽略即可）
- 支持断线续传：重连时带 `Last-Event-ID` header 或 `?last_id=128`，服务端补发 id > last_id 的通知
- 服务端保留最近 200 条用于补发

### POST /notify
手动推送（测试/联动用）。
请求：`{"title":"...", "body":"...", "detail":"", "priority":"default", "tags":[], "url":""}`
返回：`201 {"id": 129}`

### GET /notifications?limit=50&before_id=0
历史，按 id 倒序。
返回：`{"items":[Notification...]}`

### GET /sources
返回：`{"items":[SourceStatus...]}`

### POST /sources/{name}/check
立即检查某个源（异步触发，立即返回 `202 {"queued":true}`）。

### POST /sources/{name}  (仅支持 enabled 开关)
`{"enabled": false}` → `200 {"name":"bangumi","enabled":false}`

## 客户端行为要求

1. App 用 SSE 常驻（前台 Service），断线指数退避重连（1s→60s，带 jitter）。
2. 收到 notification 后立即 `NotificationManager.notify(id)`，用**独立通知渠道**（IMPORTANCE_HIGH）。
3. 相同 `id` 的重复事件要忽略（去重表存最近 500 个 id）。
4. 屏幕关闭不影响：Service 用 `startForeground` + `PARTIAL_WAKE_LOCK` 仅在连接等待时持有。
5. 若 SSE 连续失败超过 5 次，降级为每 5 分钟轮询 `/notifications?limit=20`（WorkManager）。
``
