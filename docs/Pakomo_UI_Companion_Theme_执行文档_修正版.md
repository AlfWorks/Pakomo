# Pakomo UI · Companion 主题执行文档

> 本文档是 `Pakomo_UI_Technical_Design_revised.md`(设计方向)落到 **Pakomo 当前真实源码**上的可执行方案。
> 原文档是"视觉方向说明",本文档是"工程实施清单"。当二者冲突时,以本文档对真实代码的描述为准。
>
> **核心目标**:在**不移除、不改变**现有 Standard 风格的前提下,新增一个**可切换**的 Companion 主题。
> Standard = 现状(零视觉改动);Companion = 增量叠加(角色 / 状态色 / 轻故障)。
> 角色与状态插画本阶段一律用**占位符**(纯色块 / Compose 矢量),不引入真实美术资源。

---

## 0. 现状事实(执行前必须知道的真相)

| 事实 | 位置 | 对本任务的影响 |
|---|---|---|
| 颜色是顶层 `val`,不是 Token | `ui/theme/PakomoTheme.kt:14-24` | **顶层 val 无法随主题切换**。必须先 Token 化。 |
| `PakomoTheme(content)` 不接主题参数 | `PakomoTheme.kt:80-87` | 需要改签名为 `PakomoTheme(themeMode, content)`。 |
| 颜色被直接 import 49 处 / 9 文件 | 全 `ui/` 下 | 迁移面较大,但**可逐文件增量**,未迁移文件继续显示 Standard 色,不会崩。 |
| 内联硬编码色 28 处 / 7 文件 | 见 §2.2 清单 | 这些"隐藏色"也要 Token 化,否则 Companion 换不动状态卡/选择器。 |
| 主题在 `MainActivity.onCreate` 包裹 | `MainActivity.kt:92` | themeMode 要能传进这里(见 §3)。 |
| 状态经 `PakomoViewModel` → `PakomoUiState` StateFlow | `PakomoViewModel.kt`、`PakomoApp.kt:64` | themeMode 走**独立 StateFlow**,不要塞进 stats(stats 每秒刷新,会导致整树重组)。 |
| 偏好用 `PakomoPreferences`(SharedPreferences) | `data/PakomoPreferences.kt` | 加 `readThemeMode/writeThemeMode` 一对即可,与现有风格一致。 |
| `EngineStage` 只有 4 态 + `isIdleRunning` | `core/model/PakomoModels.kt:171` | 文档的 7 态视觉表**不能照抄**,见 §5。 |
| Home 是 `Column`,无 Box 叠层、无角色装饰 | `HomeScreen.kt:174` | 角色装饰属于可选视觉增量,列为 Phase 3;不得因视觉文档新增未定义的“紧急恢复”业务语义。 |
| 导航是手写 `mutableStateListOf<Screen>` 栈 | `PakomoApp.kt:66` | 不动导航;主题切换不涉及导航。 |
| 设置页 `SettingsScreen` 已有 Switch 行样式 | `ui/screens/UtilityScreens.kt:107` | 主题开关直接复用 `SettingSwitchRow` / 新增分段控件。 |

**结论**:关键路径不是画角色,而是 **①颜色 Token 化 → ②themeMode 管线 → ③Companion 色值 → ④视觉增量**。前两步不改任何视觉,是纯地基。

---

## 1. 总体阶段划分

```
Phase 0  颜色 Token 化重构        —— 零视觉变化,Standard 与现状像素一致
Phase 1  themeMode 管线 + 设置开关 —— 可切换,但此时只换颜色
Phase 2  Companion 视觉增量        —— 状态卡角色槽 / 空状态 / 背景装饰 / 轻故障(占位符)
Phase 3  待决策增项(可选)        —— 角色底部装饰、派生故障提示、关于页角色
```

每个 Phase 都能独立编译、独立验收、独立合并。Phase 0/1 合并后 App 行为与现在**完全一致**,只是多了一个默认关闭的开关。

---

## 2. Phase 0 — 颜色 Token 化(地基,零视觉变化)

### 2.1 新增 `PakomoColors` 与 CompositionLocal

在 `ui/theme/` 下新增(建议 `PakomoColors.kt`):

```kotlin
package com.pakomo.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class PakomoColors(
    // —— 基础层 ——
    val background: Color,
    val surface: Color,
    val surfaceFold: Color,
    val border: Color,
    val textPrimary: Color,       // 原 OnSurface
    val textSecondary: Color,     // 原 OnSurfaceVariant
    val muted: Color,             // 原 Muted
    // —— 强调 ——
    val accent: Color,            // 原 Accent
    val accentStrong: Color,      // 原 AccentStrong
    val accentTint: Color,        // 原 AccentTint
    // —— 状态语义(把现有内联硬编码收拢进来)——
    val statusRunning: Color,     // 现 = accent
    val statusStopped: Color,     // 现 #8D8D8D
    val statusIdle: Color,        // 现 #E5A23B(空闲运行橙)
    val errorContainer: Color,    // 现 #FFF5F4
    val errorBorder: Color,       // 现 #F0C8C4
    val warning: Color,           // 现 #B56D00(0 目标计数文字)
    val warningContainer: Color,  // 现 #FFF1D8
    val scopeTrack: Color,        // 现 #F0F2F5
    val scopeDisabled: Color,     // 现 #E2E5E9 / #E7E9EC 归一
    val danger: Color,            // 原 Danger
    // —— Companion 专属(Standard 下取中性回退值)——
    val glitchCyan: Color,
    val glitchPink: Color,
)

val LocalPakomoColors = staticCompositionLocalOf<PakomoColors> {
    error("PakomoColors not provided")
}

/** 便捷读取:PakomoColors.current */
val PakomoColorsProvider get() = LocalPakomoColors
```

> 说明:Token 表**刻意比原文档 §10 更长**,因为它必须覆盖代码里真实存在的内联色(状态卡橙/灰、选择器轨道、0 计数警告黄),否则 Companion 主题下这些区域换不动。这正是"贴合项目"与"照抄文档"的区别。

### 2.2 Standard 实例 = 现状色值(逐一对应,禁止改动数值)

```kotlin
val StandardColors = PakomoColors(
    background = Color(0xFFF7F8F9),
    surface = Color(0xFFFFFFFF),
    surfaceFold = Color(0xFFF6F8FA),
    border = Color(0xFFEEF0F3),
    textPrimary = Color(0xFF1C1F24),
    textSecondary = Color(0xFF59616E),
    muted = Color(0xFF98A2B3),
    accent = Color(0xFF3B6FE0),
    accentStrong = Color(0xFF2F5FCA),
    accentTint = Color(0xFFE8EEFC),
    statusRunning = Color(0xFF3B6FE0),
    statusStopped = Color(0xFF8D8D8D),
    statusIdle = Color(0xFFE5A23B),
    errorContainer = Color(0xFFFFF5F4),
    errorBorder = Color(0xFFF0C8C4),
    warning = Color(0xFFB56D00),
    warningContainer = Color(0xFFFFF1D8),
    scopeTrack = Color(0xFFF0F2F5),
    scopeDisabled = Color(0xFFE2E5E9),
    danger = Color(0xFFC0392E),
    // Standard 不表现故障美学 → 回退到中性/既有色
    glitchCyan = Color(0xFF98A2B3),
    glitchPink = Color(0xFF98A2B3),
)
```

**内联硬编码来源清单**(迁移时逐处替换为对应 Token):

| 硬编码 | 出处 | 迁移到 |
|---|---|---|
| `#8D8D8D` | `HomeScreen.kt:399` | `statusStopped` |
| `#E5A23B` | `HomeScreen.kt:398` | `statusIdle` |
| `#FFF5F4` | `HomeScreen.kt:396` | `errorContainer` |
| `#F0C8C4` | `HomeScreen.kt:412` | `errorBorder` |
| `#F0F2F5` | `PakomoComponents.kt:106` | `scopeTrack` |
| `#E2E5E9` | `PakomoComponents.kt:124` | `scopeDisabled` |
| `#E7E9EC` | `HomeScreen.kt:565,601` | `scopeDisabled` |
| `#FFF1D8` | `HomeScreen.kt:603` | `warningContainer` |
| `#B56D00` | `HomeScreen.kt:594` | `warning` |
| `Color.White`(卡片底) | 多处 | `surface`(语义化,可选) |

### 2.3 改造 `PakomoTheme`

```kotlin
enum class ThemeMode { Standard, Companion }

@Composable
fun PakomoTheme(
    themeMode: ThemeMode = ThemeMode.Standard,
    content: @Composable () -> Unit,
) {
    val colors = when (themeMode) {
        ThemeMode.Standard -> StandardColors
        ThemeMode.Companion -> CompanionColors   // Phase 1 定义
    }
    CompositionLocalProvider(LocalPakomoColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(), // 由 token 生成,替代原写死 scheme
            typography = PakomoTypography,
            content = content,
        )
    }
}
```

`toMaterialColorScheme()` 用 token 拼出与现在等价的 `lightColorScheme`(primary=accent、background、surface、outline=border、error=danger…),保证 Material 组件(Switch、AlertDialog、Card 默认色)行为不变。

### 2.4 迁移策略(增量、可分多次 PR)

- 保留旧顶层 `val`(暂不删)作为过渡,新代码一律走 `LocalPakomoColors.current`。
- **逐文件**把 `import ...theme.Accent` → 读 `val c = LocalPakomoColors.current; c.accent`。
- 每迁完一个文件即可编译验收;**未迁移文件仍用旧 val(=Standard 色),不影响运行**。
- 全部迁移完成后,删除顶层 `val` 与 `PakomoTheme.kt` 里旧的 `private val PakomoColors`。
- 迁移顺序建议:`PakomoComponents.kt`(共享组件,收益最大)→ `HomeScreen.kt` → `UtilityScreens.kt` → `RulesScreen / ScopeScreen / RuleEditorScreen / LatencyTestScreen / SpecialFaultScreens`。

**Phase 0 验收**:切到 Standard,逐屏与迁移前截图像素对比,应无差异。

---

## 3. Phase 1 — themeMode 管线 + 设置开关

### 3.1 持久化(`PakomoPreferences.kt`)

仿照现有 `readQuickControlEnabled/writeQuickControlEnabled` 增加一对:

```kotlin
fun readThemeMode(): ThemeMode =
    runCatching { ThemeMode.valueOf(preferences.getString(KEY_THEME_MODE, ThemeMode.Standard.name)!!) }
        .getOrDefault(ThemeMode.Standard)

fun writeThemeMode(mode: ThemeMode) =
    preferences.edit { putString(KEY_THEME_MODE, mode.name) }
// KEY_THEME_MODE = "theme_mode",默认 Standard(即上线后默认不变样)
```

### 3.2 独立 StateFlow(避免性能问题)

**不要**把 themeMode 塞进 `PakomoUiState`——stats 每秒 update 一次,若主题 key 在整个 state 上会触发全树重组(违反原文档 §17"主题切换不应触发页面结构重建")。

在 `PakomoViewModel` 新增独立流:

```kotlin
private val _themeMode = MutableStateFlow(preferences.readThemeMode())
val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

fun setThemeMode(mode: ThemeMode) {
    if (_themeMode.value == mode) return
    _themeMode.value = mode
    viewModelScope.launch(Dispatchers.IO) { preferences.writeThemeMode(mode) }
}
```

### 3.3 `MainActivity.onCreate` 接线

```kotlin
setContent {
    val serviceRuntime by VpnServiceController.runtime.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()   // 新增
    LaunchedEffect(serviceRuntime) { viewModel.setEngineRuntime(serviceRuntime) }

    PakomoTheme(themeMode = themeMode) {                     // 传入
        PakomoApp(
            viewModel = viewModel,
            /* 现有参数保持不变 */
            themeMode = themeMode,                           // 透传给设置页显示
            onThemeModeChange = viewModel::setThemeMode,     // 透传给设置页开关
            ...
        )
    }
}
```

### 3.4 设置页开关(`UtilityScreens.kt` → `SettingsScreen`)

在"服务"分组上方或"应用与系统"下方,新增一个"外观"分组。因为是二选一,用**分段控件**比 Switch 更贴切(也顺手复用了原文档想要的 Companion 分段风格):

```kotlin
SectionLabel("外观")
InfoCard {
    ThemeModeRow(
        current = themeMode,
        onChange = onThemeModeChange,
    )   // "标准" | "Pakomo 陪伴" 两段;沿用 ScopeSelector 的分段视觉语言
}
```

> `SettingsScreen` 需新增两个参数 `themeMode` / `onThemeModeChange`,并在 `PakomoApp.kt` 的 `Screen.Settings ->` 分支透传(与现有 `quickControlEnabled` 走法完全一致)。

**Phase 1 验收**:
- 切换开关 → 全 App 颜色即时切换,布局/交互/导航零变化。
- 杀进程重启 → 记住上次选择。
- 运行 VPN 时切主题 → 服务不中断、stats 不卡顿、不整树重建。

---

## 4. Phase 2 — Companion 视觉增量(占位符美术)

### 4.0 看板娘 Pako 资产映射(视觉基准)

Companion 主题的品牌载体是看板娘 **Pako**(参考资产:`pako.png` 角色稿、`pakomo_icon.png` 应用图标)。
下列锚点为 Phase 2 占位实现的对齐基准——**占位符的形状/配色/状态语义必须朝这些锚点收敛**,将来替换真美术时调用点不动:

| 视觉元素 | 在角色/图标中的样子 | 对应文档条目 | 落地位置 |
|---|---|---|---|
| Pako 本体 | 浅灰蓝长发、青碧色眼睛、白/浅蓝露肩针织 | §3.2 角色主色 | 状态卡角色槽(§4.1)、空状态(§4.2)、关于页(§7) |
| 小黑兽发饰 | 头侧的小黑蛙夹子 | §5.1.1 "黑色小兽发饰" | 顶栏 Brand Icon / App Icon 极简版 |
| 托举的青色数据方块 | 手心托着发光青色方块 | §5.1.2 "运行中托举数据块" | 状态卡 FORWARDING 态 |
| 全息碎裂像素 | 图标背景粉/青/薰衣草碎块 | §7 装饰 / §8 故障母题 | 背景装饰层(§4.3)、轻故障(§4.4) |
| 六边形轮廓 | 图标背景淡描边六边形 | §7 "淡六边形轮廓" | 背景装饰层(§4.3) |
| 暖中性灰底 | 图标背景采样后调校去紫 | §7 背景方向 | Companion `background` |

> `pakomo_icon.png` 实际上就是整套 Companion 主题的参考渲染——背景色、六边形、碎裂像素、漂浮数据方块一应俱全。占位阶段可直接以它为构图参照。
> 下方调色板的 accent / glitch / background 均由 `pako.png` 与 `pakomo_icon.png` 真实像素采样得出,而非臆想值。

### 4.1 Companion 调色板(品牌校准,由真图采样)

先定义 Companion 色实例(**起始建议值,供设计微调**;遵守原文档 §7:背景避开大面积蓝,以免与蓝发角色打架):

```kotlin
val CompanionColors = PakomoColors(
    background    = Color(0xFFF3F2EF),  // 暖中性灰(去紫,衬托冷色角色)
    surface       = Color(0xFFFCFBFA),
    surfaceFold   = Color(0xFFEEEBE6),
    border        = Color(0xFFE6E2DB),
    textPrimary   = Color(0xFF2A2E38),
    textSecondary = Color(0xFF5D6068),
    muted         = Color(0xFFA09E98),
    accent        = Color(0xFF4A72A8),  // ← 发色灰蓝 #5883B3/#6D9AC7 加深保证对比
    accentStrong  = Color(0xFF3A5C8A),
    accentTint    = Color(0xFFE4EAF2),
    statusRunning = Color(0xFF4A72A8),
    statusStopped = Color(0xFF9C9A94),
    statusIdle    = Color(0xFFC79A54),  // 暖黄(角色无此色,保留做空闲区分)
    errorContainer= Color(0xFFF7EEEC),
    errorBorder   = Color(0xFFE7CCC6),
    warning       = Color(0xFF9A6A2E),
    warningContainer = Color(0xFFF6EAD6),
    scopeTrack    = Color(0xFFE4E0D9),  // 比 surface 暗一档,让分段选择器选中段浮起
    scopeDisabled = Color(0xFFDBD6CE),
    selectionBorder = Color(0xFFCBD3E4),
    disabledContainer = Color(0xFFECE9E3),
    warningStrong = Color(0xFFC0873C),
    danger        = Color(0xFFBA5A54),
    glitchCyan    = Color(0xFF5FD0DE),  // ← 眼睛/手心数据方块亮青(高光 #B2E1EE 加深)
    glitchPink    = Color(0xFFE6B8D0),  // ← 图标全息碎裂粉 #EFC6C6/#EDEDFF(真实,非臆想)
)
```

> **采样与实机调校**:`accent` 取自 Pako 发色簇(`#5883B3`/`#6D9AC7`),`glitchCyan` 取自眼睛/数据方块高光(`#B2E1EE`),`glitchPink` 取自图标全息碎裂(`#EFC6C6`)。
> 实机复审后两处调整:①大面积中性面由"暖灰紫"改为**暖中性灰**——蓝发角色是冷色身份,背景带紫会显脏、喧宾夺主,暖中性灰更衬角色;②`scopeTrack` 调暗一档,修复 Companion 下分段选择器"选中段与轨道明度太近、选中态不明显"。蓝/青/粉只保留在 accent 与故障点缀。

### 4.2 状态卡角色槽(`HomeScreen.kt` → `ServiceStatusCard`)

- 现结构:`Row [ 图标圈 | 文本列 weight(1) ]`,卡片高 72dp。
- 增量:仅在 `themeMode == Companion` 时,在文本列右侧加一个 **35–40% 宽的角色装饰槽**(原文档 §5.1.2 比例)。
- 角色槽 = `Box` + `.clearAndSetSemantics {}`(不进语义树、不可点击),内容此阶段用**占位矢量**(一个随状态变色的圆角方块 + 简单表情/数据块符号)。
- 状态→占位表现映射见 §5(**只映射真实 4+1 态**)。
- 硬约束:文本 z 序高于角色;角色不接触摸事件;Standard 下此槽不渲染。

### 4.3 空状态角色(原文档 §5.4,角色最佳落点)

当前**没有独立空状态组件**。落点:
- 规则页无自定义规则、日志页无输出、Scope 无目标等场景,新增 `EmptyState(art, title, hint, action?)` 复用组件。
- Standard:纯文字 + 标准按钮(现风格);Companion:上方加占位角色图。
- 主按钮始终用标准 Material 组件,不被角色替代。

### 4.4 背景与轻装饰(原文档 §7)

- Companion 下 `PakomoApp.kt:116` 的 `Surface(color = Background)` 改读 `LocalPakomoColors.current.background`(Phase 0 已 Token 化则自动生效)。
- 可选:在 Home 内容层**之下**加一层低对比度装饰(淡六边形轮廓 / 少量点阵),用 `drawBehind` 实现,`alpha ≤ 0.06`,小屏自动消失。禁止覆盖文字。

### 4.5 轻故障效果(原文档 §8/§9)

- 仅作用于:状态卡数据块、TrafficCard 丢包瞬间。
- 形式:单次 100–180ms 短闪 / 方块一次错位 / 局部色偏(用 `glitchCyan/glitchPink`)。
- **复用现有动效预算**:代码里已有 tween(180/120/280) 的节奏,故障动画对齐同区间。
- 严禁:整页滤镜、扫描线、RGB 分离、高频循环、覆盖图表数据。
- 遵守系统"减少动画":读 `AccessibilityManager` / 动画 scale,为 0 时降级为静态。

**Phase 2 验收**:关闭角色资源(占位)后所有功能仍可用;日志/图表区无装饰干扰;小屏/横屏/大字体不遮挡;Standard 完全不受影响。

---

## 5. 状态映射(修正版:对齐真实引擎,而非文档 7 态)

真实 `EngineStage` = `STOPPED / STARTING / FORWARDING / ERROR`,外加 UI 派生的 `isIdleRunning`。**Phase 2 只做这 5 行**:

| 真实状态 | 主色 Token | 占位角色表现 | 数据块 | 故障 |
|---|---|---|---|---|
| STOPPED | `statusStopped` | 安静 / 休息 | 熄灭 | 无 |
| STARTING | `glitchCyan` | 观察 / 等待 | 渐亮 | 轻微短闪一次 |
| FORWARDING(正常) | `statusRunning` | 托举数据块 | 点亮 | 极少 |
| FORWARDING(isIdleRunning) | `statusIdle` | 提示 / 指向 | 半亮 | 无 |
| ERROR | `danger`/`errorContainer` | 异常反馈 | 错位 | 一次错位 |

**原视觉文档中的 High Latency / Packet Loss / Disconnected 不属于当前 `EngineStage`**,不得直接作为引擎状态实现。处理方式二选一（列为 Phase 3）：
- 从 `RuntimeStats`（如 `droppedTransfers`、`delayedTransfers`）派生**非持久、非互斥的 UI 提示**，不修改 `EngineStage`，也不覆盖 STOPPED / STARTING / FORWARDING / ERROR；
- 或本期不做，仅保留真实 5 态。

本期默认采用第二种：**只实现真实 5 态**。派生提示需另行定义阈值、去抖、持续时间和优先级后再进入开发。

---

## 6. Phase 3 — 待决策增项(需你拍板,勿默认开工)

### 6.0 本阶段已确定的边界

- **不新增“紧急恢复正常网络”按钮。**
- **Phase 2 只映射真实 5 态。**
- 高延迟、丢包仅可作为未来从 `RuntimeStats` 派生的 UI 提示，不得伪装成现有引擎状态。
- 所有新增文案必须与真实引擎行为一致，不能仅依据视觉概念稿承诺能力。


这几项在原文档里被当作既定要求,但**当前代码里并不存在**,属于新增功能而非"增量接入",因此单列并需要你确认:

### 6.1 底部角色装饰层(原文档 §5.1.6 / §14)
- 需要把 `HomeScreen` 的根 `Column` 包一层 `Box`,底部叠角色装饰 + 响应式高度隐藏规则。
- 建议:**做**,但仅 Companion、仅占位、`clearAndSetSemantics{}`、不参与测量。

### 6.2 停止 VPN 快捷入口（不新增“紧急恢复”语义）

- 当前 App 和引擎不存在独立的“紧急恢复正常网络”能力，因此本执行方案**不新增**该按钮，也不把它列为 UI 验收项。
- 视觉文档中的“紧急恢复”仅是概念建议，不能据此创造新的业务承诺。
- 若后续认为首页需要更显式的安全出口，只允许复用现有“停止 VPN”行为，并使用准确文案，例如：
  - `停止 VPN`
  - `停止弱网模拟`
- 该入口必须调用现有、已验证的停止流程，不得暗示会执行额外的网络修复、DNS 恢复、代理清理或系统设置还原。
- 若未来确实需要“恢复正常网络”能力，应先定义引擎侧行为、失败处理和可验证边界，再单独设计 UI。

### 6.3 派生运行提示（见 §5）
- 可评估从 stats 派生“延迟较多”“丢弃较多”等轻提示。
- 这些提示不是新的引擎状态，也不应命名为“断网状态”。
- 开发前必须补充阈值、采样窗口、去抖规则、展示时长与多提示冲突优先级。

---

## 7. 关于页角色(原文档 §6 允许区)
- 当前无独立"关于"页(信息在设置页底部)。若要角色亮相,可在设置页新增"关于"分组或子页,Companion 下放占位角色 + 版本信息。列为 Phase 2 可选。

---

## 8. 资源与占位策略(本期)
- 角色/状态图当前**全部用 Compose 占位**;真美术按需产出后按下方 manifest 落位。
- **唯一接缝**:`ui/components/PakomoArtwork.kt` 的 `PakomoMascot(state)`(状态卡角色)与后续 `EmptyStateArt(...)`(空状态角色)。真图到位后**只改这些函数体**为 `painterResource(...)`,所有调用点不变。
- 资源格式:优先 WebP、**背景透明**、不烘焙 UI 文字;按密度桶提供(或给一张 xxhdpi 高清由系统降采样)。

### 8.1 看板娘 Pako 资产 manifest(按需产出,分批)

> 通用要求:①背景透明;②主体安全内边距,勿贴边;③不含任何文字;④命名走项目 `res/drawable` 规范。
> **编号即占位标签**:App 里每个角色位现在都是"白底黑框黑字编号"占位框(如 `A1 mascot_status_running`),编号与下表一一对应,方便实机对照"哪个槽在哪"。真图按资源名给我即可。

**批次 A · 状态卡角色(已接线占位)** —— 右侧局部裁切(脸+肩+托举数据方块那只手),**非全身**。

| 编号 | 资源名 | 状态 | 表现(参考 §5) |
|---|---|---|---|
| A1 | `mascot_status_running` | 运行中 | 托举发光青色数据方块,自信/微笑 |
| A2 | `mascot_status_idle` | 空闲运行 | 提示/指向(催促选目标) |
| A3 | `mascot_status_starting`| 正在启动 | 观察/等待 |
| A4 | `mascot_status_stopped` | 已停止 | 安静/休息 |
| A5 | `mascot_status_error` | 启动失败 | 困惑 + 一枚轻微错位碎块 |

- **构图**:主体靠右,**左侧 ~55–60% 必须留空**(状态文字压在其上,不能被角色挡)。交付比例约 3:2 横向(如 312×208 @xxhdpi),右对齐。
- **背景适配(关键难点)**:状态卡底色随状态变**实色**(运行=蓝、空闲=橙、停止=灰、失败=浅底)。角色需带**描边/柔和轮廓光**,在深色实底与浅底上都读得出;避免大面积纯白(浅底消失)或纯黑(深底消失)。若高饱和态实测压不住,退路:仅低饱和态显示角色。

**批次 B · 空状态角色(浅底,无背景适配难题)** —— 半身/全身,居中构图,约 1:1(480×480 @xxhdpi),透明。

| 编号 | 资源名 | 位置(已接线占位) | 表现 |
|---|---|---|---|
| B1 | `empty_apps` | Scope 应用搜索无结果 | Pako 摊手 |
| B2 | `empty_address` | Scope 地址列表为空 | Pako 抱着空数据盒 |
| B3 | `empty_targets` | 特殊故障目标页无目标 | Pako 指向(提示先去选目标) |
| B4 | `empty_logs` | 日志空态(**未接线**,logcat 常流) | Pako 看着空终端 |

**批次 C · 首页装饰 / 品牌 / 关于**

| 编号 | 资源名 | 用途 | 约束 |
|---|---|---|---|
| C1 | `mascot_home_{档}_{n}` | 首页**角色主卡**(铺满接管范围卡下方→App 底部整片区,**已接线占位**) | **差分变体**,见下方案 |
| C2 | `brand_mark` | ~~顶栏品牌图标~~(顶栏图标已移除,**暂不需要**)/ 将来 App Icon 极简版可复用 | 暂缓 |
| C3 | `about_pako` | 关于页较完整立绘(关于页已存在,未接角色) | 透明、居中,~1:1 |

> **C1 方案(差分变体 + 按状态尺寸随机)**:主卡区高度随状态变(全局/指定 × 停止/运行,约 394–554dp)。**不用一张图拉伸**,改为按高度分几档、每档出多张差分;运行时按当前区域高度选对应档、**随机取一张**贴底满宽显示(不缩放变形)。
> - **档位(按区域高度)**:`tall`(停止态,最高)/ `mid`(运行 或 指定停止,中)/ `short`(指定+运行,最矮)。三档的宽高比 ≈ 该状态区域的宽高比,避免裁切/变形。
> - **每档多张差分**(不同姿势/表情),各 3–5 张都行(做多点没事);切状态时在对应档里**随机放一张**。
> - 命名:`mascot_home_tall_1..n` / `mascot_home_mid_1..n` / `mascot_home_short_1..n`。
> - 通用:透明背景、**主体贴底、横向满构图**、**左上留白**给规则/日志/设置三行导航文字避让、不含文字。
>
> 已接线占位:A1–A5、B1–B3、C1(A/B 各一张一位;C1 真图按上面分档 × 差分出多张)。B4/C2/C3 等对应功能做出来再补。
> 建议先出**批次 B**(最好画、无背景适配坑),再攻**批次 A**(需背景适配打磨),C1 分档差分最后攻。

---

## 9. 验收总表

| 维度 | 标准 |
|---|---|
| 不破坏现状 | Standard 与重构前逐屏像素一致;默认主题 = Standard |
| 可切换 | 设置页一键切换,即时生效,重启记忆 |
| 性能 | 切主题不整树重建;运行中切换 VPN 不中断、stats 不卡 |
| 增量非平行 | 无复制页面 / 无平行业务组件 / 导航与状态源不变 |
| 角色边界 | 角色不进日志正文/规则编辑/图表;不可点击;从语义树移除 |
| 无障碍 | 状态卡可被 TalkBack 朗读;不靠颜色单独表意;字体缩放不截断 |
| 动效 | 无高频循环;故障单次短暂;遵守系统减少动画 |

---

## 10. 落地顺序(建议的提交切分)

1. **PR-0a**:新增 `PakomoColors` + `LocalPakomoColors` + `StandardColors` + `PakomoTheme(themeMode)`(旧 val 暂留)。
2. **PR-0b~0d**:逐文件迁移 49 处 import + 28 处内联色;迁完删旧 val。
3. **PR-1**:`ThemeMode` 持久化 + ViewModel `themeMode` 流 + MainActivity 接线 + 设置页分段开关。
4. **PR-2a**:`CompanionColors` + 状态卡角色占位槽 + 状态映射(5 态)。
5. **PR-2b**:空状态组件 + 背景装饰 + 轻故障(占位)。
6. **PR-3**（待决策）：底部装饰 / 派生运行提示 / 关于角色。若需要首页快捷停止，仅复用现有停止 VPN 行为，单独评审。

> PR-0 与 PR-1 合并后即达成"可切换且不改现状";Companion 的视觉丰富度在 PR-2 之后逐步累加,任何时点中断都不影响 Standard 用户。
