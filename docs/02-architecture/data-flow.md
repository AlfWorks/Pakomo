# 数据流（Data Flow）

[English](data-flow_EN.md) | 简体中文

本文追踪一条连接如何被接管、归属、整形与注入故障。故障行为的定义以 [故障模型](../01-capabilities/fault-models.md) 为准。

## 1. 建立接管

1. 界面或自动化触发 `VpnServiceController.start` 或 `update`，生成 configId，将配置图存入 `VpnRuntimeConfigStore`，
   Intent 携带该 configId。
2. `WeakNetworkVpnService` 消费配置，启动本地 `Socks5Server`（随机端口与随机凭据），通过 `VpnService.Builder.establish()`
   建立 TUN（按接管范围设置允许的应用），并启动转发引擎（Kernel 或 Hev）。
3. 管道就绪后，发布 `EngineStage.FORWARDING` 与 `appliedConfigId`。

## 2. 一条连接的路径

```
App 发起连接
  → 内核路由进 TUN
  → 转发引擎重组为流，作为 SOCKS5 客户端（携带归属前导）连接本地 Socks5Server
  → Socks5Server.handleConnect：
       归属：getConnectionOwnerUid() 定位 UID 与应用；域名依据 SNI 与学习 IP 判定
       连接前故障判定：命中"网络中断·立即"且可预判时返回 refused；命中 DNS 缓存保护时执行 reset 或 drop
       未命中或需嗅探时：先经 protect() 连出目标，读取 SNI 或 HTTP Host 后再判定，必要时降级为 RST
       命中整形或慢响应：在中继 pump 上施加
  → 经 protect() 的出站 Socket 连接目标服务器
  → 下行数据经中继（可能被整形或暂扣）返回 App
  → FlowLog 记录该连接（协议、显示主机名、端口、上下行字节、是否暂扣/整形、状态、来源应用、
    源/目的 IP:端口、结束时间；DNS 流量另记查询过的域名及其解析到的 IP）
```

## 3. 归属与域名学习

- **应用归属**：`ConnectivityManager.getConnectionOwnerUid()` 依据原始五元组（由归属前导透传）在 Kotlin 侧解析。
- **域名匹配**：`DomainRoutingPolicy` 使用 TLS SNI 后缀，以及从明文 DNS 学习到的目标 IP 进行 IP 匹配，
  以覆盖 QUIC 与无 SNI 的连接。
- **DNS 学习**：中继在 UDP 响应路径上通过 `observeDnsResponse` 读取明文 DNS，跨 SOCKS 会话学习域名与 IP 的对应关系；
  DoH 与 DoT 无法学习。
- 整形与故障复用同一个归属器，以保证同一连接的归属一致且不重复查询（`AndroidConnectionAttributor` 带有 origin 到 packages
  的缓存）。
- **展示域名反查**：`DnsNameCache` 在 DNS 响应路径上学习 IP→域名（全局，与整形/故障的域名学习相互独立），建流时把目的 IP
  反查成域名用于流量列表展示；不嗅探、不阻塞，DoH/DoT 或接管前已缓存的解析看不到时回落显示 IP。
- **展示归属**：`AndroidConnectionAttributor.displayPackageFor` 为任意应用（不限所选集合）做单次、无重试的 owning package 解析，
  供流量列表显示来源，避免整形/故障归属路径的重试开销、不增加建连延迟。
- **DNS 查询记录**：DNS 流量按解析器（`IP:53`）聚合为一条；请求路径记录查询名，响应路径解析 A 记录记录其结果，供连接详情逐条展示
  「域名 → 解析 IP」。仅 IPv4（A 记录）。
- **快照复用**：已结束连接的 `FlowRecord` 快照只构建一次并复用（引用相等），使每秒一次的列表刷新只重建仍活跃的少数连接，
  降低历史连接堆积时的 GC 抖动。

## 4. 故障与整形的注入点

| 故障 | 注入位置 |
|---|---|
| 网络中断·立即（可预判） | `handleConnect` 连接前，返回 SOCKS refused |
| 网络中断·立即（域名首连） | 连出并嗅探后，降级为 RST |
| 网络中断·静默 | 接受后挂起，丢弃客户端数据 |
| 连接重置 | 连接建立后经 `SO_LINGER(0)` 发送 RST |
| DNS 失败 | `handleUdpAssociate` 的 DNS 路径，合成 RCODE 或丢弃 |
| 弱网整形 | 中继 pump，施加延迟、抖动、丢包与限速 |
| 慢响应 | 下行 `delayedPump` 的闸门暂扣 |

## 5. 热更新

- 规则与域名变更通过 `ACTION_UPDATE` 触发 `reconfigure()`，在后台构建新的 runtime，先切换数据面（`socks.reconfigure`），
  成功后再发布元数据，并调用 `publishAppliedConfig(configId)`；失败时调用 `publishFailedConfig`。整个过程不重建隧道、
  不断开连接。
- 接管范围或选中应用的变更通过 `ACTION_START` 重建 TUN 接口，因为允许的应用集只能在 establish 时设定。

## 6. 停止

`ACTION_STOP` 停止中继与引擎，关闭 TUN，并发布 `EngineStage.STOPPED`。
