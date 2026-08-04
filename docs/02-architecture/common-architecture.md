# 公共架构（Common Architecture）

本文说明 Pakomo 如何实现其能力。能力清单以 [能力矩阵](../01-capabilities/capability-matrix.md) 为准。

## 总链路

```
App → TUN → 转发引擎 [ Kernel: Tun2SocksEngine（纯 Kotlin） | Hev: hev-socks5-tunnel（native） ]
          → 本地认证 SOCKS5 (Socks5Server) → protect() Socket → 目标服务器
```

设备 TUN 流量由转发引擎转发至仅监听本机随机端口的 SOCKS5 中继；所有连出目标服务器的 Socket 先经
`VpnService.protect()`，不再回环进 TUN。两条引擎路径之后的中继、整形与故障逻辑完全相同，差异仅在 TUN 与 SOCKS
之间的一层，分别参见 [Kernel 后端](kernel-backend.md) 与 [Hev 后端](hev-backend.md)。

## 分层与职责

| 层 | 关键类 | 职责 |
|---|---|---|
| VPN 生命周期 | `vpn/WeakNetworkVpnService`、`vpn/VpnServiceController` | 建立、热更新与停止接管；发布运行状态与 `appliedConfigId` |
| 配置传递 | `vpn/VpnRuntimeConfigStore` | 大配置图保留在进程内，Intent 只携带一次性 configId，避免 Binder 大事务 |
| 转发引擎（二选一） | `kernel/Tun2SocksEngine` 或 `hev-socks5-tunnel` | 在 TUN 裸包与 SOCKS5 流之间转换 |
| 本地中继 | `forwarding/Socks5Server`、`forwarding/NioRelay` | 认证 SOCKS5、按连接中继、`protect()` 出站 |
| 整形 | `shaping/TrafficShaper`、`forwarding/ShapingPolicy` | 延迟、抖动、丢包与限速 |
| 故障 | `forwarding/FaultPolicy`、`forwarding/DnsMessage` | 四种特殊故障的判定与注入 |
| 归属 | `vpn/AndroidConnectionAttributor`、`forwarding/DomainRoutingPolicy`、`forwarding/HostSniffer` | 基于 UID、SNI 与学习 IP 的按应用与域名归属 |
| 可观测 | `forwarding/FlowLog`、`vpn/TunnelStatsSampler`、`vpn/RecentHitTracker` | 逐连接记录、运行时统计与命中 |
| 自动化（debug） | `app/src/debug/java/com/pakomo/automation/` | 通过 adb 广播驱动，转发至 `VpnServiceController` |

## 数据面原则

- **单一中继**：无论采用哪条引擎，整形与故障都作用于同一个 `Socks5Server` 实例；弱网参数与特殊故障叠加于同一数据面，
  互不重复施加。
- **按连接判定**：由于不解密 TLS，整形与故障的最小粒度为连接。
- **热更新一致性**：规则与域名变更通过 `ACTION_UPDATE` 热切换，不重建隧道、不断开连接；接管范围与选中应用的变更通过
  `ACTION_START` 重建接口。配置生效以 `appliedConfigId` 确认，等待语义参见 [自动化控制接口](../automation-control.md)。

## flavor 与构建

- `engine` 维度包含两个 flavor：Kernel（默认，applicationId `com.pakomo.kernel`，无 native）与 Hev（`com.pakomo.hev`，
  native）。
- 构建脚本以"任务名是否包含 `hev`"决定是否编译原生库；两个 flavor 必须分开调用，否则 native 会连带编入 Kernel 版包。
- 自动化控制组件仅在 debug 构建（`src/debug`）注册，release 产物不含。

## 参见

- 端到端数据流与故障注入点：[数据流](data-flow.md)。
- 自研内核替换的历史与吞吐优化：[内核替换 postmortem](../kernel-replacement-postmortem.md)。
