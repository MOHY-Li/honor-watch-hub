# 参考 FlClash 的技术选型（调研记录）

调研对象：[chen08209/FlClash](https://github.com/chen08209/FlClash)
（57k stars，多平台代理客户端，ClashMeta/mihomo 内核）

数据来源：仓库 `pubspec.yaml`、`build.yaml`、`android/` 模块、`.agents/architecture.md`、
`.github/workflows/build.yaml`、`tool/bump_version.sh`。非记忆推测。

## 一、FlClash 的技术栈

| 层 | 技术 |
|---|---|
| UI | **Flutter / Dart**（Material 3 + `material_ui` 包）|
| 状态管理 | **Riverpod 3** + `riverpod_generator`，`@Riverpod(keepAlive: true)` 管理长生命周期 |
| 数据模型 | **freezed** + `json_serializable`（不可变 + 生成序列化）|
| 本地库 | **drift**（SQLite 的类型安全封装）|
| 网络 | **dio**（不是 http 包）|
| 内核 | **Go**（ClashMeta/mihomo），Android 用 `gomobile bind` 成 AAR |
| 原生桥 | Android MethodChannel + 自研 `plugins/service.dart` |
| 桌面 IPC | **Rust**（`rust_api`，Unix socket / Windows named pipe）|
| 协议定义 | Go 侧 `core/protocol.go` 与 Dart 侧共享方法名/事件类型 |
| 打包 | 自研 `dart setup.dart <platform>`，Flutter build hooks 自动编内核 |
| CI | GitHub Actions，Flutter/Go/NDK 版本锁死 |

**关键结论：FlClash 不是纯 Flutter 客户端，而是「Dart UI + 原生内核」的分层架构。**
所有真正危险/高性能的活（网络、TUN、DNS）都在 Go 里，Dart 只做界面和编排。

## 二、它做对的六件事（值得我们抄）

### 1. 用 Riverpod 而不是 setState + 满屏回调

```dart
@Riverpod(keepAlive: true)
class UpdatingAction extends _$UpdatingAction {
  Timer? _sweepTimer;
  @override
  void build() { ref.onDispose(_cancelSweep); ... }
}
```

`keepAlive: true` 让长任务（下载、配置更新）跨页面存活。
我们的 `Engine` 目前是每次 `new Engine(this)`，多实例会互相覆盖状态——
这是需要修的点。

### 2. 长任务有「卡住」的兜底超时

```dart
const updatingSweepInterval = Duration(seconds: 30);
const updatingStaleTimeout = Duration(minutes: 3);
// Core 若无响应，3 分钟后强制丢弃 updating 状态
```

网络请求挂住不返回是真实存在的。我们现在的 `Http` 只有 20s readTimeout，
但没有「整个任务卡死」的兜底，也没在 UI 上体现。可以借鉴这个 sweep 机制。

### 3. 代码生成隔离到 generated/ 目录

```yaml
# build.yaml
freezed:
  options:
    build_extensions:
      '^lib/models/{{}}.dart': 'lib/models/generated/{{}}.g.dart'
```

生成物不混在源码旁边。我们 Kotlin 侧没有代码生成，但如果以后加 Room/Proto，
应该照这个约定。

### 4. 版本号 =语义版本 + 日期构建号

```bash
# tool/bump_version.sh
major  0.8.94 -> 0.8.95
minor  build number: YYYYMMDDNN（当天第 N 次构建）
```

`0.9.1+2026101702` = 语义版本 0.9.1 + 2026-10-17 第 2 次构建。
比纯自增 `versionCode` 信息量大得多，且天然单调递增。

### 5. CI 缓存与并发控制

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ !startsWith(github.ref, 'refs/tags/v') }}
```

- 同分支新提交取消旧构建（省 CI 时间）
- **但 tag 构建绝不取消**（发布不能被打断）

我们现在的 workflow 没有这个，手动触发两次会并发跑。

### 6. 更新机制：桌面自动替换，Android 走系统安装器

FlClash 的做法和我们的判断一致：
- **桌面**：能直接替换可执行文件，所以可以静默更新
- **Android**：受系统限制，必须 `ACTION_VIEW` 拉起安装器让用户确认

我们对 Android 的实现（DownloadManager + SHA-256 + FileProvider + 安装器）是正确的，
**额外做对的一点**：我们强制校验 SHA-256，FlClash 的 Android 路径依赖 GitHub
HTTPS 传输安全，没有独立校验。我们的做法更保守，对「更新包可能被中间人替换」这个
威胁模型更稳妥。

## 三、不该照抄的地方

### 1. Flutter 对我们不合适

FlClash 用 Flutter 是因为要覆盖 Windows/macOS/Linux/Android/iOS 五个平台。
我们只做 Android 单平台，且核心需求是「前台服务 + 系统通知 + 定时调度」——
这些全是 Android 原生 API，Flutter 只会加一层 MethodChannel 负担和 30MB+ 体积
（我们当前 APK 5.6MB）。

**结论：继续用 Kotlin 原生。**

### 2. Go 内核不适用

FlClash 的 Go 内核是为了跑 mihomo 代理协议栈。我们的「采集器」逻辑
（HTTP + JSON/XML 解析 + 字符串匹配）用 Kotlin 标准库就够，且更好调试。

### 3. 不要引入 dio

Android 侧 dio 对应的是 OkHttp。我们的 `Http.kt` 只有
「GET 一个 URL 读全部字节」一种形态，用 JDK 自带 `HttpURLConnection` 足够。
引入 OkHttp 会加约 1MB 且没有真实收益。

## 四、按优先级落地的改进

### P0 — 已完成 ✅

1. **Engine 单例化**
   ~~`MainActivity.refresh()` 每次都 `Engine(this)` 新建实例，状态丢失。~~
   已改为 `Engine.get(ctx)` 进程级单例（`synchronized` 双重检查）。
   `HubService` 与 `MainActivity` 现在共享同一份 `lastRun` / `inFlight`。

2. **任务卡死兜底**
   `busy: HashSet` 已换成 `inFlight: HashMap<String, Long>`（记录开始时间）。
   同一源超过 **3 分钟** 未返回，下一次 tick 会强制重试并打日志，
   避免单个源卡住让整个调度瘫痪。对标 FlClash 的 `updatingStaleTimeout`。

### P1 — 已完成 ✅

3. **CI 加 concurrency 控制**（tag 不取消）— 已加上。

### P1 — 待做

4. **版本号改 `1.2.0+2026102101` 形式**，`build.bat` 支持 `--bump`

### P2 — 可选

5. 源配置的正则/JSON 路径加「试运行」预览（对标 FlClash 的 `OverrideView` 校验）
6. 把生成类文件（如果有）隔离到 `generated/`

## 五、一句话总结

FlClash 最值得学的不是 Flutter，而是：
**「把不可靠的长任务做成有状态、有超时、有单例生命周期的一等公民」**。

这三点我们已补齐两点（单例 + 超时），并加了 8 项 `ScheduleSemanticsTest` 锁住语义。

## 六、验证记录

```
总计 46 项测试全部通过
  SourcesTest            12
  NotifTest               3
  HttpSourcesTest         7
  UpdateTest             11
  UpdateCheckE2ETest      5
  ScheduleSemanticsTest   8   ← 本次新增
```
