# Kernel 后端（纯 Kotlin tun2socks）

Kernel 版使用纯 Kotlin 实现 Pakomo 当前业务所需的那部分用户态隧道与转发能力，足以承接 Pakomo 已有功能，
但并不等于用 Kotlin 完整重写 `hev-socks5-tunnel`。如果 Pakomo 未来新增底层网络能力，Kernel 内核需要同步扩展。
两者属于不同的实现路径，而非完整版与精简版的关系。

## 覆盖范围

Kernel 内核（`com.alphynia.pakomo.kernel`）只实现 Pakomo 数据链路实际用到的子集：

- IPv4。Pakomo 只添加 `0.0.0.0/0` 的 IPv4 路由，不包含 IPv6 双栈。
- TCP 转 SOCKS5，通过用户态 TCP 状态机桥接到本地 `Socks5Server`。
- UDP 转 SOCKS5 UDP ASSOCIATE，用于 DNS、QUIC 等。
- ICMP echo 的本地应答。
- 连接回收与空闲超时。

Kernel 内核不实现 IPv6、非 Android 平台后端、通用 SOCKS5 特性，以及原生栈的全部边角协议处理。

## 组件

| 文件 | 职责 |
|---|---|
| `Tun2SocksEngine.kt` | 编排器，接管 TUN fd，启动读写与各类流，并回收空闲连接 |
| `Tun2SocksConfig.kt` | 隧道配置，包含 SOCKS5 端口与凭据等 |
| `KernelDispatchers.kt` | 协程调度 |
| `tun/TunReader.kt`、`tun/TunWriter.kt` | TUN fd 的读与写，写入串行化 |
| `ip/Ipv4Packet.kt`、`ip/Checksum.kt` | IPv4 解析与校验和 |
| `tcp/TcpConnection.kt`、`tcp/TcpSegment.kt` | TCP 状态机与段处理 |
| `udp/UdpSession.kt` | UDP 会话，桥接到 SOCKS5 UDP ASSOCIATE |
| `icmp/IcmpResponder.kt` | ICMP echo 的本地应答 |
| `socks/Socks5Client.kt` | 作为 SOCKS5 客户端连接本地 `Socks5Server`，包含归属前导，行为与 HEV 补丁一致 |

## 关键设计

- TUN 侧是无损内存链路。应用、内核与用户态栈之间不存在真实的丢包或乱序，弱网与故障由下游的 `Socks5Server` 注入。
  因此 TCP 栈的难点是背压，即通过收缩通告窗口让应用自然停下，而非抗丢包。
- 所有回写 TUN 的包经单一写者串行处理，以保证顺序。
- `Socks5Client` 透传原始连接五元组，使 Kotlin 侧的 `getConnectionOwnerUid()` 能够按应用与域名归属，
  该行为与 HEV 的归属前导补丁一致。

## 优势

Kernel 版不含第三方原生依赖，构建不需要 NDK，APK 更小、构建更快；全部为 Kotlin，可进行单元测试，
IP、TCP、UDP 与 ICMP 的解析与校验和均有测试覆盖。

## 历史

自研内核替换 hev 的过程，包括校验和缺陷、DNS 端口错误与吞吐优化，详见
[内核替换 postmortem](../kernel-replacement-postmortem.md)。Kernel 版已于 2026 年 8 月通过真机验收。
