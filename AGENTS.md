# WatchHub 项目规则

本文件是本仓库的最高优先级工程约定。改架构、改目录、改构建方式、改可靠性策略或改开发流程时，必须同步更新本文件。

## 1. 项目定位

手机侧「提醒中枢」：手机上采集信息、生成任务、决定什么时候提醒；荣耀手表只是单向输出端。

三条平台事实决定了所有设计，不要花时间绕开它们：

- 荣耀手表（Watch 5 Pro，MagicOS/RTOS）**不开放第三方 App SDK**，装不了 APK。真正的约束是平台封闭，**不是算力**——不要保留「以后把手表端逻辑上移」的抽象。
- 到手表唯一的通路的**通知镜像**（荣耀运动健康 → 设备 → 消息通知），本质是把手机通知栏的「一行标题 + 一行正文」复制过去。
- 镜像通道是**单向**的：手表上不能打勾、不能回复、不能顺延。任何任务闭环只能发生在手机上。

因此：手机是唯一计算节点；UI 里不要出现「在手表上操作」的暗示。

## 2. 开发流程：SPEC + TDD（强制）

任何功能、修复、重构都按这个顺序走，缺一步都算没完成。这一条优先于「快点做完」。

### 2.1 先写 SPEC

- 动手前先在 `docs/specs/YYYY-MM-DD-<slug>.md` 写 SPEC，模板见 `docs/specs/TEMPLATE.md`。
- SPEC 必须写清：问题（为什么做）、用户场景、**验收标准**、非目标、影响面（模块 / 接口 / schema / 迁移）、边界与失败情况、测试计划、发布方式。
- 验收标准必须逐条可执行，并映射到测试用例；映射不上的条目要么删掉，要么补测试。
- **先定 seam（测试观察点）**：明确「从哪个公开接口观察行为」，写进 SPEC。测试只写在这些 seam 上，不测私有实现。
- 写完 SPEC，用 `grilling` skill 把假设压一遍（该 skill 不可用时，按同样的「设计树 + 每轮编号提问 + 给推荐答案」格式人工执行）：谁会用、什么情况会失效、有没有更小的版本。压不出问题再写代码；有疑问先问用户或查证事实，不要猜。
- SPEC 定稿前不写实现代码。编码中发现 SPEC 错了，先改 SPEC 再改代码。

### 2.2 TDD 循环

- 红 → 绿 → 重构，按**垂直切片**走：一个测试 → 一个最小实现 → 下一个测试。不要「先把所有测试写完再写实现」——那是在测想象中的结构。
- 测试只通过公开接口验证行为，不断言实现细节；不要为了好测去 mock 内部协作者。
- 期望值必须来自独立来源（SPEC、手工算例、已知向量），不能在测试里用同样的算法再算一遍（同义反复）。
- 已有测试变红时先修实现，不允许放宽断言或删用例来「变绿」；测试必须先真的失败过。
- 每个验收标准至少一个测试；边界必须覆盖：空 / 超长、时区、失败重试、旧数据迁移。
- **唯一例外**：纯 UI 布局与样式不写单测，但 SPEC 必须给人工验证清单（真机步骤 + 预期结果），交付说明里标注「未真机验证」。UI 里的状态映射逻辑仍应尽量下沉到 `core/` 或 `HubUi` 以便测试。

### 2.3 完成判据（缺一不可）

- 单测全绿，且用 `TZ=UTC` 再跑一遍（与 CI 时区对齐）。
- `:app:assembleDebug` 通过；`:app:lintDebug` 无 error。
- 改 schema 必须写明迁移路径与验证方式；无法真机验证时在交付说明里明确标注。
- 交付说明包含：SPEC 路径、新增/修改的测试及**实际执行结果**、未验证项、回滚方式。
- 发布前：工作区干净、本地 master 与远端同步、`versionCode` 递增、tag 指向已合入 master 的提交。

### 2.4 尺度与例外

- 拼写、文案、单行修复可以不写完整 SPEC，但必须有失败测试或明确的人工复现步骤。
- 不为可逆、低影响、只是复述实现的改动写测试（见 §7「测试规则」）。
- 发布这类不可逆动作不属于开发流程，但同样要先说明影响和回滚方式。

## 3. 架构原则

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
- `Article`（订阅文章）和 `Alert`（送达记录）是两张表：前者给手机回看/已读，后者给手表通知/去重。

## 4. 可靠性契约（不可降级）

必须同时满足，缺一项就算 bug：

1. **先落库后发通知**：`Alert` 先以 `PENDING` 写 SQLite，派发成功后才标 `DELIVERED`。两步之间进程被杀，下次启动补发。
2. **精确闹钟**：任务到期用 `AlarmManager.setExactAndAllowWhileIdle`；没有 `SCHEDULE_EXACT_ALARM` 授权时降级为 `setAndAllowWhileIdle`，并在体检页显式暴露这个降级。
3. **心跳 + 看门狗**：前台服务每 20s 写一次心跳；`WatchdogWorker`（15 分钟）发现心跳超过 5 分钟没更新，就重启服务、重排闹钟、补发 `PENDING`。
4. **重启对齐**：`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / 服务重启后都要跑一次 reconcile（补发、重排、materialize）。
5. **不刷屏**：同一来源/同一提醒用固定 tag 覆盖通知；同一个 key 的 Alert 只落库一次。
6. **错过补偿**：同一条重复提醒只补发**最近一次**过期未送达的实例，更早的标 `MISSED` 不推。迟到提醒的正文必须写明原定时间。

## 5. 通知规则（决定能不能上手表）

- 只发最朴素的 title + text，**禁止**自定义 RemoteViews / 大图 / 按钮 / 进度条。
- 标题 ≤40 字符、正文 ≤120 字符，统一走 `Text.clip()`。
- 独立渠道 `watchhub_alerts`，请求 `IMPORTANCE_HIGH`；提醒渠道上绝不能 `setSilent(true)`。
  ROM 可能只提供「默认」档——体检按「未关闭 / 未调低」判定，不以必须为高为准（见 2.2.2 SPEC）。
- 服务常驻通知用 MIN 渠道，避免被镜像。
- 通知 ID 稳定（用 alert id），tag 稳定，覆盖而不是堆叠。

## 6. 数据与迁移

- SQLite schema 变更必须写 `onUpgrade` 迁移。
- v1 的 SharedPreferences 配置（`watchhub_sources/sources_json`，含 `cron` 源）在首次启动时迁移到 v2 的 sources / reminders 表；迁移只跑一次并写日志。
- `versionCode` 单调递增，自更新靠它比较新旧。

## 7. 构建与测试

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
- 时间相关逻辑必须注入 `Clock`，禁止把 `System.currentTimeMillis()` 散落在可测逻辑里；测试里显式传时区，不依赖运行机器默认时区。
- 以下必须有测试：解析、去重、重复规则、错过补偿、清单版本比较、配置迁移映射。
- 不为纯 UI 摆样写测试（但见 §2.2 的例外条款）。

## 8. 不做的事

- 不做 AGSL / 自定义 shader 等重绘效果（v1 的 glass 模块已删除，原因见 `docs/ARCHITECTURE.md`）。
- 不做静默安装（系统禁止）。自更新必须校验 SHA-256 和文件大小。
- 不引入服务器或公网依赖；中枢离线可用（数据源自身的网络访问除外）。
- 不为「将来多设备同步」「将来手表变强」提前抽象。
