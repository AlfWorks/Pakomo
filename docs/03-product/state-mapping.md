# 状态映射（State Mapping）

[English](state-mapping_EN.md) | 简体中文

Pakomo 的可视状态由真实引擎状态派生，共五个，均已实现。界面文案、Mascot 视觉与美术资源使用同一套状态标识。

## 状态来源

实现位于 `ui/components/PakomoArtwork.kt`。

```kotlin
enum class MascotState { Stopped, Starting, Running, Idle, Error }

fun mascotStateOf(stage: EngineStage, isIdleRunning: Boolean): MascotState = when (stage) {
    EngineStage.STOPPED   -> MascotState.Stopped
    EngineStage.STARTING  -> MascotState.Starting
    EngineStage.FORWARDING -> if (isIdleRunning) MascotState.Idle else MascotState.Running
    EngineStage.ERROR     -> MascotState.Error
}
```

引擎状态 `EngineStage`（`core/model/PakomoModels.kt`）只有四个：`STOPPED`、`STARTING`、`FORWARDING` 与 `ERROR`。
Mascot 在 `FORWARDING` 下再按是否有活动流量区分 `Running` 与 `Idle`，因此共有五个稳定状态。

## 状态标识

| 状态标识 | 来源 | 含义 | 视觉倾向 |
|---|---|---|---|
| `companion.state.stopped` | `EngineStage.STOPPED` | 未接管 | 静止、灰 |
| `companion.state.starting` | `EngineStage.STARTING` | 正在建立接管 | 过渡，`StatusDecor` 使用 glitchCyan |
| `companion.state.running` | `FORWARDING` 且有流量 | 接管中且有流量 | 活跃 |
| `companion.state.idle` | `FORWARDING` 且空闲 | 接管中且无流量 | 平静 |
| `companion.state.error` | `EngineStage.ERROR` | 启动或运行出错 | 警示，`StatusDecor` 使用 danger |

渲染由 `StatusDecor(state)` 负责（`PakomoArtwork.kt`），属装饰性视觉，只反映上述状态，不引入新的业务语义。

## 不存在的状态

Pakomo 不提供由 `RuntimeStats` 实时派生的"高延迟""丢包""断网"等工况严重度状态，因为 `RuntimeStats` 只有累计量，
不提供"当前丢包率"或"当前 RTT"一类的稳定派生量，参见 [限制](../01-capabilities/limitations.md)。因此 Mascot 不随
网络劣化程度改变表情，只反映引擎生命周期。

## 主题与 Mascot 视觉资源

Pakomo 未单独设立美术需求文档。已实现的视觉资源如下：

- **主题**：`ui/theme/`，通过 `ThemeMode` 切换，包含 `PakomoColors` 与 `PakomoTheme`。
- **Pako 美术**：`ui/components/PakomoArtwork.kt`，包含 `StatusDecor` 等装饰。角色"Pako"及相关美术为界面装饰，
  由 Stable Diffusion 生成（AI 生成内容）。

后续如需扩充 Mascot 视觉，交付资源需与上表的状态标识一一对应。
