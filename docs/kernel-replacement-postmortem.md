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

---

## 1 · 缺陷逐个剖析

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

## 2 · 版本分化：k 版 / n 版

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

## 3 · 本轮改动清单

| 文件 | 改动 |
|---|---|
| `ip/Checksum.kt` | 修正向下取偶掩码（核心校验和修复） |
| `udp/UdpSession.kt` | DNS 回包 UDP 端口镜像修正 |
| `build.gradle.kts` | 拆分 kernel / hev 两个 flavor；native 仅 hev 变体编译 |
| `vpn/VpnServiceController.kt` | 改用 `BuildConfig.USE_NATIVE_KERNEL` 驱动分支 |
| `test/KernelUnitTest.kt` | 修复 Long/Int 编译错误 + 校正校验和期望值 |
| `tun/TunWriter.kt` | 非阻塞 fd 下容忍 EAGAIN 重试 |
| `Tun2SocksEngine.kt` | TUN fd 设非阻塞，stop 可及时中断读循环 |
| `tcp/TcpConnection.kt` | 移除每包 hex 日志刷屏 |

> 所有改动当前**未提交**。建议拆两个 commit：`fix: correct kernel checksum / DNS port / unit-test suite` 与 `build: split hev & kernel product flavors`。

---

## 4 · 后续优先级 — 具体处理逻辑

本轮已清掉全部 P0 阻断项。以下按修复后的实际风险排序，重点从"能不能通"转向"弱网健壮性、可维护性与性能"。每项给出**问题 → 处理方法 → 验证方式**。

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
