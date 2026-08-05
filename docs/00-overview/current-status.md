# 当前状态（Current Status）

> 现状快照。能力的权威清单以 [能力矩阵](../01-capabilities/capability-matrix.md) 为准；本文只做叙述性总览。
> 只记录**已实现**的现状与**明确不做**的边界；已废弃的未来设想不在此列。

## 已实现（Implemented）

- **双转发引擎并存**：Kernel（纯 Kotlin 自研 tun2socks，`com.alphynia.pakomo.kernel`，默认，无 NDK）与
  Hev（原生 `hev-socks5-tunnel`，`com.alphynia.pakomo.hev`）。中继/整形/故障逻辑两版一致。Kernel 版 **2026-08 真机验收通过**。
- **弱网整形**：延迟 / 抖动 / 丢包 / 上下行限速，简单与高级（分方向）两模式。
- **四种特殊故障**：连接重置、DNS 失败（NXDOMAIN/SERVFAIL/REFUSED/超时 + 抗缓存）、网络中断（静默/立即）、
  慢响应（Late Response，闸门暂扣 + 放行小响应阈值）。随规则保存，可同时启用。
- **接管范围**：全局 / 指定应用 / 指定地址，域名子域匹配。
- **归属与可观测**：按 UID + SNI/学习 IP 归属；逐连接 FlowLog；运行时统计；实时诊断 + Logcat。
- **自动化控制协议**：adb 广播驱动（start/update/stop/status/reset/load_profile），三路回读，配置生效确认，
  release 强制 token。控制组件仅 debug 构建含。见 [automation-control](../automation-control.md)。
- **产品**：主题切换 + Pako 美术、Mascot 5 视觉状态（绑 `EngineStage`）、中/英即时切换、悬浮球快捷控制。

## 明确不做（Out-of-Scope）

- 应用层内容故障（HTTP 状态码注入、非法 JSON、空/截断响应、改写正文）——违反不解密/不 MITM 边界。

## 参见

- 边界依据：[原则与边界](principles-and-boundaries.md)
- 自研内核替换历史：[kernel-replacement-postmortem](../kernel-replacement-postmortem.md)
