# WatchHub 项目规则

本文件是本仓库的最高优先级工程约定。改架构、改目录、改构建方式或改可靠性策略时，必须同步更新本文件。

## 1. 项目定位

手机侧「提醒中枢」：手机上采集信息、生成任务、决定什么时候提醒；荣耀手表只是单向输出端。

三条平台事实决定了所有设计，不要花时间绕开它们：

- 荣耀手表（Watch 5 Pro，MagicOS/RTOS）**不开放第三方 App SDK**，装不了 APK。真正的约束是平台封闭，**不是算力**——不要保留「以后把手表端逻辑上移」的抽象。
- 到手表唯一的通路的**通知镜像**（荣耀运动健康 → 设备 → 消息通知），本质是把手机通知栏的「一行标题 + 一行正文」复制过去。
- 镜像通道是**单向**的：手表上不能打勾、不能回复、不能顺延。任何任务闭环只能发生在手机上。

因此：手机是唯一计算节点；UI 里不要出现「在手表上操作」的暗示。

## 2. 架构原则

依赖方向只允许：

```
ui → health / delivery / engine / store / core
service → engine / delivery / store / core
source → net / core
engine → store / source / delivery / core
delivery → store / core
```

- `core/` 是纯 Kotlin，不依赖 Android（时间通过 `Clock` 注入）。所有可测逻辑都放这里。
- 采集（`source/`）只产出 `FeedItem`，不直接发通知。
- 三者分离：`Reminder`（重复规则）→ `Task`（一次具体到期）→ `Alert`（一次送达）。Feed 事件只产生 `Alert`，没有完成语义。
- 数据库是唯一事实来源：任何状态都要能在进程被杀后恢复。

## 3. 可靠性契约（不可降级）

必须同时满足，缺一项就算 bug：

1. **先落库后发通知**：`Alert` 先以 `PENDING` 写 SQLite，派发成功后才标 `DELIVERED`。两步之间进程被杀，下次启动补发。
2. **精确闹钟**：任务到期用 `AlarmManager.setExactAndAllowWhileIdle`；没有 `SCHEDULE_EXACT_ALARM` 授权时降级为 `setAndAllowWhileIdle`，并在体检页显式暴露这个降级。
3. **心跳 + 看门狗**：前台服务每 20s 写一次心跳；`WatchdogWorker`（15 分钟）发现心跳超过 5 分钟没更新，就重启服务、重排闹钟、补发 `PENDING`。
4. **重启对齐**：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / 服务重启后都要跑一次 reconcile（补发、重排、materialize）。
5. **不刷屏**：同一来源/同一提醒用固定 tag 覆盖通知；同一个 key 的 Alert 只落库一次。
6. **错过补偿**：同一条重复提醒只补发**最近一次**过期未送达的实例，更早的标 `MISSED` 不推。迟到提醒的正文必须写明原定时间。

## 4. 通知规则（决定能不能上手表）

- 只发最朴素的 title + text，**禁止**自定义 RemoteViews / 大图 / 按钮 / 进度条。
- 标题 ≤40 字符、正文 ≤120 字符，统一走 `Text.clip()`。
- 独立渠道 `watchhub_alerts`，`IMPORTANCE_HIGH`；提醒渠道上绝不能 `setSilent(true)`。
- 服务常驻通知用 MIN 渠道，避免被镜像。
- 通知 ID 稳定（用 alert id），tag 稳定，覆盖而不是堆叠。

## 5. 数据与迁移

- SQLite schema 变更必须写 `onUpgrade` 迁移。
- v1 的 SharedPreferences 配置（`watchhub_sources/sources_json`，含 `cron` 源）在首次启动时迁移到 v2 的 sources / reminders 表；迁移只跑一次并写日志。
- `versionCode` 单调递增，自更新靠它比较新旧。

## 6. 构建与测试

本地（macOS，已验证可用）：

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

产物在 `android/app/build/outputs/apk/debug/app-debug.apk`。`scripts/build.sh` 是上面命令的封装。

测试规则：

- 单测必须纯 JVM、不依赖真机；HTTP 测试用 `dev.watchhub.app.testing.TestHttpServer`（裸 ServerSocket 实现，不依赖 `com.sun.net.httpserver`——AGP 编译单测时传 `-no-jdk`，classpath 里没有它）。
- 时间相关逻辑必须注入 `Clock`，禁止把 `System.currentTimeMillis()` 散落在可测逻辑里。
- 以下必须有测试：解析、去重、重复规则、错过补偿、清单版本比较、v1 配置迁移映射。
- 不为纯 UI 摆样写测试。

## 7. 不做的事

- 不做 AGSL / 自定义 shader 等重绘效果（v1 的 glass 模块已删除，原因见 `docs/ARCHITECTURE.md`）。
- 不做静默安装（系统禁止）。自更新必须校验 SHA-256 和文件大小。
- 不引入服务器或公网依赖；中枢离线可用（数据源自身的网络访问除外）。
- 不为「将来多设备同步」「将来手表变强」提前抽象。
