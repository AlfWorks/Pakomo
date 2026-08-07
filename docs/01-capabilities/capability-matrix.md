# 能力矩阵（Capability Matrix）

[English](capability-matrix_EN.md) | 简体中文

本文件列出 Pakomo 的能力及其实现状态，并说明 Kernel 与 Hev 两条实现路径的差异。

## 状态标签

| 标签 | 含义 |
|---|---|
| `Implemented` | 代码中已存在且可运行，有明确证据 |
| `Designed` | 方案已确定，尚未实现 |
| `Experimental` | 已有实现或设想，但仍需验证 |
| `Planned` | 路线图能力，未开工 |
| `Conceptual` | 仅作为概念说明，不承诺实现 |
| `Out-of-Scope` | 当前版本**明确不做**（边界声明） |

当前版本仅收录 `Implemented` 与 `Out-of-Scope` 两类条目：前者为现状能力，后者为当前版本明确不做的范围。

## 两条实现路径

Pakomo 的转发引擎有两个可选实现，以 Android build flavor 区分，**可同机并存**：

- **Kernel（k 版，`com.alphynia.pakomo.kernel`，默认）**：纯 Kotlin 自研 tun2socks 内核（`com.alphynia.pakomo.kernel`），
  实现 **Pakomo 当前业务所需的那部分**用户态隧道与转发能力（IPv4、TCP/UDP→SOCKS5、ICMP echo、连接回收）。
  Kernel 版足以承接 Pakomo 现有功能，但**不等于**用 Kotlin 完整重写 `hev-socks5-tunnel`。
- **Hev（h 版，`com.alphynia.pakomo.hev`）**：使用原生 `hev-socks5-tunnel` 转发核心，保留更完整、更成熟的底层能力范围。

两者是不同的实现路径，而非完整版与精简版的关系。中继、整形与故障注入逻辑在两种实现中完全一致，均位于共享的
`forwarding/Socks5Server` 及策略层，差异仅在 TUN 与 SOCKS 之间的一层。详见 [Kernel 后端](../02-architecture/kernel-backend.md) 与
[Hev 后端](../02-architecture/hev-backend.md)。

## 能力表

图例：✔ 支持  · ✘ 不支持 · — 不适用

### 连接层能力（无需解密即可实现）

| 能力 | Kernel | Hev | 状态 | UI 开放 | 边界 / 口径 |
|---|:--:|:--:|---|:--:|---|
| 固定延迟 | ✔ | ✔ | `Implemented` | 是 | 简单模式为整体值均分上下行；高级模式分方向独立。见 [测量口径](../06-testing/measurement-methodology.md) |
| 抖动（jitter） | ✔ | ✔ | `Implemented` | 是 | 分布模型见测量口径 |
| 丢包 | ✔ | ✔ | `Implemented` | 是 | 与第三方测速工具统计口径不同（上下行独立 vs 合并），见测量口径 |
| 带宽限速（上下行） | ✔ | ✔ | `Implemented` | 是 | 每方向独立 |
| 连接重置（TCP RST） | ✔ | ✔ | `Implemented` | 是 | `SO_LINGER(0)`，best-effort；UDP/QUIC 无 RST |
| DNS 解析失败（NXDOMAIN/SERVFAIL/REFUSED/超时 + 抗缓存） | ✔ | ✔ | `Implemented` | 是 | 仅明文 UDP 53 可见；DoH/DoT 不可命中 |
| 网络中断（静默超时 / 立即失败） | ✔ | ✔ | `Implemented` | 是 | 只作用连接层，不拦 DNS 查询；不触发系统真实断网广播 |
| **慢响应 / Late Response**（闸门暂扣） | ✔ | ✔ | `Implemented` | 是 | 独立故障模型，与普通延迟分开，参见 [故障模型](fault-models.md) |

### 归属与可观测

| 能力 | Kernel | Hev | 状态 | UI 开放 | 边界 / 口径 |
|---|:--:|:--:|---|:--:|---|
| 按应用归属（UID） | ✔ | ✔ | `Implemented` | 是 | `getConnectionOwnerUid()`；归属前导两版一致 |
| 按域名归属（SNI + 学习 IP） | ✔ | ✔ | `Implemented` | 是 | QUIC / 无 SNI 尽力；DoH 学不到 IP |
| 逐连接流量记录（FlowLog） | ✔ | ✔ | `Implemented` | 是 | 仅 TCP；UDP/QUIC/ICMP 不显示 |
| 运行时统计（速率/活动连接/累计丢弃/暂扣/uptime） | ✔ | ✔ | `Implemented` | 是 | `RuntimeStats`；**无**"当前丢包率/当前 RTT"稳定派生量 |
| 自动化控制协议（adb 广播驱动） | ✔ | ✔ | `Implemented` | adb | debug/release 均含；release 强制 token。见 [自动化控制接口](../automation-control.md) |

### 产品 / 呈现

| 能力 | Kernel | Hev | 状态 | UI 开放 | 边界 / 口径 |
|---|:--:|:--:|---|:--:|---|
| 接管范围：全局 / 指定应用 / 指定地址 | ✔ | ✔ | `Implemented` | 是 | 三种互斥；域名子域匹配 |
| 规则预设 + 自定义规则（含特殊故障，随规则保存） | ✔ | ✔ | `Implemented` | 是 | normal/light/medium/severe/offline + 用户规则 |
| 主题切换 + Pako 美术 | ✔ | ✔ | `Implemented` | 是 | 基础切换（`ThemeMode`）+ `PakomoArtwork` |
| Mascot 视觉状态（Stopped/Starting/Running/Idle/Error，5 态） | ✔ | ✔ | `Implemented` | 是 | 由 `EngineStage` 派生（`mascotStateOf`），`StatusDecor` 渲染。见 [状态映射](../03-product/state-mapping.md) |
| 多语言（中 / 英，即时切换） | ✔ | ✔ | `Implemented` | 是 | |
| 快捷悬浮控制（悬浮球开关接管） | ✔ | ✔ | `Implemented` | 是 | 需 `SYSTEM_ALERT_WINDOW` |
| 诊断（实时状态 / 归属命中 / Logcat） | ✔ | ✔ | `Implemented` | 是 | |

### 分发与更新

| 能力 | Kernel | Hev | 状态 | UI 开放 | 边界 / 口径 |
|---|:--:|:--:|---|:--:|---|
| 应用内自更新（novi） | ✔ | ✔ | `Implemented` | 是 | 基于 [novi](https://github.com/AlfWorks/Novi)（`com.alphynia.novi`）；清单 P-256 签名 + APK 签名者校验双层信任；更新源为 CI 打 tag 时发布的签名双轨（kernel/hev）公开更新源；应用内弹窗完成检测→下载→校验→安装，带校验详情。信任模型与协议详见 novi 文档 |

### 应用层内容故障 — 明确不做

| 能力 | Kernel | Hev | 状态 | UI 开放 | 边界 |
|---|:--:|:--:|---|:--:|---|
| HTTP 状态码注入（404 / 500 / 503） | ✘ | ✘ | `Out-of-Scope` | 否 | 见下 |
| 非法 JSON / 空响应 / 截断响应 | ✘ | ✘ | `Out-of-Scope` | 否 | 见下 |
| 修改响应正文 | ✘ | ✘ | `Out-of-Scope` | 否 | 见下 |

Pakomo 不解密 TLS，不进行中间人（MITM）解密，不持有证书，也不读取或保存 Payload，相关边界参见
[原则与边界](../00-overview/principles-and-boundaries.md)。对于 HTTPS 与 QUIC 流量，不解密便无法通用地识别并重写
HTTP 响应，因此这类能力不属于 Pakomo 的支持范围。此类能力仅在满足以下前提时局部成立，且均非 Pakomo 的默认能力：
仅适用于明文 HTTP、需要目标应用配合、需要测试构建单独提供代理或证书能力，或作为独立的实验性扩展提供。
完整说明参见 [限制](limitations.md)。

## Kernel / Hev 差异

| 维度 | 说明 |
|---|---|
| **共同能力** | 上表所有 `Implemented` 行：中继、整形、四种特殊故障、归属、FlowLog、自动化、UI 全部两版一致 |
| **Kernel 独有** | 无第三方原生依赖、构建不需 NDK；隧道层为自研纯 Kotlin，覆盖 Pakomo 当前所需子集 |
| **Hev 独有** | 原生 `hev-socks5-tunnel`，底层能力范围更完整、更成熟 |
| **当前功能差异** | 对 Pakomo 现有业务功能**无可见差异**（中继/故障逻辑共享） |
| **一致性要求** | 业务行为要求一致；两条路径通过同一套自动化冒烟 + 双 flavor 对拍校验 |
| **无法保证一致的底层差异** | 隧道层实现细节（如原生栈的成熟度、边角协议处理）不要求逐一对齐 |

如果 Pakomo 未来新增底层网络能力，Kernel 内核需要同步扩展才能与 Hev 对齐。这属于实现路径的自然差异。当前不存在此类缺口。

## 实现位置

- 弱网整形：`shaping/TrafficShaper.kt`、`forwarding/ShapingPolicy.kt`
- 特殊故障：`forwarding/FaultPolicy.kt`、`forwarding/Socks5Server.kt`、`data/SpecialFaultCodec.kt`、`core/model/PakomoModels.kt`
- 归属：`vpn/AndroidConnectionAttributor.kt`、`forwarding/DomainRoutingPolicy.kt`
- FlowLog：`forwarding/FlowLog.kt`、`core/model/FlowRecord.kt`
- Kernel 内核：`com/alphynia/pakomo/kernel/`（tun/ip/tcp/udp/icmp/socks）
- Mascot 状态：`ui/components/PakomoArtwork.kt`（`MascotState` / `mascotStateOf` / `StatusDecor`）
- 引擎状态：`core/model/PakomoModels.kt`（`EngineStage`：STOPPED/STARTING/FORWARDING/ERROR）
- 自动化：`app/src/debug/java/com/alphynia/pakomo/automation/`
