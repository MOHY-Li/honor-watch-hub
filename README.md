# WatchHub · 让荣耀手表收到你自己定义的通知

> 目标：**定时提醒** + **服务联动**（网站更新 / 番剧更新 / 任意变化），最终变成手表上的一次震动。
> 荣耀手表（Watch 5 Pro）不开放第三方 App SDK，所以正确解法不是「在手表上写 App」，
> 而是「**自建服务端 → 手机 App 发系统通知 → 荣耀运动健康镜像到手表**」。

```
┌──────────────┐   变化检测    ┌──────────────┐   SSE 长连接   ┌──────────────┐  系统通知  ┌──────────┐
│ 网站 / RSS /  │ ───────────▶ │  WatchHub     │ ────────────▶ │ Android App  │ ─────────▶ │ 荣耀手表  │
│ 番剧 / 定时器 │   轮询抓取    │  Server       │   实时推送     │ (前台服务)   │  镜像通知  │ Watch 5  │
└──────────────┘               └──────────────┘                └──────────────┘            └──────────┘
```

## 仓库结构

```
honor-watch-hub/
├─ server/                  零第三方依赖的 Python 通知中枢
│  ├─ hub/sources.py        5 种采集器：rss / json / regex / hash / cron
│  ├─ hub/store.py          SQLite 去重 + 历史
│  ├─ hub/broker.py         SSE 广播
│  ├─ hub/scheduler.py      每源一线程，按 interval 轮询
│  ├─ hub/server.py         标准库 HTTP + SSE（无 FastAPI/Flask）
│  └─ tests/                冒烟测试 + cron 单元测试
├─ android/                 Kotlin 客户端（前台服务 + SSE + 通知桥）
├─ docs/PROTOCOL.md         手机端 ↔ 服务端的唯一契约
└─ deploy/                  示例配置 / systemd / Docker
```

## 快速开始

### 1. 起服务端

```bash
cd server
python -m hub.main --port 8787 --data ../data --config ../deploy/sources.yaml
# 首次启动会自动生成 token 并写入 data/token.txt
```

零依赖：只要求 Python 3.9+，不需要 pip install 任何东西。

验证：

```bash
curl http://127.0.0.1:8787/health
TOKEN=$(cat data/token.txt)
curl -X POST http://127.0.0.1:8787/api/v1/notify \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"你好","body":"来自 WatchHub"}'
```

### 2. 装手机 App

- 用 Android Studio 打开 `android/`，或命令行 `cd android && ./gradlew assembleDebug`
- 安装后填 **服务器地址**（同局域网用 `http://电脑IP:8787`）和 **token**
- 点「测试连接」→「开始连接」→「本地测试通知」
- 手机通知栏出现通知即 App 侧 OK

### 3. 让通知上手表（关键）

荣耀手表走的是「手机通知镜像」，不是独立 App：

1. 手机蓝牙连好手表，打开 **荣耀运动健康**
2. **设备 → 消息通知** → 找到 **WatchHub** → 打开
3. 手机 **设置 → 电池 → 应用启动管理** → WatchHub 设为「手动管理」并全开
4. 手机 **设置 → 通知 → WatchHub** → 「WatchHub 推送」设为**重要/允许打扰**
5. 手表端：**设置 → 通知 → 开启**

> 经验：荣耀/华为的镜像对「长文本 + 图片 + 按钮」支持很差。
> 本项目服务端已把 title 裁到 30 字、body 裁到 120 字，就是为了让手表能完整显示。

## 配置一个「番剧更新」

Bangumi 的网页 RSS（`/rss/subject/xxx`）现在返回 404，官方可用的是 v0 API。
本项目用 `json` 采集器接它（`deploy/sources.yaml` 里已配好，实测通过）：

```yaml
bangumi:
  kind: json
  url: https://api.bgm.tv/v0/episodes?subject_id=425998&type=0&limit=20
  list_path: data
  limit: 20
  interval: 900
  priority: high
  tags: [anime, 番剧]
  fields:
    title: name_cn            # 字段映射：通知标题取这一列
    key: id                   # 去重主键
    body: airdate
    url: id
  # 也可以用模板组合多个字段：
  # body_format: "{airdate} · 第{ep}话"
```

把 `subject_id=425998` 换成你追的番（bangumi.tv/subject/<id> 里的数字）。

> **首轮不会刷屏**：任何源第一次检查只「播种」去重表，不推送历史。
> 想让某个源一启动就推最新一条，加 `notify_on_first_run: true`。
> `cron` 源不受影响——定时提醒命中了就该响。

重启服务即可。也可以在运行时：

```bash
curl -X POST http://HOST:8787/api/v1/sources/bangumi/check -H "Authorization: Bearer $TOKEN"
curl -X POST http://HOST:8787/api/v1/sources/bangumi -H "Authorization: Bearer $TOKEN" \
     -H "Content-Type: application/json" -d '{"enabled":false}'
```

## 配置一个「定时提醒」

```yaml
drink_water:
  kind: cron
  times: ["09:30", "11:30", "14:30", "16:30", "20:00"]
  days: [0, 1, 2, 3, 4]     # 周一至周五；省略=每天
  title: 喝水
  body: 起来走两步，喝口水
  priority: high
  tags: [health]
```

`sources.py` 里 cron 的 key 是 `名字+YYYYMMDDHHMM`，所以同一分钟绝不会重复提醒，
服务重启也不会补发历史提醒。

## 五种采集器

| kind | 用途 | 关键配置 |
|---|---|---|
| `rss` | RSS/Atom，最稳 | `url`, `limit` |
| `json` | 任意 JSON API | `url`, `list_path`, `fields{}` |
| `regex` | HTML 抓条目 | `url`, `pattern`（命名组 title/body/url/key）|
| `hash` | 整页指纹，变了就报 | `url`, `title`, `body` |
| `cron` | 定时提醒 | `times[]`, `days[]` |

`hash` 是最万能的兜底：「我不关心它变了什么，只想知道它变了」。
适合抢票、补货、公告页、论文录用名单这种无结构页面。

## 为什么不直接写手表 App

- 荣耀手表（MagicOS/RTOS）**没有公开的第三方应用 SDK**，也没有类似 Wear OS 的独立 APK 安装通道
- 官方可自定义的只有**表盘**（表盘开放平台）
- 所以社区通用做法就是本项目这套：**通知镜像**

如果哪天你只想要「表盘上显示服务器状态」，那需要走表盘开发（设计稿 + 上传审核），
和本项目的代码链路是独立的，可以再单独做。

## 测试

一键（Python + Android 单测 + 构建 APK）：

```bat
test-all.bat
```

```bash
./test-all.sh
```

分项：

```bash
python server/tests/run_all.py            # 跑下面三个
python server/tests/test_smoke.py         # 起真服务：health/鉴权/notify/SSE/补发/去重
python server/tests/test_cron.py          # cron 命中逻辑 + 首轮播种语义
python server/tests/test_live_pipeline.py # 真实 RSS 站点 → 轮询 → 去重 → SSE 全链路

cd android && ./gradlew :app:testDebugUnitTest
# SseParserTest            SSE 行协议边界（多行 data / 心跳 / CRLF / 连续事件）
# NotifTest                模型解析与缺省值
# SseProtocolFixtureTest   用「真实服务端录下的字节流」做协议一致性守门
```

协议 fixture 是自动生成的：改了服务端 SSE 格式后重录一次，Kotlin 测试会立刻校验：

```bash
python server/tests/record_sse_fixture.py
```

## 项目自带的一键验证

```
$ test-all.bat
========== Python server tests ==========
ALL 3 SUITES PASSED
========== Android unit + protocol tests ==========
BUILD SUCCESSFUL
========== Android build APK ==========
BUILD SUCCESSFUL
ALL PASSED. APK: android\app\build\outputs\apk\debug\app-debug.apk
```

## 已知限制

- SSE 在 Android 上会被 Doze 影响；本项目用前台服务 + 90s 限时 wakelock 缓解，
  但仍建议把 App 加入电池白名单（App 里点状态文字可跳转）
- 服务端 token 走明文 HTTP。**只建议在局域网/内网使用**；要暴露公网请套
  Caddy/Nginx 的 TLS，客户端把地址改成 `https://`
- 荣耀运动健康对不同 App 的通知镜像策略可能随版本变化，若手表不响，
  先用「本地测试通知」确认是手机侧还是镜像侧的问题
