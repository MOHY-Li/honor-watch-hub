# 玻璃效果 + 悬浮切换栏：从 FlClash 移植到 Android 原生

## 一、FlClash 是怎么做的（源码级）

| 文件 | 作用 |
|---|---|
| `shaders/liquid_glass.frag` | **核心**：GLSL 片元着色器，做折射/色散/边缘光 |
| `lib/ui/liquid_glass.dart` | 样式参数 `LiquidGlassStyle` + 渲染 Widget |
| `lib/ui/liquid_glass_capture.dart` | 抓取背景作为着色器输入 |
| `lib/ui/navigation_dock.dart` | 悬浮胶囊导航栏（约 1500 行，物理动画极细）|

关键机制：
1. **背景捕获** —— 用 `RepaintBoundary` 把背后内容渲成纹理
2. **折射** —— 着色器在圆角矩形边缘把采样点向中心偏移，模拟光线穿过厚玻璃
3. **色散** —— RGB 三通道用不同折射率，边缘出现彩虹
4. **边缘光 (rim)** —— 沿边缘一条向内衰减的亮度带，模拟玻璃厚度
5. **物理动画** —— 弹簧 (`SpringDescription`) 驱动选中滑块，带 jelly/squash

## 二、Android 原生怎么等价实现

Flutter 有 `FragmentShader` + `BackdropFilter`；Android 对应的是
**AGSL `RuntimeShader`（API 33+）**。两者同属 SkSL 家族，语法非常接近，
所以着色器可以近乎原样移植。

| FlClash (Flutter) | WatchHub (Android) |
|---|---|
| `liquid_glass.frag` (GLSL) | `res/raw/liquid_glass.agsl` (AGSL) |
| `FragmentShader` | `android.graphics.RuntimeShader` |
| `LiquidGlassStyle` | `GlassStyle` (data class) |
| `BackdropFilter` | `RenderNode` 抓背景 → `BitmapShader` 作为 `uBackdrop` |
| `SpringDescription` | `OvershootInterpolator` + `ValueAnimator` |
| `NavigationDock` | `FloatingDock` (纯 Canvas 绘制) |

### 关键差异：API 版本分层

**Android 比 Flutter 麻烦的地方**：`RuntimeShader` 只有 API 33+ 才有。
所以必须做三级降级：

| API | 效果 | 实现 |
|---|---|---|
| **33+** | 真玻璃：折射 + 色散 + 边缘光 | AGSL RuntimeShader |
| **31–32** | 磨砂：模糊 + 半透明白 | `RenderEffect.createBlurEffect` |
| **< 31** | 纯半透明卡片 | 只有 tint + 描边 |

代码里 `GlassCard.onDraw()` 就是这个分支。`GlassShader.isSupported()` 判断。

## 三、着色器在做什么（简化版数学）

```
对每个像素 p：
  d = 圆角矩形的有符号距离  (d < 0 在内部)

  # 折射：越靠边，采样点越往中心挪
  edge  = clamp(-d / bevel, 0, 1)
  bend  = (1 - smoothstep(0,1,edge))²
  offset = -normal * bend * refraction

  # 色散：RGB 用略微不同的偏移
  R = backdrop(p + offset * 1.01)
  G = backdrop(p + offset)
  B = backdrop(p + offset * 0.99)

  # 边缘光：沿边缘向内快速衰减
  rim = 1 - smoothstep(0, rimWidth, -d)
  color += rim * topLightFactor

  # 圆角外透明（SDF 抗锯齿）
  alpha = 1 - smoothstep(-1, 0.5, d)
```

`bdRoundRect`（有符号距离场）是整个效果的地基——
它同时给出了「是不是在内部」「离边缘多远」「法线方向」三个信息。

## 四、悬浮切换栏的设计决策

FlClash 的 `navigation_dock.dart` 有约 1500 行，包含：
- 12 个 `SpringDescription`（track/press/follow/lift/travel/jelly/settle/hover/fade/glowIn/glowOut）
- rubber-band 过度拖拽
- hover magnet（桌面鼠标悬停吸引）
- parallax（图标视差）
- glow 外发光

**我们只移植了 4 个**，理由：

| FlClash 特性 | 移植? | 原因 |
|---|---|---|
| 浮起胶囊 | ✅ | 核心形态 |
| 滑块弹性跟随 | ✅ | `OvershootInterpolator(0.9)` |
| 按压缩放 | ✅ | `DecelerateInterpolator` |
| 边缘光 | ✅ | 着色器里 |
| 拖拽 fling | ❌ | 我们只有 4 项，一屏放得下 |
| hover magnet | ❌ | 触屏没有 hover |
| jelly squash | ❌ | 收益/代码量比太低 |
| parallax | ❌ | 图标太小看不出 |

**canvas 绘制 vs 子 View**：FlClash 用 Flutter 的 Widget 组合；我们选纯 `Canvas`
是因为导航栏只有 4 个图标，用子 View 会导致每次选中都触发多次
`invalidate()` + layout，反而更容易掉帧。

## 五、踩到的坑

### 1. `Paint.setRenderEffect` 不存在

那是 `View` 的方法。对 `Paint` 做模糊要走 `RenderNode`：

```kotlin
val rn = RenderNode("blur")
rn.setRenderEffect(RenderEffect.createBlurEffect(...))
val rc = rn.beginRecording(w, h)
rc.drawBitmap(src, 0f, 0f, null)
rn.endRecording()
canvas.drawRenderNode(rn)
```

### 2. 着色器编译可能失败

不同 GPU 驱动对 AGSL 支持程度不同。`GlassCard.init` 里用 `runCatching`
包住，失败就退化成降级路径，**绝不崩**。

### 3. 玻璃需要「背后的内容」

`RuntimeShader` 的 `uBackdrop` 必须喂一张真实的背景图。
我们的做法是把根布局 `draw()` 到一个 Bitmap 再传进去。
这有个副作用：**背景是静态快照**，滚动时玻璃里的内容不会实时跟着动。

FlClash 用 `RepaintBoundary` + 每帧重新捕获解决这个问题。
Android 上要等价做到需要 `PixelCopy` 或 `RenderNode` 每帧重录，
代价不小。**当前取舍：静态背景 + 内容变化时手动 `refreshGlassBackdrop()`。**

## 六、验证

```
58 项测试全绿（新增 GlassStyleTest 12 项）
  - alpha / withAlpha 位运算
  - scale() 浮现动画的边界与单调性
  - 预设合理性（胶囊整圆角、降级关掉昂贵效果）
```

着色器本身无法在 JVM 单测验证，需要真机/模拟器截图。
