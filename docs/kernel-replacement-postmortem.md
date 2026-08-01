# Pakomo tun2socks 内核替换 · 故障剖析与后续规划

> 把第三方 native 库 `hev-socks5-tunnel` 替换为自研纯 Kotlin 转发内核（`com.pakomo.kernel`）过程中，三个相互独立的缺陷叠加，让整套链路看起来"完全不通"。本文完整剖析每个缺陷、版本分化方案，并给出后续优先级项的**具体处理逻辑**。

- **项目**：pakomo · Android VpnService
- **目标**：hev → `com.pakomo.kernel`
- **验证设备**：96800305
- **结论**：已修复并实测通过（握手 `116/0` → `52/52`，网页正常访问）

---

## TL;DR — 三个 bug 叠加，任何一个都足以"全网瘫痪"

1. **编译 timeout** — 构建仍在编译整套 hev 的 C 代码，NDK 全量编译拖垮构建。
2. **校验和全错（核心）** — `Checksum.sum` 里一个写错的位运算，让每个回包的校验和只基于头部第一个字节。Android 内核静默丢弃所有写入 tun 的包 → App 收不到 SYN-ACK → TCP 握手永远不完成。
3. **DNS 回包端口写反** — 即使校验和正确，DNS 应答也会被送到错误的 UDP 端口。
4. **隐藏放大器** — 单元测试套件本身编译不过、从没运行过，本该拦住 #2 的校验和测试形同虚设。

> 本文按时间顺序覆盖三轮：**连通性（§1）→ 稳定性与负载崩溃（§2）→ 下载吞吐（§3）**。每一轮都是"上一轮修好后才暴露出来"的更深问题。

---

## 1 · 第一轮缺陷：连通性

### 1.1 校验和只累加了第一个字节 `[Critical · 核心根因]`

**位置**：`app/src/main/java/com/pakomo/kernel/ip/Checksum.kt:12`

目的是"把长度向下取偶"，但 `0xFFFE.inv()` 算错了：`0xFFFE` 是 Int `0x0000FFFE`，取反得 `0xFFFF0001`。于是 `length and 0xFFFF0001` 对任何小于 65536 的偶数长度都得 **0**，主循环一次都不执行，只把第一个字节加进去就返回。

```kotlin
// Before — 缺陷：20 and 0xFFFF0001 == 0 → end = offset
val end = offset + (length and 0xFFFE.inv())

// After — 修复：清掉最低位，真正向下取偶
val end = offset + (length - (length and 1))
```

设备上抓到的真实 SYN-ACK 印证了这一点。IP 头校验和字段是 `0xBAFF`，恰好等于 `complement(0x4500)` —— 只算了头部第一个字节 `0x45`：

```
TUN write hex = 4500 0028 0000 0000 4006 baff debdac66 0a4d0001 …   ← 缺陷
正确应为        4500 0028 0000 0000 4006 e55e debdac66 0a4d0001 …   ← 修复后
```

**后果**：IP / TCP / UDP 全部校验和错误 → Android 内核丢弃每一个回包 → App 永远收不到 SYN-ACK，握手卡死。这也解释了为何目标 IP 全是首字节 ≥128 的地址却依旧失败——问题与地址无关，是所有包都被丢。

### 1.2 NDK 仍在编译整套 hev C 代码 `[Critical · 编译 timeout]`

**位置**：`app/build.gradle.kts · externalNativeBuild`

构建配置里 `externalNativeBuild { ndkBuild { path = ".../hev-socks5-tunnel/Android.mk" } }` 让每次构建都把 `third_party` 下上千个 C 文件按两个 ABI 全量编译一遍。与 Kotlin 代码无关，纯粹是构建卡死的来源。已通过 product flavor 拆分解决（见第 2 节）——kernel 版完全跳过 NDK 工具链。

### 1.3 DNS 回包 UDP 端口写反 `[High · DNS 不通]`

**位置**：`app/src/main/java/com/pakomo/kernel/udp/UdpSession.kt · sendToTun()`

App 查询是 `UDP src=54321 → dst=53`，回包必须镜像为 `src=53 → dst=54321`。但代码把源端口写成了本地中继通道的临时端口 `source.port`，目的端口写成了 `destinationPort`(53)。

```kotlin
// Before
writeInt16(udpHeader, 0, source.port)
writeInt16(udpHeader, 2, destinationPort)

// After
writeInt16(udpHeader, 0, destinationPort)
writeInt16(udpHeader, 2, sourcePort)
```

**后果**：DNS 应答被送到 App 没有监听的端口 → 解析永远超时。IP 地址与伪首部本来就是对的，所以端口按同样的镜像关系修正即可。

### 1.4 单元测试套件从未编译通过 `[High · 放大器]`

**位置**：`app/src/test/java/com/pakomo/kernel/KernelUnitTest.kt:70`

`0xC0A80001` 超过 Int 上限，Kotlin 推断为 `Long`，而 `buildHeader` 要 `Int`，导致 `compileDebugUnitTestKotlin` 失败。整个测试从来没运行过。而 `checksumBasic` 本应算出 `0x3A7B`、抓住上面的校验和 bug（缺陷版会得到 `0xBAFF`）——但因为套件编译不过，它形同虚设。修好编译错误 + 校正期望值后，测试恢复守卫作用。

---

## 2 · 第二轮缺陷：稳定性与负载崩溃

连通打通后暴露出四个只在"用一会儿"或"高并发"时才发作的稳定性缺陷——现象是越用越慢最终超时，或一刷 B 站视频列表就"实时流量瞬间归零"。

### 2.1 TCP 连接表泄漏（无空闲回收）`[High]`

**问题**：`Tun2SocksConfig.tcpReadWriteTimeoutMs` 定义了却从未被使用，`TcpConnection` 没有任何空闲超时。空闲的 ESTABLISHED，或卡在 FIN_WAIT/LAST_ACK 的半关闭连接（对端消失、永不 ACK 我们的 FIN），永远留在表里。累积到 `maxSessionCount`(1024) 后 `handleTcp` 静默丢弃所有新 SYN → 新连接全超时。UDP 有空闲回收，TCP 没有。

**处理**：每连接维护 `lastActivityMs`（进出段都刷新）；引擎级 reaper 每 15s 扫描：ESTABLISHED/CLOSE_WAIT 空闲超 `tcpReadWriteTimeoutMs`(300s) 回收，握手中/半关闭状态空闲超 30s 强制关闭（连带 actor 退出、关 SOCKS socket、取消 RTO）。

**验证**：reaper 日志中 tcp 计数在 17–82 间震荡、不再单调爬向 1024。

### 2.2 UDP 下行中继忙等 `[High]`

**问题**：`relayChannel` 是非阻塞的，`relayJob` 在无数据时立即返回 null 并**无任何等待就重来**——每个 UDP 会话满速空转一个协程。DNS/QUIC 短流累积到 ~293 个会话时 CPU 被打满 → 全局变慢。

**处理**：无数据时 `delay(RELAY_POLL_MS)` 让出，消除热自旋；UDP 空闲回收 60s→30s 降低并发驻留数。

**验证**：UDP 会话数稳定在 13–103，CPU 回到空闲。

### 2.3 TunWriter 阻塞队列冻结线程池 `[High]`

**问题**：写队列是 `LinkedBlockingQueue`，满时 `put()` **阻塞调用线程**。下行洪流写不过来 → 队列满 → 所有连接的 `sendSegment → w.send` 阻塞在 `Dispatchers.Default`（线程数≈核数）上 → 整个 Default 线程池被占满 → 协程饿死。

**处理**：改用挂起式 `Channel`，满时发送方**挂起**（让出线程）而非阻塞线程；EAGAIN 重试用 `delay` 取代 `Thread.sleep`。背压沿 TCP 流控自然回传。

### 2.4 IO 线程池耗尽 → 引擎冻结 `[Critical · "刷 B 站→流量归零"]`

**问题**：每个连接的上游中继用**阻塞 `socket.read()` + `Dispatchers.IO`**，读取时占住一个 IO 线程。`Dispatchers.IO` 上限 64。一刷 B 站列表瞬间开上百连接、各自阻塞在 `read()` → 64 个线程被占满 → 连读/写 TUN 的核心循环（也跑在 IO 上）都抢不到线程 → 整个引擎冻结 → **实时流量瞬间归零**。

**日志签名**：reaper（在 Default 上）照常每 15s 打印，但连接表卡死在 `tcp=21 / udp=89` 长达 90s 纹丝不动——Default 还活着，IO 全死。

**处理**：新增 `KernelDispatchers`——`core`(3 线程) 专供 TUN 读/写循环，永不被饿死；`connIo`(256 线程，独立预算) 承载连接阻塞 I/O。`Dispatchers.IO.limitedParallelism` 对 IO 允许突破 64 且各视图互不争抢。

**验证**：反复刷 B 站列表 + 播放，流量图不再归零；连接表数字持续波动、无冻结签名、无写循环报错。

> **局限**：`connIo=256` 仍是"每连接一个阻塞线程"的模型，极端并发下会触顶（但核心已隔离，不再全局冻结）。**正解见 P1-0：上游改非阻塞 NIO。**

---

## 3 · 第三轮缺陷：下载吞吐

标准（无整形）配置下下载被严重压缩——初期"下几 MB 就断"，修复断连后又只有几百 KB/s。诊断走了弯路（慢测试服务器 tele2 给出 0 重传的假象、一度误判为限速），最终用一个能跑满速的国内镜像（清华，原始 56MB/s）复现并逐层定位。

### 3.1 无发送侧流控 → 高速下溢出→重传风暴→RST `[Critical]`

**问题**：`sendDataToApp` 无节制地把上游数据灌给 App，不限制"在途未确认字节 ≤ App 通告窗口"。高速下溢出 App 的 64KB 接收窗口 → App 丢弃并把窗口降到 0 → 同一段重传到 `MAX_RETRANSMIT` → RST 断连（"下几 MB 就断"）。低速（tele2 15KB/s）时不溢出、0 重传——这个假象一度把诊断带偏。

**处理**：仅在 `inFlight < appWindow` 时发送，窗口满则等 ACK（等待放在锁外，让 ACK 能推进窗口），背压沿链路回传。

### 3.2 无窗口缩放 → 吞吐被 64KB/RTT 封顶 `[High]`

**问题**：SYN-ACK 不带 Window Scale 选项，整条连接窗口锁死 ≤64KB。即便流控正确，goodput = 64KB/RTT，手机 RTT 下只有几百 KB/s。

**处理**：SYN-ACK 加 MSS + WScale 选项、解析 App 的 shift、双向缩放窗口字段。实测 App 窗口自动长到 **5–6MB**，吞吐跳升。

### 3.3 并发状态损坏 → 重传风暴（断连真凶）`[Critical]`

**问题**：`sendDataToApp`（socksReadJob 协程）与 ACK 处理（actor 协程）**并发读写同一份发送状态**（`sendQueue`/`sndNxt`/`rtoJob`，非线程安全）。日志实证：同一 seq 被 3 个线程在同一毫秒并发重传——`startRtoTimer` 被并发调用产生多个 RTO 定时器。窗口缩放只是让它跑更快才炸。

**处理**：用 `Mutex` 串行化发送状态修改（`sendSegment`/`processAck`/`retransmit`），reset 路径放锁外避免可重入死锁。断连根治。

### 3.4 单连接吞吐榨取：7MB/s → ~19MB/s

断连修好后单连接稳定但只有 ~7MB/s（窗口 6MB 不受限、发送侧能跑 33MB/s），瓶颈在**每连接的固定串行点**，逐一消除：

- **每段抢锁的乒乓（主因）**：发送与 ACK 每段各抢一次 `Mutex`，互相乒乓 + 上下文切换，恰好卡在 ~7MB/s。→ **一次抢锁批量发多段**（每 16KB 从 ~11 次锁降到 1 次）→ 单连接翻近 3 倍到 ~19MB/s，0 重传。
- **RTO 定时器每段重建协程**：`sendSegment` 每段 `cancel + launch` 一个协程。→ 只保留一个定时器、仅在 ACK 真正推进时重整。
- **读/发串行**：socksReadJob "读完再发、发完再读"。→ 拆成生产者/消费者两协程并发流水线。
- **本地中继串行 + 小缓冲**：本地 `Socks5Server` 的 `copyDirect` 同样读写串行、缓冲 16KB，上游/环回 socket 无大缓冲，正常模式还白走 `delayedPump` 慢路径。→ 中继流水线化、缓冲扩容（上游收 2MB / 发我方 1MB / 拷贝 64KB / 我方环回收 1MB）、空操作 shaper 时旁路 `delayedPump` 走直拷。

**结果**：单连接从"670KB 处断"→ **稳定 16.5–22MB/s、0 重传、完整下完 197MB**；4 连接并发 27MB/s+ 可叠加。

### 3.5 诚实的天花板

原始直连本身在 26–56MB/s 剧烈波动。隧道单连接现约为原速的 **35%–75%**（取决于原始那一刻的波动值）。**单连接 95% 原速在此"存储转发双跳 SOCKS 中继 + 用户态 TCP 栈"架构下不可达**——剩余差距来自每包处理开销、ACK 路径仍逐个抢锁、以及双跳中继的固有成本。若要再逼近，唯一大杠杆是改**单 actor 模型**（发送/ACK/重传全在一个协程、完全去锁），收益递减、风险更高，且仍到不了 95%。

---

## 4 · 版本分化：k 版 / n 版

hev 保留，做成两个独立可选的 Android product flavor（dimension = `engine`）。编译哪一版就只带哪一版的重资源；运行时由 `BuildConfig.USE_NATIVE_KERNEL` 自动选择转发引擎，不再硬编码。

| 项 | **k 版**（flavor `kernel`，默认） | **n 版**（flavor `hev`） |
|---|---|---|
| 引擎 | 纯 Kotlin `Tun2SocksEngine` | native `TProxyService` |
| NDK | 完全跳过，不碰 C 工具链 | 编译并打包 native 库 |
| 构建 | ~15s，无 native 任务 | 触发 `buildNdkBuild[arm64/armeabi]` |
| 产物 | `app-kernel-debug.apk`（无 .so） | `app-hev-debug.apk` + `libhev-socks5-tunnel.so` |
| Config | `USE_NATIVE_KERNEL = true` | `USE_NATIVE_KERNEL = false` |

共用同一套 App 与转发链路，只有引擎不同：

```
App → TUN → [ Tun2SocksEngine (k) | TProxyService (n) ] → Socks5Client(127.0.0.1) → 本地 Socks5Server → 上游
```

**实现要点**：native 编译只在检测到 hev 变体的构建任务时才 wire（`gradle.startParameter` 判定），因此 `assembleKernelDebug` 不会触发 NDK，从根本上消除 timeout。

**命名说明**：口头"n 版" = flavor `hev`。flavor id 保留 `hev` 而非 `native`，因为代码里 `USE_NATIVE_KERNEL` 中的 "native" 指的恰恰是自研 Kotlin 引擎（k 版），叫 `native` 会与之冲突。

**IPv6**：Builder 只配了 IPv4 地址与 `0.0.0.0/0` 路由，无 IPv6 地址/路由，故 IPv6 **不进 tun**、直接走物理网卡（旁路放行）——与原版 hev 一致。引擎对非 v4 返回 null 是空操作，**无需丢弃、无需处理**。副作用：IPv6 流量不受弱网整形/域名规则约束。

---

## 5 · 改动清单

**第一轮 · 连通性**

| 文件 | 改动 |
|---|---|
| `ip/Checksum.kt` | 修正向下取偶掩码（核心校验和修复） |
| `udp/UdpSession.kt` | DNS 回包 UDP 端口镜像修正 |
| `build.gradle.kts` | 拆分 kernel / hev 两个 flavor；native 仅 hev 变体编译 |
| `vpn/VpnServiceController.kt` | 改用 `BuildConfig.USE_NATIVE_KERNEL` 驱动分支 |
| `test/KernelUnitTest.kt` | 修复 Long/Int 编译错误 + 校正校验和期望值 |
| `Tun2SocksEngine.kt` | TUN fd 设非阻塞，stop 可及时中断读循环 |
| `tcp/TcpConnection.kt` | 移除每包 hex 日志刷屏 |

**第二轮 · 稳定性与负载**

| 文件 | 改动 |
|---|---|
| `Tun2SocksEngine.kt` | 新增空闲连接 reaper（§2.1） |
| `tcp/TcpConnection.kt` | 每连接 `lastActivityMs` 活跃时间戳（§2.1） |
| `udp/UdpSession.kt` | 下行中继挂起等待消除忙等（§2.2） |
| `Tun2SocksConfig.kt` | UDP 空闲回收 60s→30s（§2.2） |
| `tun/TunWriter.kt` | 阻塞队列 → 挂起式 Channel，EAGAIN 用 delay（§2.3） |
| `kernel/KernelDispatchers.kt`（新） | core / connIo dispatcher 隔离（§2.4） |
| `Tun2SocksEngine.kt` · `tun/TunWriter.kt` | 核心读写循环改用 `KernelDispatchers.core`（§2.4） |
| `socks/Socks5Client.kt` · `tcp/TcpConnection.kt` · `udp/UdpSession.kt` | 连接阻塞 I/O 改用 `KernelDispatchers.connIo`（§2.4） |

**第三轮 · 下载吞吐**

| 文件 | 改动 |
|---|---|
| `tcp/TcpConnection.kt` | 发送侧流控（§3.1）；窗口缩放 + WScale/MSS 选项（§3.2）；`sendLock` 串行化发送状态（§3.3）；批量发送 + 单 RTO 定时器 + 读/发流水线（§3.4） |
| `tcp/TcpSegment.kt` | `build` 支持 TCP 选项（供 SYN-ACK 的 MSS/WScale） |
| `Tun2SocksEngine.kt` | 解析 App SYN 的 window-scale，传入连接 |
| `socks/Socks5Client.kt` | 环回 socket 收发缓冲扩容 |
| `forwarding/Socks5Server.kt` | 中继流水线化；上游/客户端 socket 缓冲扩容；空操作 shaper 时旁路 `delayedPump` |
| `shaping/TrafficShaper.kt` | 新增 `isNoOp()` 供直拷旁路判断 |
| `security/SecurityPolicy.kt` | 拷贝缓冲 16KB→64KB |

> 所有改动当前**未提交**。建议拆 commit：`fix: correct kernel checksum / DNS port / unit-test suite`、`build: split hev & kernel product flavors`、`fix: reap idle conns and isolate blocking I/O to survive load bursts`、`perf: fix download flow-control/window-scaling/concurrency and pipeline the relay`。

---

## 6 · 后续优先级 — 具体处理逻辑

三轮已清掉全部阻断项（连通性 + 稳定性 + 下载吞吐）。以下按修复后的实际风险排序，重点从"能不能通/能不能扛住/够不够快"转向"架构可扩展性、弱网健壮性与可维护性"。每项给出**问题 → 处理方法 → 验证方式**。

### P1-(-1) · 单 actor 模型（彻底去锁） `[性能，可选]`

**问题**：§3.4 后单连接 ~19MB/s，剩余瓶颈之一是 ACK 路径仍逐个抢 `sendLock`。发送/ACK/重传分散在多个协程，靠 `Mutex` 串行。

**处理方法**：把下行发送、ACK 处理、RTO 全部收进单个 actor 协程（`select` over inputChannel + downstreamChannel + rtoChannel），维护一个非阻塞的发送泵（窗口满则缓冲、ACK 开窗则续发，绝不在 actor 内阻塞等待），**完全移除 `sendLock`**。

**验证**：单连接吞吐进一步上升、CPU 下降；并发行为不变。

> ⚠️ 收益递减、风险高，且**仍到不了原速 95%**（架构固有成本，见 §3.5）。除非单连接吞吐是硬指标，否则不建议投入。

### P1-0 · 上游 socket 非阻塞化（NIO） `[架构，最高优先]`

**问题**：§2.4 的 dispatcher 隔离只是把连接 I/O 的线程上限从 64 抬到 256，**根子仍是"每连接一个阻塞线程"**——极端并发（大量长连接同时等待上游数据）下 `connIo` 仍会触顶，且几百个阻塞线程的内存开销可观。核心已隔离故不再全局冻结，但连接吞吐会封顶。

**处理方法**：把 `Socks5Client` 的上游 socket 从阻塞 `java.net.Socket` 改为非阻塞 `SocketChannel` + 单个 `Selector` 事件循环（一条 selector 线程服务所有连接的可读/可写事件，零 per-connection 阻塞线程），与本地 `Socks5Server` 已有的 `NioSelectorLoop` 对齐；`TcpConnection`/`UdpSession` 的中继改为由 selector 事件回调驱动，取消 `withContext(connIo){ read() }`。完成后可移除 `connIo` 的大线程池。

**验证**：B 站/多标签高并发下线程数保持个位到几十、内存平稳；连接数可远超 256 而不退化。

---

以下为第一轮遗留项，风险次于 P1-0：

### P1-1 · SYN-ACK 重传逻辑错误（弱网握手无法自愈）

**问题**：`retransmit()` 无论何种状态都发 `FLAG_ACK`。在 `SYN_RCVD` 状态下，若首个 SYN-ACK 在弱网中丢失，RTO 触发后发出的是一个裸 ACK（`seq = iss+1`），而不是重发 SYN-ACK（`seq = iss`, `SYN|ACK`）。对端会忽略这个裸 ACK 并继续重发 SYN，握手在丢包下永远无法完成——这正是本项目"弱网"主题最该扛住的场景。

**根因结构**：`sendQueue` 里的 `QueuedSegment` 只存了 `(seq, length)`，丢掉了原始 flags 与 payload，所以 RTO 时无法"原样重发"最早的未确认段，只能凑一个 ACK。

**处理方法**（推荐：通用化"重发最早未确认段"）：

1. 扩展 `QueuedSegment`，保存重建该段所需的信息——最简单是直接缓存**已构建好的 IP 包字节**（含当时的 seq/flags/payload），或保存 `flags + payload + seq` 三元组。
   ```kotlin
   private class QueuedSegment(
       val seq: Long, val length: Int,
       val flags: Int, val payload: ByteArray,   // 新增
   )
   ```
2. 把 `sendSegment` 拆成两条路径：
   - **新数据发送**：构建 → `onSendPacket` → 入队 → 推进 `sndNxt` → 启动 RTO（现有逻辑）。
   - **重发**：`resendOldest()` 取 `sendQueue.first()`，用**当前 rcvNxt**（ack 可更新）但**原始 seq/flags/payload** 重新构建并 `onSendPacket`，**不推进 sndNxt、不再次入队**。
   ```kotlin
   private suspend fun resendOldest() {
       val q = sendQueue.firstOrNull() ?: return
       val bytes = buildPacket(flags = q.flags, seq = q.seq,
                               ack = rcvNxt, payload = q.payload)
       onSendPacket(bytes)   // 仅重发，状态不变
   }
   ```
3. `retransmit()` 改为调用 `resendOldest()`，而非 `sendSegment(FLAG_ACK, empty)`。
4. **加重传上限**：连续重传 N 次（如 5）仍未被 ACK → `resetConnection()` 关闭，避免死连接无限重传占用资源。用一个 `retransmitCount` 计数，`processAck` 成功推进时清零。

**验证**：集成测试（见 P1-3）——制造"SYN 进入但吞掉第一个 SYN-ACK"，断言 RTO 后**再次**发出的是 `SYN|ACK` 且 `seq == iss`；随后喂入 ACK 应进入 ESTABLISHED。设备侧可临时对 downstream 注入丢包观察握手是否仍能建立。

### P1-2 · 握手 ACK 携带数据被丢弃

**问题**：`handleSynRcvd` 在 `isAck && ack==iss+1` 时切到 ESTABLISHED，但**不处理 `seg.payload`**。当对端把 ClientHello 与握手 ACK 合并在同一段发送（合法且部分客户端会这么做），这段 payload 被丢，且 `rcvNxt` 已被推进 → 我们后续会 ACK 掉从未转发的数据 → 上游收不到 ClientHello → 该连接 TLS 卡死。

**处理方法**（抽出统一的"数据摄取"逻辑，两处复用）：

1. 把 `handleEstablished` 里"处理 payload / 推进 rcvNxt / 回 ACK / 处理 FIN"的部分抽成 `ingestSegment(seg)`。
2. `handleSynRcvd` 成功转 ESTABLISHED 后，先把 `rcvNxt` 设为消费掉对端 SYN 的值（`synSeq+1`），随后若 `seg` 带 payload/FIN，则调用 `ingestSegment(seg)` 走正常投递：
   ```kotlin
   state = State.ESTABLISHED
   if (seg.payload.isNotEmpty() || seg.isFin) ingestSegment(seg)
   ```
3. 这样"数据+ACK 合并段"在转态的同一步就把数据 `writeToSocks` 并正确 ACK；纯 ACK 段则无副作用。

**验证**：集成测试喂入"ACK 且带 payload"的握手完成段，断言 payload 被写到 fake SOCKS 输出、`rcvNxt` 正确推进、回了 ACK。

### P1-3 · 握手 / 中继集成测试（防回归）

**问题**：现有单测只覆盖纯函数（校验和、解析、ICMP）。握手与中继这类端到端缺陷（本轮的 #1/#2/1.1/1.2）**无法被现有测试捕获**。

**处理方法**（内存级 harness，无需真机）：

1. `TcpConnection` 的 I/O 边界是两个回调/接口：`onSendPacket`（发往 tun）与 `Socks5Client`（上游）。测试时注入 fake：
   - `onSendPacket` = 收集发出的 IP 包到列表。
   - fake `Socks5Client.TcpConnection`，用内存管道（`PipedInputStream`/`PipedOutputStream` 或 `okio.Buffer`）承接上下行。
2. 用 `TcpSegment.build(...)` 造 SYN/ACK/data/FIN 段驱动状态机，断言：
   - **SYN-ACK 正确性**：`Ipv4Packet.parse` + `TcpSegment.parse` 解出发出的包，且**重算整包校验和 == 0**（直接守住 1.1 那类缺陷）。
   - seq/ack 递进、data 被转发到 fake SOCKS 输出、FIN 四次挥手序列正确。
3. 提供断言助手：
   ```kotlin
   fun assertChecksumsValid(ipPacket: ByteArray) {
       // 重算 IP 头校验和与 TCP 伪首部校验和，均应为 0
   }
   ```
4. UDP 侧同理：fake `UdpAssociation` + 内存 channel，断言 `sendToTun` 产出的 UDP 端口镜像正确、校验和为 0（守住 1.3）。

**注意**：`TcpConnection` 当前用协程 + `delay` 忙轮询，确定性测试较难。改成挂起驱动（见 P2-2）后测试可用 `runTest` 虚拟时钟，二者有协同——**建议先做 P2-2 再补 P1-3**。

### P2-1 · SOCKS 控制连接多余写入

**问题**：`UdpSession.sendToRelay` 每发一个数据报就向 SOCKS 控制 TCP 连接写一个多余的 `0` 字节。本地 `Socks5Server` 的 UDP 循环只用控制连接判活（读到 EOF 才断），这个写入是多余的、且污染协议语义。

**处理方法**：删除 `assoc.controlOutput.write(0); assoc.controlOutput.flush()` 两行即可。控制连接保持打开本身就够判活，无需写入。

**验证**：删除后 DNS/QUIC UDP 往返仍正常（真机打开网页 + `PakomoUdp` 日志无异常）。

### P2-2 · TCP actor 忙轮询 → 挂起驱动

**问题**：`runActor` 用 `inputChannel.tryReceive()` + `delay(1 或 10ms)` 轮询，单连接引入最多 ~10ms 延迟，多连接下 CPU 空转。

**处理方法**：改成挂起式消费。RTO 是**独立的 `rtoJob` 定时器**驱动，不依赖轮询循环，因此该循环可以纯事件驱动：

```kotlin
private suspend fun runActor() {
    try {
        for (seg in inputChannel) {          // 挂起直到有段或 channel 关闭
            processTcpSegment(seg)
            if (state == State.CLOSED) break
        }
    } catch (_: ClosedReceiveChannelException) { }
}
```

- 删除 `POLL_INTERVAL_MS` / `tryReceive` 轮询。
- `cleanup()` 里的 `inputChannel.close()`、以及 TIME_WAIT 的 `delay→closeConnection→close()` 都能让 `for` 循环解除挂起并退出——收敛路径不变。
- 附带收益：延迟下降、CPU 降低，且让 P1-3 的测试能用虚拟时钟确定性运行。

**验证**：吞吐/延迟对比（P3-3 的指标）；集成测试断言状态机行为不变。

### P3-1 · 命名去歧义

**问题**：`useNativeKernel` / `USE_NATIVE_KERNEL` 里 "native" 指 **Kotlin 自研引擎**，与"hev native 库"概念相反，易读错。

**处理方法**：机械重命名为 `USE_KOTLIN_KERNEL`（true = Kotlin 引擎）。涉及：`build.gradle.kts` 的 `buildConfigField`、`VpnRuntimeConfigStore`（字段+参数）、`VpnServiceController`、`WeakNetworkVpnService`（参数+分支判断）。纯改名，无行为变化。

### P3-2 · 日志分级 & 全量构建边界

- **日志**：保留的 per-connection `Log.i`（SYN / ESTABLISHED / SOCKS connected）在生产版应降为 `Log.d`，或用 `BuildConfig.DEBUG` 门控，避免线上刷日志。
- **全量构建**：`./gradlew build` 会因为任务名里含 hev 变体而给**两个变体都编译 native**（`path` 是全局的）。日常单独 `assembleKernelXxx` / `assembleHevXxx` 不受影响；CI 若只需一版，分别调用对应 assemble 任务即可，不要用笼统的 `build`。

### P3-3 · k 版 vs n 版长期评测

**处理方法**：在相同工作负载下对比两版 APK 的四类指标——

- **吞吐**：复用现有 `stats()` 的字节计数，跑固定大小下载测速。
- **延迟**：握手 RTT、首字节时间（可在引擎打点）。
- **CPU / 电量**：Android Studio Profiler / `dumpsys batterystats`。
- **内存**：Profiler heap。

据此决定长期主线；再评估是否引入 TCP 流控/拥塞控制、SACK、MSS/MTU 优化等增强（当前窗口固定 65535、无真正流控，大流量或高延迟下可能次优）。

---

## 附录 · 验证证据

- **握手计数**：修复前 `116 SYN / 0 ESTABLISHED`；修复后 `52 SYN / 52 SOCKS5 connected / 52 ESTABLISHED`（1:1:1）。
- **校验和**：设备抓包 IP 头字段 `0xBAFF`（= 仅首字节 `0x45` 的补码）；修复后应为 `0xE55E`，独立 Python 复算整包校验和 == 0。
- **变体构建**：`assembleKernelDebug` ~15s 且无 ndkBuild 任务；`assembleHevDebug` 触发 `buildNdkBuild[arm64-v8a]/[armeabi-v7a]` 并打包 `libhev-socks5-tunnel.so`（两 ABI）。
- **稳定性**：负载下 reaper 轨迹 tcp/udp 计数震荡不再单调爬向 1024；刷 B 站列表不再"流量瞬间归零"（IO 池耗尽冻结的日志签名：reaper 照常打印但连接表卡死 90s）。
- **下载吞吐**（清华镜像，原始 26–56MB/s 波动）：单连接 `670KB 处断` → **稳定 16.5–22MB/s、0 重传、完整下完 197MB**；4 连接并发 27MB/s+。App 接收窗口经 WScale 自动长到 5–6MB。
