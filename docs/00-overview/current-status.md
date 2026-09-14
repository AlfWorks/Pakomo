# 当前状态（Current Status）

[English](current-status_EN.md) | 简体中文

> 现状快照。能力的权威清单以 [能力矩阵](../01-capabilities/capability-matrix.md) 为准；本文只做叙述性总览。
> 只记录**已实现**的现状与**明确不做**的边界；已废弃的未来设想不在此列。

## 已实现（Implemented）

- **双转发引擎并存**：Kernel（纯 Kotlin 自研 tun2socks，`com.alphynia.pakomo.kernel`，默认，无 NDK）与
  Hev（原生 `hev-socks5-tunnel`，`com.alphynia.pakomo.hev`）。中继/整形/故障逻辑两版一致。Kernel 版 **2026-08 真机验收通过**。
  TCP 转发基于**共享事件循环（NIO reactor）**：非阻塞、无每连接读写线程，修正了半关闭/FIN 时序、握手数据投递、入站背压与空闲回收，弱网/长连接/网络切换下更稳。
- **弱网整形**：延迟 / 抖动 / 丢包 / 上下行限速，简单与高级（分方向）两模式。
- **四种特殊故障**：连接重置、DNS 失败（NXDOMAIN/SERVFAIL/REFUSED/超时 + 抗缓存）、网络中断（静默/立即）、
  慢响应（Late Response，闸门暂扣 + 放行小响应阈值）。随规则保存，可同时启用。
- **接管范围**：全局 / 指定应用 / 指定地址，域名子域匹配。
- **归属与可观测**：按 UID + SNI/学习 IP 归属；流量页逐连接 FlowLog（来源应用、源/目的 IP、时长、整形/暂扣；点条目从底部弹出详情；DNS 记录查询域名与解析结果；关键字段可复制）；运行时统计（含**按连接延迟**：实测握手 RTT + 整形注入延迟）；实时诊断 + Logcat。
- **自动化控制协议**：adb 广播驱动（start/update/stop/status/reset/load_profile），三路回读，配置生效确认，
  release 强制 token。控制组件仅 debug 构建含。见 [automation-control](../automation-control.md)。
- **产品**：主题切换 + Pako 美术、Mascot 5 视觉状态（绑 `EngineStage`）、首次启动跟随系统语言、中/英即时切换、**贴边悬浮窗快捷控制**（一键启停 + 就地切换规则；可侧向展开**按连接延迟趋势图表**——各连接彩色曲线 + 综合平均，切规则时实时更新以确认整形是否生效）。

## 明确不做（Out-of-Scope）

- 应用层内容故障（HTTP 状态码注入、非法 JSON、空/截断响应、改写正文）——违反不解密/不 MITM 边界。

## 参见

- 边界依据：[原则与边界](principles-and-boundaries.md)
