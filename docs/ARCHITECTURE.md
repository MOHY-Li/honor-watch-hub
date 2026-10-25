# WatchHub 2.0 架构

## 一句话

手机是唯一的计算与决策节点，手表是单向显示器。v2 把 v1 的「通知管道」升级成「任务中枢」，并补上 v1 缺的可靠性层。

## 为什么重写

v1 的架构选型是对的（手机采集 + 通知镜像），但产品定义没到位：

| 问题 | v1 | v2 |
|---|---|---|
| 数据模型 | `源 → Item → 通知`，没有任务状态 | `Reminder → Task → Alert`，任务有完成/跳过/顺延 |
| 调度 | 只有一个 20s 前台服务线程 | 精确闹钟（到期）+ 前台服务（轮询）+ WorkManager 看门狗 |
| 送达 | 直接 `notify()` | 先写 outbox（PENDING），派发成功才 DELIVERED，可补发 |
| 错过 | 明确不补发 | 补发最近一次过期实例，标题标注「迟到」 |
| 链路可观测 | 只有一个「测试通知」按钮 | 体检页：权限/渠道/精确闹钟/电池/服务/源失败/送达 |
| 兜底通道 | 无 | 高优先级任务可写手机日历（手表原生同步日历提醒） |
| 配置 | SharedPreferences JSON | SQLite；v1 配置自动迁移 |
| UI | 500+ 行 AGSL 玻璃渲染 | Material 3 常规控件 |
| HTTP | 仅 GET | GET/POST + 自定义 headers + 响应大小上限 |

## 分层

```
ui/          Activity / Fragment / Adapter，只通过 HubUi 门面拿数据
health/      链路体检（纯规则 + Android 探针）
delivery/    Notifier（唯一通知出口）、Deliverer（outbox 派发）、CalendarSync
engine/      Poller（源轮询）、ReminderEngine（任务生成/到期/补偿）、Scheduler（闹钟）、Migrator、
             HealthEngine（指标采样+阈值规则）、HealthAnalyst（AI 分析编排）
source/      rss / json / regex / hash 四种采集器 + DeepSeekClient（AI 分析协议端）
store/       SQLite：alerts / seen / sources / source_state / reminders / tasks / articles / settings /
             metrics / health_rules / health_reports
net/         极简 HTTP（GET/POST、headers、gzip、大小上限）
core/        纯 Kotlin：模型、时间、重复规则、文本裁剪、日志、退避、健康规则判定
```

依赖只允许向内：`ui → health/delivery/engine/store/core`，`source → net/core`。`core` 不依赖 Android。

## 页面职责

一次信息只在一个页面负责，避免同一个 RSS 有两个入口：

| 页面 | 职责 | 不该做的事 |
|---|---|---|
| 健康 | 身体域：指标 + 健康规则 + AI 分析/报告 | 不放与身体无关的开关 |
| 任务 | 时间域：概览卡（下次提醒/链路状态）+ 提醒规则 + 任务执行 | 不混健康规则（在健康页） |
| 订阅 | 内容域：RSS 阅读 + 添加订阅 + RSS 源管理 + 高级源入口 | 不重复实现规则编辑（复用源管理页） |
| 源管理（二级页） | 高级监控源配置（json/regex/hash） | 不做阅读，不列 RSS（RSS 只在订阅页管理，避免双入口） |
| 设置 | 配置与状态：中枢开关/源状态、自动化、AI 分析、备份恢复、诊断、关于 | 不放无法验证的勾选项 |

导航是声明式注册的（`MainActivity.TABS`，学 FlClash 0.9 的 NavigationItem）：
加页签只改一处表 + menu xml。页面间即时刷新走 `core/ChangeBus`（写操作 →
`changes.post()` → 已打开页面在主线程回调），onResume 拉取仍是兜底。

## 数据模型

```
Reminder  重复规则：times[] + days[] + 优先级，editable
   │ materialize(horizon = 7d)
   ▼
Task      一次具体到期：dueAt + status(OPEN/DONE/SKIPPED/MISSED) + snoozedUntil
   │ due(now)
   ▼
Alert     一次送达：key + title + body + deliveryState(PENDING/DELIVERED/FAILED)
```

- **Feed 事件**（RSS/JSON/正则/指纹）跳过 Task 层，直接产生 Alert：它们没有「完成」语义。
- **健康指标**（v2.5 新增 `metrics` 表）同样没有「完成」语义：`HealthEngine` 每 5 分钟采样
  （读取端 `HealthMetricsReader`，荣耀 Health Kit 资格过审前为「未接入」占位），
  `core/HealthRuleEngine` 纯函数判定「窗口内持续越限」→ 生成 `kind=HEALTH` 的 Alert。
  判定带 15 分钟新鲜度门槛（手表摘掉不误报）与冷却期（持续异常不刷屏）。
- **AI 分析**（`HealthAnalyst` + `source/DeepSeekClient`）：把最近 7 天指标的**聚合统计**
  （不上传原始样本）发给 DeepSeek 生成「整体情况 + 建议」，报告存 `health_reports`（保 20 份）。
  手动按钮 + 每周自动；分析在独立线程，失败不影响任何核心能力——它与 RSS 抓取同属
  「数据源自身的网络访问」，不违反离线可用原则。
- **Article** 是独立的阅读记录（v3 新增 `articles` 表）：同一个 feed 条目同时写 Alert 和 Article。
  Alert 负责「送到手表」，Article 负责「手机上回看 + 已读」。两者生命周期不同，不要合并。
  `UNIQUE(source, key)` 保证幂等。
- `alerts` 同时是 outbox：`state=PENDING` 就是待补发。
- `tasks` 有 `UNIQUE(reminder_id, due_at)`，materialize 幂等。

### 重复规则

`times` 是本地时间点（`HH:mm`），`days` 是 ISO 星期（0=周一…6=周日，空=每天）。
`Recurrence.nextAfter(now)` 用纯函数计算下一次到期，按本地时区解释，通过 `Clock` 注入，单测覆盖跨天/跨周/夏令时边界（安卓默认时区下不出现 DST 跳变也测）。

### 调度与升级链（v2.3）

- `ScheduleMode.TIMES` 沿用 Recurrence；`INTERVAL` 每 N 分钟；`CRON` 用 `CronExpression` 解析 5 段表达式。
- 每次提醒写入 `Task.alertedAt`，并开始升级链（`escalationLevel=0`、`chainStartedAt=now`）。
- 未确认：`alertedAt + EscalationPolicy.intervalFor(level)` 到点再提醒，level+1（20/15/10/5/1，之后 1 分钟）。
- 确认（完成/跳过/稍后）结束链；间隔模式确认后从确认时间 + 基础间隔安排下一次。
- 跨天重置链；cron 的下一次触发会自动插入并让旧链标记 MISSED。
- 确认只来自手机端；手表无回执，这是平台事实不是实现取舍。

### 错过补偿

服务重启或看门狗唤醒时 reconcile：
- 每条 reminder 的过期 OPEN task 里，只把**最近一条**变成迟到 Alert（正文 `迟到 · 原定 09:30`）；
- 更早的置 `MISSED`，不推；
- 这样手机离线三天后不会一次炸出 12 条喝水提醒。

## 可靠性层

```
AlarmManager(精确) ──到期──► AlarmReceiver ──► ReminderEngine.due() ──► Alert(PENDING)
                                                      │
Foreground HubService(20s) ──► Poller ──► Alert(PENDING) ──► Deliverer ──► Notifier
                                                      ▲
WorkManager(15m) ──► Watchdog: 心跳过期? ──► 重启服务 + 重排 + reconcile + 补发
```

- **心跳**写在 settings（`heartbeat_at`），看门狗阈值 5 分钟。
- **精确闹钟**是主调度；前台服务只负责「源轮询」这种分钟级、非硬实时的活。
- **源失败退避**（`core/Backoff`，纯函数）：连续失败 ≥3 次的源，轮询间隔每次翻倍、封顶 6 小时
  （本来就慢的源不会被加速）；一次成功即恢复。手动「立即检查」不受限。
  目的：被墙/挂掉的源不再按原间隔空转耗电，状态可见「已降频」。
- `SCHEDULE_EXACT_ALARM` 未授权时降级为非精确并体检告警，不静默失败。
- 所有入口（boot、包更新、手动启动、看门狗）都调用同一个 `Reconciler`，避免逻辑分叉。

## 为什么删掉 glass UI

v1 用 AGSL shader 做液态玻璃（GlassCard/FloatingDock/GlassShader，约 500 行 + 一台渲染管线）。
它不承担任何产品功能，却是整个仓库最难维护、最易在低端机掉帧的部分。v2 用 Material 3 常规控件，把预算换到可靠性上。想练手可以另开分支，不要合并回主线。

## 非目标

- 手表端 App / 表盘（平台不开放，另走表盘开放平台）。
- 云端同步、多设备（本机中枢不需要）。
- 静默安装、绕过系统通知权限。
- 抓取需要登录态的站点（无 cookie 管理，需要时用可访问的公开端点）。
