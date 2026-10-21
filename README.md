# WatchHub · 手机本机通知中枢 → 荣耀手表

一个 APK，把「定时提醒」和「网站/番剧更新」变成手表上的震动。
**不需要电脑、不需要服务器、不需要公网 IP。** 全部在手机上跑。

```
[手机 App 进程内]
 定时器 ──► 采集器(RSS/JSON/正则/指纹/定时) ──► 去重(SQLite) ──► 系统通知
                                                                    │
                                                       荣耀运动健康镜像
                                                                    ▼
                                                              [荣耀手表]
```

## 为什么是这个架构

荣耀手表（Watch 5 Pro）是 MagicOS/RTOS，**不开放第三方 App SDK**，装不了 APK。
它只能通过「手机通知镜像」显示内容 —— 这和你微信消息上手表是同一个通道。

所以手表只是个蓝牙显示器，真正干活的是手机上的这个 App。
第一版把服务端放在电脑上（Python），但那样要求电脑一直开机、要开端口、要配 token。
现在把服务端整个搬进 APK：省掉网络中间环节，也省掉所有配置。

## 下载

- **最新版**：https://github.com/MOHY-Li/honor-watch-hub/releases/latest
- **在 App 内更新**：设置里点「检查更新」→ 下载 → 系统安装器确认（会校验 SHA-256）
- **本地构建**：跑 `build.bat`，产物在 `dist/WatchHub.apk`

安装后：

1. 打开 App → 点「启动中枢」
2. 点「测试通知」，手机通知栏出现通知即 App 侧 OK
3. **荣耀运动健康 → 设备 → 消息通知 → 勾选 WatchHub**（这一步只能手动做）
4. 手机设置 → 通知 → WatchHub →「WatchHub 推送」设为**重要/允许打扰**
5. 手机设置 → 电池 → 应用启动管理 → WatchHub → **手动管理，全开**
   （App 里有按钮直接跳转）

第 3~5 步是荣耀/华为系统的硬性要求，跳不过去。第 3 步决定了通知能不能上手表。

## 五种数据源

内置配置里已经有三条示例，在 App 里点「编辑配置 (JSON)」可以直接改：

| kind | 用途 | 关键字段 |
|---|---|---|
| `rss` | RSS / Atom，最稳 | `url`, `limit` |
| `json` | 任意 JSON API | `url`, `list_path`, `fields{}`, `*_format` |
| `regex` | 从 HTML 抠条目 | `url`, `pattern`（命名组 title/body/url/key）|
| `hash` | 整页指纹，变了就报 | `url`, `title`, `body` |
| `cron` | 定时提醒（本地算，不联网）| `times[]`, `days[]` |

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

把 `subject_id` 换成你追的番（`bangumi.tv/subject/<id>` 里的数字）。
注意：Bangumi 网页版 RSS（`/rss/subject/xxx`）已失效，必须用 v0 API。

### 定时提醒

```json
{
  "name": "drink_water",
  "kind": "cron",
  "times": ["09:30", "11:30", "14:30", "16:30"],
  "days": [0, 1, 2, 3, 4],
  "title": "喝水",
  "body": "起来走两步，喝口水",
  "priority": "high"
}
```

`days`：0=周一 … 6=周日，省略=每天。
key 是 `名字+年月日时分`，所以同一分钟绝不会重复，重启也不会补发历史。

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

## 关键行为

- **首轮不刷屏**：新源第一次检查只写入去重表，不推送历史。
  需要启动即推最新一条的加 `"notify_on_first_run": true`。`cron` 源例外。
- **手表长度约束**：标题裁 40 字、正文裁 120 字。荣耀镜像对长文本支持很差。
- **同源覆盖**：用 `tag = "wh:" + 源名`，连续 10 集更新不会把手表通知栏刷满。
- **保活**：前台服务每 20 秒 tick 一次，cron 精度 20 秒内。
  另配开机自启 + 电池白名单跳转。

## 自更新怎么工作

Android 不允许应用静默装 APK（系统安全限制），所以流程是标准的：

```
检查 dist/latest.json  →  versionCode 比大小
        ↓ 有新版
DownloadManager 下载 APK
        ↓
SHA-256 + 文件大小校验   ← 不通过就删除，绝不安装
        ↓
FileProvider 暴露 URI → 拉起系统安装器 → 用户点「安装」
```

- 清单地址硬编码在 `Updater.DEFAULT_MANIFEST_URL`，指向本仓库 master 的 `dist/latest.json`
- 进 App 前台会静默检查一次（失败不打扰），也可手动点「检查更新」
- **必须校验 SHA-256**：更新包走公网，不校验等于给了别人替换 APK 的机会

发布新版本（一条命令）：

```bash
# 1. 改 android/app/build.gradle.kts 里的 versionCode（+1）和 versionName
# 2. 提交
git commit -am "feat: xxx"
git tag v1.2.0
git push origin master --tags
```

CI 会自动：跑 38 项测试 → 构建 APK → 发 GitHub Release（资产名 `WatchHub.apk`）
→ 生成 `dist/latest.json`（含真实 SHA-256）→ 提交回 master。
用户端下次打开 App 就能看到更新。

## 代码结构

```
android/app/src/main/java/dev/watchhub/app/
├─ hub/
│  ├─ Source.kt       源基类 + 工厂 + clip/fmt 工具
│  ├─ RssSource.kt    RSS/Atom（DOM 解析，关掉 XXE）
│  ├─ JsonSource.kt   JSON API + 字段映射 + 模板
│  ├─ RegexSource.kt  HTML 正则抓取
│  ├─ HashSource.kt   整页 SHA-256 指纹
│  ├─ CronSource.kt   本地定时（时分 + 星期）
│  ├─ Http.kt         极简 HTTP（JDK 自带，支持 gzip）
│  ├─ Store.kt        SQLite：通知 / 去重 / 源状态
│  ├─ SourceRepo.kt   源配置仓库（JSON 存 SharedPreferences）
│  └─ Engine.kt       调度引擎：每个源独立倒计时
├─ HubService.kt      前台服务，20s 心跳
├─ Notifier.kt        通知桥（HIGH 渠道 + 长度裁剪）
├─ update/
│  ├─ UpdateManifest.kt 更新清单解析（容错 HTML/空响应）
│  └─ Updater.kt        check / 下载 / SHA-256 校验 / 拉起安装器
├─ MainActivity.kt    状态 / 源列表 / 历史 / 日志 / 检查更新
└─ SourcesActivity.kt JSON 配置编辑（保存前校验）
```

## 构建与测试

```bat
build.bat
```

输出 `dist/WatchHub.apk`。自带 JDK 17 + Android SDK + Gradle 在 `.toolchain/`（不入库）。

测试 38 项，全部不依赖真机：

```
SourcesTest (12)        clip / 工厂 / JSON 路径 / 模板 / cron 命中与星期过滤 / 首轮语义
NotifTest (3)           通知模型解析与缺省值
HttpSourcesTest (7)     真起本地 HTTP 服务，跑通 RSS/Atom/JSON/Regex/Hash + 404 + 拒绝连接
UpdateTest (11)         清单解析边界 / SHA-256 已知向量 / 版本比较
UpdateCheckE2ETest (5)  真起 HTTP 服务跑检查逻辑（含 HTML 响应、连接失败）
```

## 已知限制

- **通知镜像取决于你的 ROM 版本**。如果运动健康的「消息通知」列表里没有 WatchHub，
  说明该系统只允许白名单应用镜像。此时需要换落地方式（发邮件到手机邮箱、
  写手机日历等），数据源和定时逻辑可以复用。
- 荣耀/华为的系统杀后台极其激进，务必手动开电池白名单；否则中枢会被冻结。
- 明文 HTTP 访问源站（多数 RSS 是 https，没问题）；没做代理支持。
- `hash` 源对动态页面会误报（广告/时间戳变化），必要时应改用 `regex` 精确抓。
