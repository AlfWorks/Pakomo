# 特殊故障错误语义与验证规范

## 1. 文档目的

本文定义 Pakomo 三类特殊故障在传输层应执行的动作，以及应用侧可能观察到的错误。

这里需要区分两个层次：

- **协议行为**：Pakomo 可以控制，例如拒绝连接、发送 TCP RST、丢弃数据或合成 DNS 响应。
- **应用错误码**：由 Android 网络栈、HEV、应用使用的解析器和 Chromium 版本共同决定，Pakomo 只能设定目标，不能保证所有设备返回完全相同的编号。

因此，验证时首先检查协议行为是否正确，再记录应用侧错误码。Chromium `net::Error` 仅作为主要参考。

## 2. 当前问题

真机验证发现：对某个未缓存域名启用“网络中断”时，无论选择立即还是静默，应用都可能先得到：

`net::ERR_NAME_NOT_RESOLVED (-105)`

原因是当前网络中断也处理 UDP 53：

- 立即模式为 DNS 查询合成 NXDOMAIN。
- 静默模式直接丢弃 DNS 查询。

此时失败发生在域名解析阶段，连接尚未建立，连接拒绝、连接重置与静默挂起的差异无法体现。

当前实现还有第二个差异：

- 全局或整应用目标可以在连接前判定，立即中断返回 SOCKS `NETWORK_UNREACHABLE`。
- 域名目标通常需要连接后读取 TLS SNI 或 HTTP Host，命中后只能重置已建立连接。

同一个“立即中断”因此可能表现为不可达、拒绝或重置，语义不够稳定。

## 3. 设计结论

### 3.1 网络中断不处理 DNS 查询

网络中断只处理目标的数据连接：

- TCP
- 非 DNS UDP
- QUIC

普通 DNS 查询保持正常，由“DNS 解析失败”单独处理。这样未缓存域名仍可以先完成解析，网络中断的立即与静默差异才会出现在连接阶段。

这会修订 [项目方向](PROJECT-DIRECTION.md) 中的故障优先级描述：网络中断仍然优先于其他连接故障，但不再抢占 DNS 查询。

### 3.2 三类故障保持独立

- Connection Reset：建立连接后主动重置。
- DNS 解析失败：只改变 DNS 查询结果。
- 网络中断：拒绝、挂起或丢弃目标的数据连接。

三类故障可以同时开启。运行时按协议类型分别决策，而不是用一个全局分支覆盖全部流量。

### 3.3 立即与静默的含义

- **立即**：尽快给出主动失败结果，不等待应用超时。
- **静默**：不返回有效结果，让应用按自身超时策略结束。

“立即”不代表所有域名目标都能返回同一个错误码。首次连接可能必须先建立连接并嗅探域名，此时只能降级为 TCP RST。

## 4. 目标行为矩阵

| 故障 | 模式 | Pakomo 的协议行为 | Chromium 目标表现 |
| --- | --- | --- | --- |
| Connection Reset | 立即 | TCP 建立后发送 RST | `ERR_CONNECTION_RESET (-101)` |
| 网络中断 | 立即 | 连接前返回 refused；无法预判时降级为 RST | `ERR_CONNECTION_REFUSED (-102)`；降级时 `-101` |
| 网络中断 | 静默 | 接受后挂起，不返回数据 | `ERR_TIMED_OUT (-7)` |
| DNS 失败 | NXDOMAIN | 合成 DNS `RCODE=3` | `ERR_NAME_NOT_RESOLVED (-105)` |
| DNS 失败 | SERVFAIL | 合成 DNS `RCODE=2` | 通常为 `ERR_NAME_RESOLUTION_FAILED (-137)` |
| DNS 失败 | REFUSED | 合成 DNS `RCODE=5` | 通常为 `ERR_NAME_RESOLUTION_FAILED (-137)` |
| DNS 失败 | 超时 | 丢弃 DNS 查询 | 由解析器决定，常见为 `-137`、`-105` 或应用自己的超时错误 |

表中的 Chromium 编号是验证目标，不是跨设备保证。

## 5. TCP 行为

### 5.1 Connection Reset

对命中的 TCP 连接：

1. 向隧道侧确认连接建立。
2. 对客户端 Socket 设置 `SO_LINGER(0)`。
3. 立即关闭连接，促使隧道向应用表现为 TCP RST。

目标错误为 `ERR_CONNECTION_RESET (-101)`。

UDP 没有 TCP RST。对 QUIC（UDP 443）只能丢弃数据，尝试促使应用回落到 TCP；应用是否回落由其自身决定，不能保证。

### 5.2 网络中断：立即

如果连接前已经可以确定目标命中：

- 返回 SOCKS `CONNECTION_REFUSED (0x05)`。
- 目标表现为 `ERR_CONNECTION_REFUSED (-102)`。

可以在连接前判定的情况包括：

- 全局范围
- 整应用目标
- 域名对应的目标 IP 已从明文 DNS 响应中学习

如果是域名首次连接、目标 IP 尚未学习，必须先连接并读取 TLS SNI 或 HTTP Host：

- SOCKS SUCCESS 已经返回，无法再返回 refused。
- 命中后使用 `SO_LINGER(0)` 重置连接。
- 此时允许降级为 `ERR_CONNECTION_RESET (-101)`。

### 5.3 网络中断：静默

静默模式统一采用：

1. 向隧道侧确认连接建立。
2. 不连接或不继续使用目标服务器。
3. 保持客户端连接打开。
4. 读取并丢弃客户端数据，不返回任何内容。
5. 等待应用主动关闭或达到 Pakomo 的空闲保护上限。

目标表现为等待后超时，Chromium 通常返回 `ERR_TIMED_OUT (-7)`。

对非 DNS UDP 和 QUIC 直接丢弃。UDP 的最终超时由应用协议决定。

## 6. DNS 行为

### 6.1 第一阶段支持范围

第一阶段只处理隧道中可见的普通 UDP 53 DNS 查询。

暂不保证：

- TCP 53 DNS
- DNS-over-HTTPS
- DNS-over-TLS
- 应用自带的加密解析协议

DoH、DoT 内容无法按查询域名修改。若应用完全绕过系统明文 DNS，域名级 DNS 故障可能无法命中。

### 6.2 DNS 结果

| 结果 | DNS RCODE | 含义 |
| --- | ---: | --- |
| NXDOMAIN | `3` | 查询完成，但域名不存在 |
| SERVFAIL | `2` | DNS 服务器无法完成解析 |
| REFUSED | `5` | DNS 服务器拒绝查询 |
| 超时 | 无响应 | DNS 查询没有得到回复 |

合成响应必须：

- 保留请求事务 ID。
- 保留原始 Question。
- 标记为 Response。
- 设置正确的 RCODE。
- 不携带伪造的成功 Answer。
- 从原 DNS 服务器地址返回给应用。

### 6.3 错误码边界

系统解析器、Chromium 异步解析器和应用自带解析器可能对同一个 RCODE 使用不同错误码：

- NXDOMAIN 最常对应 `-105`。
- SERVFAIL、REFUSED 目标为 `-137`，但部分解析器仍可能归并为 `-105`。
- 超时无法指定最终错误码，也无法严格控制应用等待时长。

文档和界面应描述 DNS 语义，不应承诺固定 Chromium 编号。

### 6.4 DNS 缓存

已有 DNS 缓存时，应用可能不再发起查询，DNS 失败无法直接修改已缓存结果。

“阻止缓存后的连接”是 DNS 故障中的独立开关，默认关闭。开启后，对 DNS 故障目标的
TCP 连接执行重置，对非 DNS UDP 执行丢弃。日志使用 `dns-cache-guard-reset` 或
`dns-cache-guard-drop`，不把它描述成 DNS 响应失败。

## 7. 按协议执行优先级

三类故障允许选择同一个目标，按以下顺序决策。

### 7.1 DNS 查询

1. DNS 解析失败
2. 普通转发

网络中断不处理 DNS 查询。

### 7.2 TCP

1. 网络中断
2. DNS 缓存保护（启用时）
3. Connection Reset
4. 普通弱网规则

### 7.3 非 DNS UDP

1. 网络中断
2. DNS 缓存保护（启用时）
3. Connection Reset 的 QUIC 回落辅助
4. 普通弱网规则

## 8. 实现状态

### 8.1 本轮已完成

1. `FaultPolicy.decideUdp`
   - 端口 53 且存在查询名时，不再执行网络中断。
   - 只检查 DNS 解析失败；未命中则正常转发。

2. `Socks5Server.handleConnect`
   - 即使存在域名过滤，也先用 `request.host` 尝试一次连接前故障判断。
   - 如果目标 IP 已学习并命中，直接执行连接前故障。
   - 未命中时再连接并嗅探 SNI/HTTP Host。
   - 避免同一连接重复记录命中日志。

3. `Socks5Server`
   - 新增 `REPLY_CONNECTION_REFUSED = 0x05`。
   - 网络中断立即模式的连接前回复改用 refused。
   - 连接后命中的立即模式继续使用 RST，作为明确降级。

4. DNS 结果
   - 保留现有 NXDOMAIN、SERVFAIL 和超时。
   - 增加 `DnsFailureResult.REFUSED`。
   - 增加 `DnsMessage.RCODE_REFUSED = 5`。
   - 更新持久化编解码和参数界面。

5. 项目方向
   - 将“网络中断优先处理 TCP 和 UDP”修订为“网络中断处理 TCP 与非 DNS UDP；DNS 查询由 DNS 解析失败处理”。

6. 稳定性与性能
   - 大型运行配置保留在应用进程内，Intent 只传递一次性编号，避免 Binder 大事务闪退。
   - 规则参数与特殊故障目标统一写入编辑草稿，仅在点击“保存”时持久化并热更新一次。
   - 运行时策略在后台构建，过期更新不会覆盖较新的配置。
   - 域名匹配改为后缀集合查找，DNS 学习只更新当前归属应用的策略。
   - 同一连接的重复命中去重，并对重连风暴日志限速汇总。
   - 静默中断使用小型丢弃缓冲，降低大量挂起连接的内存占用。

7. DNS 缓存保护
   - 增加独立开关，默认关闭。
   - 开启时才对已缓存目标执行连接重置或 UDP 丢弃。

8. 日志动作
   - 使用 `pre-connect-refused`、`post-connect-reset`、`silent-park`、`udp-drop`、
     `quic-fallback-drop`、`dns-*` 与 `dns-cache-guard-*` 区分实际动作。

以上改动均已进入当前工作区；仍需由 Android Studio 构建并进行真机验证。

### 8.2 后续增强

- 支持 TCP 53 DNS。
- 统计每种协议动作的命中次数。

## 9. 日志规范

每个连接或 DNS 目标的特殊故障命中至少记录：

- 故障类型
- 当前接管范围
- 应用包名（可归属时）
- 配置目标
- 实际连接目标
- 执行阶段：连接前、连接后或 DNS
- 实际动作

建议使用稳定动作名：

| 动作名 | 含义 |
| --- | --- |
| `pre-connect-refused` | 连接建立前主动拒绝 |
| `post-connect-reset` | 连接建立后发送 RST |
| `silent-park` | 保持连接但不返回数据 |
| `udp-drop` | 丢弃非 DNS UDP |
| `quic-fallback-drop` | 丢弃 Connection Reset 目标的 QUIC 数据以促使回落 TCP |
| `dns-nxdomain` | 返回 NXDOMAIN |
| `dns-servfail` | 返回 SERVFAIL |
| `dns-refused` | 返回 REFUSED |
| `dns-timeout` | 丢弃 DNS 查询 |
| `dns-cache-guard-reset` | DNS 缓存保护触发 TCP 重置 |
| `dns-cache-guard-drop` | DNS 缓存保护触发 UDP 丢弃 |

日志不得记录 DNS 报文正文、HTTP 内容、Cookie、凭据或其他应用数据。同一连接内的重复
数据包去重；每秒超过上限的重连命中以汇总行保留数量，避免日志洪泛。

## 10. 验证矩阵

### 10.1 必测范围

- 全局
- 整应用
- 应用内指定域名
- 指定域名

### 10.2 必测状态

- 域名未缓存
- 域名已缓存
- 目标 IP 尚未学习
- 目标 IP 已从明文 DNS 学习
- HTTP/TCP
- HTTPS/TCP
- QUIC/UDP 443
- 普通 UDP
- 系统明文 DNS
- DoH 应用

### 10.3 协议行为验证

- Connection Reset：抓包或应用日志能确认 TCP RST。
- 网络中断立即：可预判时连接快速被拒绝；不可预判时明确降级为 RST。
- 网络中断静默：连接保持但无响应，直到应用或保护超时结束。
- DNS NXDOMAIN：收到 RCODE 3。
- DNS SERVFAIL：收到 RCODE 2。
- DNS REFUSED：收到 RCODE 5。
- DNS 超时：没有收到 DNS 响应。
- 网络中断单独开启时，DNS 查询仍可正常完成。

### 10.4 应用表现记录

真机记录但不把编号作为唯一通过条件：

- Connection Reset：预期 `-101`。
- 网络中断立即：预期 `-102`；域名首连允许 `-101`。
- 网络中断静默：预期 `-7`。
- DNS NXDOMAIN：预期 `-105`。
- DNS SERVFAIL、REFUSED：预期 `-137`，允许解析器映射差异。
- DNS 超时：记录实际错误码和等待时间。

至少覆盖 Android 10、一个中间版本和当前主要支持版本。每个版本分别记录系统 WebView/Chromium 版本。

## 11. 已知限制

- 应用错误码不能跨 Android、Chromium 和解析器版本严格保证。
- 域名首次连接在无法预判时，立即网络中断只能降级为 RST。
- DoH、DoT 无法按查询域名合成失败响应。
- QUIC 没有 TCP Reset；丢弃 UDP 443 也不能保证应用回落 TCP。
- 网络中断模拟的是目标流量故障，不会让 Android 系统广播真实的网络断开。
- DNS 超时由解析器重试策略决定，可能明显慢于 TCP 静默超时。
