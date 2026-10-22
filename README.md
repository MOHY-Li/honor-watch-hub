# WatchHub · 手机任务中枢 → 荣耀手表

一个 Android App，把「任务提醒」和「网站/番剧更新」变成手表上的震动。
**不需要电脑、不需要服务器、不需要公网 IP。** 全部在手机上跑。

```
[手机 App 进程内]
  提醒规则 ──► 任务 ──► 待送达队列(SQLite) ──► 系统通知
  数据源   ──► 事件 ──►                    │
                                  荣耀运动健康镜像
                                            ▼
                                      [荣耀手表]
```

## 2.0 做了什么

1.x 是「通知管道」，2.0 是「任务中枢」。架构选型没变（手表不开放 SDK，只能走通知镜像），
变的是数据模型和可靠性：

| | 1.x | 2.0 |
|---|---|---|
| 数据模型 | 源 → 条目 → 通知 | **提醒 → 任务 → 送达**，任务可完成/跳过/顺延 |
| 调度 | 只有一个 20 秒前台服务线程 | **精确闹钟**（到期）+ 前台服务（轮询）+ WorkManager 看门狗 |
| 送达 | 直接发通知 | **先落库后发通知**，失败重试，进程被杀后补发 |
| 错过 | 不补发 | 补发最近一次过期实例，正文标注「迟到 · 原定 09:30」 |
| 可观测 | 一个「测试通知」按钮 | **链路体检页**：权限/渠道/闹钟/电池/服务/源/送达 |
| 兜底 | 无 | 高优先级任务可**写手机日历**，不依赖镜像白名单 |
| 界面 | 500+ 行 AGSL 玻璃渲染 | Material 3 常规控件 |

设计细节见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，平台边界见 [docs/WATCH_NOTES.md](docs/WATCH_NOTES.md)。

## 下载与安装

- **最新版**：https://github.com/MOHY-Li/honor-watch-hub/releases/latest
- **在 App 内更新**：设置里点「检查更新」→ 下载 → 系统安装器确认（会校验 SHA-256）
- **本地构建**：`scripts/build.sh`，产物在 `dist/WatchHub.apk`

安装后必须手动做这几步（荣耀/华为系统限制，App 跳不过去）：

1. 打开 App → 点「启动中枢」
2. 点「测试通知」，手机通知栏出现通知即手机侧 OK
3. **荣耀运动健康 → 设备 → 消息通知 → 勾选 WatchHub**
4. 系统设置 → 通知 → WatchHub →「WatchHub 提醒」设为**重要/允许打扰**
5. 系统设置 → 电池 → 应用启动管理 → WatchHub → **手动管理，全开**
6. 系统设置 → 应用 → 特殊权限 → 闹钟和提醒 → 允许 WatchHub（精确闹钟）

做完后打开 App 的「链路体检」，所有项都是绿色就说明链路通了；哪一项红了点进去按提示处理。

## 任务与提醒

「任务」页按 已过期 / 今天 / 之后 / 最近完成 分组。每条任务可以：

- **完成**：标记完成，本周/本月统计 +1
- **跳过**：这条不做了，不影响下一次提醒
- **稍后 10 分钟**：重置提醒，10 分钟后再响

提醒的重复规则由「星期 + 时间点」组成：选 09:30、14:30，周一至周五，就是工作日每天两次。
不选任何星期 = 每天。

**迟到的处理**：手机重启、服务被杀、离线一段时间后，重新对齐时每条提醒只补发**最近一次**
过期未送达的实例，更早的直接标成「错过」。所以离线三天不会一次炸出 12 条喝水提醒。

**超时补偿**是在 `ReminderEngine` 里做的，规则在 `core/TaskRules.plan()`（纯函数，有单测）。

## 数据源

四种采集器，全部本地解析，间隔可配（最小 60 秒）：

| kind | 用途 | 关键字段 |
|---|---|---|
| `rss` | RSS / Atom，最稳 | `url`, `limit` |
| `json` | 任意 JSON API | `url`, `list_path`, `fields{}`, `*_format` |
| `regex` | 从 HTML 抠条目 | `url`, `pattern`（命名组 title/body/url/key）|
| `hash` | 整页指纹，变了就报 | `url`, `title`, `body` |

通用字段：`name`（唯一）、`enabled`、`interval`（秒）、`priority`（high/normal/low）、
`tags`、`notify_on_first_run`、`headers{}`、`timeout`（秒）。`regex` 额外支持 `encoding`（如 `gbk`，缺省 UTF-8）。

### 番剧更新（Bangumi，实测可用）

```json
{
  "name": "bangumi",
  "kind": "json",
  "url": "https://api.bgm.tv/v0/episodes?subject_id=425998&type=0&limit=20",
  "list_path": "data",
  "limit": 20,
  "interval": 900,
  "priority": "high",
  "tags": ["anime"],
  "fields": { "title": "name_cn", "key": "id", "body": "airdate", "url": "id" },
  "body_format": "{airdate} · 第{ep}话"
}
```

把 `subject_id` 换成你追的番。Bangumi 网页版 RSS（`/rss/subject/xxx`）已失效，必须用 v0 API。

### 抢票 / 补货 / 公告页

```json
{
  "name": "restock",
  "kind": "hash",
  "url": "https://shop.example.com/item/123",
  "interval": 60,
  "title": "有货了",
  "body": "商品页变了，快去看",
  "priority": "high"
}
```

`hash` 是最万能的兜底：不关心变了什么，只想知道变了。

**首轮不刷屏**：新源第一次检查只写入去重表，不推送历史。需要启动即推最新一条的加
`"notify_on_first_run": true`。

## RSS 订阅

「订阅」页把 RSS 变成了真正的阅读入口，而不只是抓取配置：

- **添加订阅**：填网站地址或 feed 直链。填网站时自动读页面的
  `<link rel="alternate" type="application/rss+xml">` 发现 feed；发现不到会提示你换成直链。
- **文章列表**：按发布时间倒序，可按订阅源筛选、只看未读；点开看摘要，再点「打开原文」跳浏览器。
- **已读状态**：点开自动标已读，也可以「全部已读」；未读数会显示在底部导航的角标上。
- **暂停 / 取消订阅**：暂停只是停止抓取和推送，文章保留；取消会删掉这个源。

同一个 feed 条目会同时产生两种记录：一条**通知**（裁剪到 40/120 字、发到手表、参与去重）
和一条**文章**（完整摘要、带已读状态、供回看）。两者互不影响——通知发失败了文章还在，
文章已读了也不会改变历史通知记录。

## 可靠性契约

代码里不是「尽量」，是硬约束（见 [AGENTS.md](AGENTS.md)）：

1. **先落库后发通知**：提醒先以 `PENDING` 写 SQLite，发通知成功才标 `DELIVERED`。
2. **精确闹钟**：任务到期用 `setExactAndAllowWhileIdle`；没授权时降级为非精确，并在体检页告警。
3. **心跳 + 看门狗**：服务每 20 秒写心跳，WorkManager 每 15 分钟检查，超过 5 分钟没心跳就重启。
4. **重启对齐**：开机、应用升级、服务重启都会跑同一套 reconcile。
5. **不刷屏**：同来源/同提醒固定 tag 覆盖通知，同一 key 只落库一次。
6. **错过补偿**：只补最近一条，迟到必须写明原定时间。

## 链路体检

通知镜像是一条四段串联的链路：**App 通知权限 → 渠道重要性 → 运动健康镜像 → 手表**。
前两段在 App 内能自检，第三段只能确认开关，第四段手表侧无法从手机探测。体检页逐项检查：

- 通知权限、提醒渠道重要性（被调低会告警）
- 精确闹钟授权、电池优化白名单
- 中枢服务心跳是否新鲜
- 荣耀运动健康是否安装、「已确认勾选镜像」勾选状态
- 最近一次推送时间、失败/待补发的提醒数
- 连续失败 ≥3 次的数据源
- 日历权限（如果开了日历兜底）

## 日历兜底

通知镜像依赖 ROM 白名单和用户手动勾选，是最脆的一环。打开「设置 → 日历兜底」后，
高优先级提醒会同时写进手机默认日历（带 0 分钟提醒），而荣耀手表原生同步日历提醒——
这条通路不依赖镜像开关。任务完成/跳过时对应日历事件会被删掉。

## 自更新

Android 不允许静默装 APK，所以流程是标准的：

```
检查 dist/latest.json（三个候选地址）→ 比 versionCode
        ↓ 有新版
DownloadManager 下载 → 校验 SHA-256 + 大小 → FileProvider → 系统安装器
```

发布新版本：改 `versionCode`/`versionName` → `git tag v2.0.x` → push tag。
CI 会跑单测 + lint → 构建签名 APK → 发 GitHub Release → 生成清单提交回 master。

## 代码结构

```
android/app/src/main/java/dev/watchhub/app/
├─ core/            纯 Kotlin：Clock、Text、TaskRules、LogBus、模型、重复规则
├─ net/             极简 HTTP（GET/POST、headers、gzip、大小上限）
├─ source/          rss / json / regex / hash 四种采集器
├─ data/            SQLite schema + 迁移 + 各 store（alerts 即 outbox）
├─ engine/          Poller / ReminderEngine / Scheduler / Reconciler / Migrator
├─ delivery/        Notifier（唯一通知出口）/ Deliverer（outbox）/ CalendarSync
├─ service/         HubService / AlarmReceiver / BootReceiver / WatchdogWorker
├─ health/          链路体检
├─ update/          自更新
├─ ui/              MainActivity + 5 个 Fragment + 编辑页；HubUi 是唯一门面
├─ App.kt / Container.kt
```

## 构建与测试

```bash
scripts/build.sh              # 单测 + debug APK → dist/WatchHub.apk
scripts/build.sh --skip-tests # 只打包
```

需要 JDK 17+ 和 Android SDK（macOS 上脚本会自动找 Android Studio 自带的 JBR 和 `~/Library/Android/sdk`）。
单测全部纯 JVM，不依赖真机；HTTP 相关测试用本地 `com.sun.net.httpserver`。

## 从 1.x 升级

直接装 2.0 即可，首次启动会：

- 把 1.x 的 `watchhub_sources/sources_json` 迁移成新的“源 + 提醒”（`cron` 源会变成重复提醒）
- 把 1.x 的 `notifications` 历史迁成送达记录
- 保留去重表，不会把看过的旧条目重推一遍

迁移只跑一次，失败会保留原数据并写日志。

## 已知限制

- **通知镜像取决于 ROM**。如果你的运动健康「消息通知」列表里没有 WatchHub，
  说明该系统只允许白名单应用镜像，此时用「日历兜底」或改成发邮件/微信等白名单 App。
- 荣耀/华为杀后台激进，务必手动开电池白名单；否则看门狗要等 15 分钟才能补救。
- 手表侧是**单向**的：不能打勾、不能顺延。所有任务操作只能在手机上做。
- 手表只显示「标题 + 正文」两行，标题裁 40 字、正文裁 120 字，富媒体会被镜像丢掉。
- 没有登录态管理：抓不了需要 cookie 的页面，需要登录的站点请找公开端点。
- 订阅只支持 RSS / Atom（XML）；JSON Feed 会被自动发现跳过，需要时请换 RSS 地址。
- 明文 HTTP 允许（少数 RSS 源），但没做代理配置。
