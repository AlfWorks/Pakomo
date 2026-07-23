# Android 非 Root 真机弱网模拟工具 · 实施计划（Pakomo）

> 本文件是对 [android-weak-network-simulator-plan.md](android-weak-network-simulator-plan.md) 的响应，交付第 20 节要求的 30 项内容，并在第 24 节逐一回答第 18 节的 27 个问题。
> 定位：**非 Root / 本地处理 / 无遥测 / 无广告 / 无远程上报 / 可审计 / 可重复测试 / 支持包名 + 域名组合规则**。

---

## 0. 关键决策速览（TL;DR）

| 决策点 | 结论 | 理由 |
|---|---|---|
| 最低版本 | **Android 10 / API 29**，target 最新稳定 | `getConnectionOwnerUid()` 从 API 29 起可用，是可靠的连接归属方案 |
| 转发核心（MVP） | **纯 Kotlin split-TCP/UDP 用户态转发**，无 native `.so` | 最易审计、无供应链风险、满足“禁止无法审计的预编译 .so” |
| 转发核心（性能演进） | 热路径可选下沉到 **Rust + smoltcp（源码编译、校验和锁定）** | 性能与 TCP 正确性更强，仍满足源码可审计 |
| 是否自研完整 TCP/IP 栈 | **否**，采用“TCP 半连接中继（split-TCP）”而非重写协议栈 | 见 Q3；重写完整栈风险与成本远高于收益 |
| 是否用 SOCKS 型核心 | **否**（tun2socks / hev-socks5-tunnel 不选） | proxy 导向，难以按流插入弱网 / 域名 / UID 钩子 |
| 域名识别 | **DNS(A) + TLS SNI**，尽力识别、安全降级、仅内存 | 见第 10 节 |
| 弱网算法 | 上下行**独立** Token Bucket 限速 + 延迟时间轮 + 概率丢包 + QueueGuard | 见第 11 节 |
| 并发模型 | Kotlin Coroutines + 单读线程 + 分片 worker + 背压 Channel | 见第 7 节 |
| 存储 | Room（规则/配置/设置）+ DataStore（偏好）+ 纯内存（DNS/SNI/Flow） | 见第 13 节 |
| 遥测 / 上报 | **零**。无任何联网 SDK，Release 关闭详细日志 | 见第 16、18 节 |

---

## 1. 项目架构图

```text
┌───────────────────────────── UI 层 (Jetpack Compose) ─────────────────────────────┐
│ dashboard · appselector · ruleeditor · profileeditor · diagnostics · security       │
└───────────────┬─────────────────────────────────────────────────────────────────────┘
                │ StateFlow / 命令
┌───────────────▼───────────────── 应用/领域层 ─────────────────────────────────────────┐
│ RuleEngine · RuleConflictDetector · RuleRepository · ProfileRepository · SettingsStore │
└───────────────┬─────────────────────────────────────────────────────────────────────┘
                │ 只读快照（不可变规则表）
┌───────────────▼───────────────── VPN / 转发内核（前台服务进程内）────────────────────────┐
│  WeakNetworkVpnService                                                                 │
│   ├─ TunInterfaceManager   （建立/销毁 TUN，配置路由与 MTU）                              │
│   ├─ SocketProtector        （统一 protect() 所有真实 socket）                            │
│   ├─ PacketReader/Writer     （TUN fd 单读单写）                                          │
│   ├─ packet/                 Ipv4Parser · TcpParser · UdpParser · DnsParser · TlsSni     │
│   ├─ forwarding/             TcpForwarder · UdpForwarder · FlowTable · ConnectionCleanup │
│   ├─ ownership/              ConnectionOwnerResolver · UidPackageResolver                │
│   ├─ domain/                 DnsDomainResolver · SniDomainResolver · DomainCache · Matcher│
│   ├─ shaping/                WeakNetworkEngine（DelayScheduler·TokenBucket·Loss·QueueGuard）│
│   └─ diagnostics/            EventLogger · StatisticsCollector · LogRedactor             │
└───────────────┬─────────────────────────────────────────────────────────────────────┘
                │ protect() 后的真实 Socket
        ┌───────▼────────┐
        │  真实网络 / OS   │ →→→ 目标服务器
        └────────────────┘
```

进程模型：VPN 与转发内核运行在**前台服务**中（可置于独立 `:vpn` 进程以隔离崩溃并便于内存上限控制）；UI 在主进程。两者通过 Repository + Service 绑定通信，规则以**不可变快照**下发，避免转发线程读到半更新状态。

---

## 2. 数据流图

```text
应用发包
  ↓ 内核路由到 TUN
TUN fd.read()  ── PacketReader（单线程，零拷贝 ByteBuffer 池）
  ↓
Ipv4Parser（长度/边界校验）
  ├─ TCP → TcpParser → FlowKey(五元组)
  │     ├─ 新流：ConnectionOwnerResolver.getUid() → UidPackageResolver → packages
  │     │        建立 FlowContext，启动 split-TCP 中继（protect 后的 Socket 连目标）
  │     ├─ ClientHello：TlsSniResolver.extractSni() → FlowContext.domain
  │     └─ 数据段 → RuleEngine.match(FlowContext) → WeakNetworkEngine.enqueue(dir, bytes)
  ├─ UDP → UdpParser
  │     ├─ dstPort==53 → DnsParser（请求记录 qname；响应写入 DomainCache: 域名→IP→TTL）
  │     └─ 其它 → FlowContext（datagram 中继）→ RuleEngine.match → WeakNetworkEngine
  └─ IPv6 → 按第 4 节策略：默认不注册 IPv6 路由（旁路），UI 明示
  ↓
WeakNetworkEngine：延迟队列 + 抖动 + 概率丢包 + Token Bucket（上/下行独立）+ QueueGuard
  ↓ 到期 & 令牌足够
真实 Socket（已 protect）→ 目标；返回方向对称经过同一引擎（下行）
  ↓
PacketWriter → TUN fd.write() → 回注到应用
```

**关键不变量**：弱网引擎只能**延迟或丢弃**字节，**绝不**修改目标 IP/端口/Payload（威胁模型 B）。

---

## 3. 模块职责

见输入文档第 14 节目录结构，本计划完整沿用。职责补充：

- **vpn/**：生命周期、TUN、`protect()`、前台通知、网络监听（`ConnectivityManager` 回调驱动重建）。
- **packet/**：**纯解析器，无副作用、无分配热点**；所有解析器强制长度上限与边界检查，可 Fuzz。
- **forwarding/**：split-TCP 半连接中继 + UDP datagram 中继；`FlowTable` 维护连接生命周期与上限。
- **ownership/**：每流一次 UID 查询并缓存；失败按第 9 节降级。
- **domain/**：DNS/SNI → `DomainCache`（内存、按 TTL 失效、容量上限）→ `DomainMatcher`（精确/通配/子域）。
- **shaping/**：弱网引擎，上下行独立；`QueueGuard` 是内存安全的最后防线。
- **rules/**：统一规则模型 + 优先级 + 冲突检测。
- **storage/**：Room + DataStore + 可选 Keystore；DNS/SNI/Flow **不持久化**。
- **diagnostics/**：默认最小日志；诊断模式带过期与脱敏；导出走 SAF。
- **security/**：输入校验、依赖审计、安全策略常量、一键清除。

---

## 4. 核心数据结构

```kotlin
// 弱网配置（沿用输入文档，补充范围约束在 InputValidator 中强制）
data class NetworkProfile(
    val id: String, val name: String,
    val uploadKbps: Int?,          // null = 不限；>0
    val downloadKbps: Int?,        // null = 不限；>0
    val latencyMs: Long,           // >=0
    val jitterMs: Long,            // >=0, <= latency 的合理上限
    val packetLossPercent: Double, // 0.0..100.0；100 = 断网
    val isSystemPreset: Boolean = false, // 系统预设只读
    val enabled: Boolean = true,
)

data class WeakNetworkRule(
    val id: String, val name: String,
    val packages: Set<String>?,    // null/空 = 所有应用
    val domains: Set<DomainPattern>?, // null/空 = 所有域名
    val profileId: String,
    val priority: Int,             // 同具体度下数字大者优先
    val enabled: Boolean,
    val createdAt: Long, val updatedAt: Long,
)

data class DomainPattern(
    val raw: String,               // "api.example.com" 或 "*.example.com"
    val kind: Kind,                // EXACT / WILDCARD
    val includeSubdomains: Boolean,
) { enum class Kind { EXACT, WILDCARD } }

// 连接上下文（沿用输入文档）
data class FlowContext(
    val protocol: Int,             // IPPROTO_TCP / UDP
    val source: InetSocketAddress,
    val destination: InetSocketAddress,
    val uid: Int?,                 // null = 未识别
    val packages: Set<String>,     // 空集 = 未识别应用
    @Volatile var domain: String?, // 尽力识别
    @Volatile var matchedRuleId: String?,
    val createdAt: Long,
    @Volatile var lastActivityAt: Long,
)

// 规则匹配的最终判定（供 UI 命中预览）
data class MatchResult(
    val rule: WeakNetworkRule?,    // null = 正常转发
    val profile: NetworkProfile?,
    val specificity: Specificity,  // PKG_DOMAIN > PKG > DOMAIN > GLOBAL > PASSTHROUGH
    val reason: String,
) { enum class Specificity { PKG_DOMAIN, PKG, DOMAIN, GLOBAL, PASSTHROUGH } }
```

配置导出格式（第 10 节输入文档约定）：

```json
{ "schemaVersion": 1, "profiles": [], "rules": [] }
```

导入严格校验：字段类型、数值范围、域名/包名格式、最大规则数、最大字符串长度、拒绝未知可执行字段（`InputValidator`）。

---

## 5. 技术栈选择

**Android 层**：Kotlin · Jetpack Compose (Material3) · Coroutines · Flow · Room · DataStore · Foreground Service（`specialUse`）· 可选 Android Keystore。
**构建**：Gradle（Kotlin DSL）· 版本目录 `libs.versions.toml` · 依赖锁定 · Gradle dependency verification（校验和/签名）。
**转发核心**：MVP 纯 Kotlin；演进期可选 Rust（`smoltcp`，MIT/Apache）经 `cargo-ndk` 从源码编译产出 `.so`，纳入校验和与 SBOM。
**CI**：GitHub Actions/内部 CI，执行单测、Lint、依赖漏洞扫描（OWASP dependency-check / OSV）、SBOM（CycloneDX）、Release APK 域名黑名单静态检查。

**禁用**：任何统计/崩溃/广告 SDK；运行时下载 native 库或规则脚本；闭源二进制核心；远程控制服务器。

---

## 6. 开源依赖推荐及许可证

| 用途 | 依赖 | 许可证 | 备注 |
|---|---|---|---|
| UI | AndroidX / Compose / Material3 | Apache-2.0 | 官方，无遥测 |
| 异步 | kotlinx-coroutines | Apache-2.0 | |
| 存储 | Room / DataStore | Apache-2.0 | |
| 序列化 | kotlinx-serialization | Apache-2.0 | 配置导入导出，非反射、可控 |
| 转发核心(可选) | smoltcp (Rust) | MIT / Apache-2.0 | 源码编译，无网络出站能力 |
| 测试 | JUnit5 · Turbine · Robolectric · kotlinx-coroutines-test | EPL/Apache/MIT | |
| Fuzz | Jazzer（JVM 覆盖引导 fuzz） | Apache-2.0 | 解析器 fuzz |
| SBOM | CycloneDX Gradle 插件 | Apache-2.0 | |
| 漏洞扫描 | OSV-Scanner / OWASP dependency-check | Apache-2.0 | CI |

**明确排除**：tun2socks / hev-socks5-tunnel / leaf 等 SOCKS 代理型核心——它们是“把 TUN 流量转给 SOCKS 服务器”的模型，与本项目“按流插入弱网 + 域名 + UID 匹配 + 禁止改目标”的需求不契合，且会引入代理语义与额外攻击面。NetGuard（GPLv3）作为**架构参考**而非依赖。

---

## 7. 线程与并发模型

```text
[TUN Reader]  单线程/单协程：fd.read() → ByteBuffer(来自对象池) → 解析头部 → 计算 FlowKey
     │  （只做无锁分发，绝不阻塞）
     ├─ 按 FlowKey 哈希分片到 N 个 worker（默认 = min(4, CPU)）
     ▼
[Flow Worker × N]  每分片单线程串行处理其名下所有流：
     - 新流建连（挂起式 protect + connect，不阻塞其它流）
     - 规则匹配（读不可变规则快照，无锁）
     - 交给 WeakNetworkEngine（每方向独立令牌桶 + 延迟队列）
     ▼
[Timer Wheel]  单线程时间轮驱动“延迟到期”回调，投递到对应 worker 的出站 Channel
     ▼
[TUN Writer]   单线程/单协程：合并回注，fd.write()
```

要点：
- **单读单写 TUN fd**（Android VpnService 要求，多线程写易错序）。
- 每条流固定归属一个 worker → 该流内部**天然有序、无锁**。
- 跨组件用**有界 Channel** 传递，形成背压；满则触发 `QueueGuard` 丢弃并计数（而非无限堆积）。
- 规则表以 `@Volatile` 不可变快照发布；更新时整体替换，转发侧永远读到一致视图。
- `ByteBuffer` 对象池减少 GC 抖动。
- 协程 `Dispatchers` 使用固定线程池，避免默认线程数膨胀。

---

## 8. TCP / UDP 转发方案

**TCP：split-TCP 半连接中继（不重写完整栈）**
- 对 TUN 中的 TCP 段维护**最小 TCP 状态机**：SYN → 本地代答 SYN-ACK 建立“设备侧半连接”；同时用 `protect()` 后的真实 `SocketChannel` 向目标建立“网络侧半连接”。
- 两侧字节流对接；本地侧负责 seq/ack、窗口、重传定时器的最小实现，网络侧交给 OS 的真实 TCP。
- 弱网引擎作用在**两侧之间的字节流**上（延迟/丢包/限速），因此 TCP 语义仍由真实内核栈保证，我们只塑形。
- MVP 用 Kotlin 实现该最小状态机；若吞吐/正确性不足，用 `smoltcp`（Rust）替换设备侧状态机，接口不变。

**UDP：datagram 中继**
- 每个 UDP 流建立一个 `protect()` 后的 `DatagramChannel` 连接目标；出入 datagram 经弱网引擎塑形。
- DNS（dstPort 53）在中继同时旁路解析，喂给 `DomainCache`。
- 空闲超时回收（UDP 无连接，靠 `FlowTable` 超时清理）。

**FlowTable 与清理**
- 连接上限、单连接缓存上限；LRU + 超时（`ConnectionCleanup`）。
- 网络切换/VPN 重建时整表失效并重建。

---

## 9. UID / 包名识别方案

- **API 29+**：`ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)` 用五元组查 UID。
- UID → 包名：`PackageManager.getPackagesForUid(uid)`。
- **每流只查一次**，结果缓存进 `FlowContext`；连接关闭/超时清理。
- **单应用会话优化**：若整个 VPN 只 `addAllowedApplication` 了一个应用，所有流直接归属该包，跳过查询。
- **降级（UID 查询失败）**：
  ```text
  无法识别应用
    → packages = 空集
    → 只匹配 “所有应用” 规则（packages==null 的规则）
    → 绝不匹配任何指定包名规则（避免误归属）
    → 记安全事件计数
  ```
- 严禁把一个应用的流量因识别失败而归到另一个应用（威胁模型 B）。

---

## 10. DNS / SNI 域名识别方案

**识别链路与优先级**（输入文档 6.3）：
```text
当前连接 TLS SNI  >  当前应用 DNS 映射  >  短期 IP/域名缓存  >  未知
```

- **DNS**：解析经 VPN 的 DNS 查询/响应，维护 `域名 → IP集合 → TTL → 来源(uid/flow)`；MVP 支持 A，后续 AAAA/CNAME；**严格按 TTL 失效**，容量上限，仅内存，退出即清。
- **SNI**：对 TCP TLS `ClientHello` **最小解析**取 SNI；只读握手元数据，不解密、不存全报文；设最大解析长度；解析失败**正常转发**不中断。
- **降 CDN 误匹配**：
  - 优先用**当前连接自身的 SNI**（最准）而非 IP 反查。
  - DNS 映射绑定**来源应用/流**，避免共享 CDN IP 被永久绑定单域名。
  - IP→域名缓存仅作最后兜底且短 TTL。
- **域名无法识别时安全降级**（输入文档 6.4）：`包名规则 → 全局规则 → 正常转发`，**绝不因无法识别域名而阻断**。
- **已知限制**（写入产品说明与测试计划）：DoH / Private DNS / ECH 会隐藏域名；QUIC(UDP) 识别有限；长连接/HTTP2/3 复用不再重握手 → 域名匹配是“尽力识别”。

---

## 11. 弱网算法方案

- **上下行独立**：两个方向各自维护 `TokenBucketLimiter` + `DelayScheduler` + `PacketLossController`，互不影响。
- **限速**：Token Bucket（`uploadKbps`/`downloadKbps`），桶容量对应可控突发；`null` = 关闭该方向限速。
- **固定延迟 + 抖动**：`effectiveDelay = latencyMs + rand(-jitter, +jitter)`（截断到 ≥0）；用**时间轮**调度到期，避免每包一个定时器。
- **丢包**：按 `packetLossPercent` 概率随机丢弃；`100%` = 完全断网（直接丢弃并短路，不占队列）。
- **贴近真实弱网**：延迟加抖动模拟排队/无线波动；丢包触发上层真实 TCP 的重传与拥塞退避，效果由真实内核栈自然产生（这正是 split-TCP 的价值——我们不假造 ACK 行为）。
- **防 OOM（QueueGuard）**：
  - 每方向队列**最大长度**、**最大字节数**、**最大驻留时间**三重上限。
  - 超限策略：丢弃最旧/拒绝入队（可配置），并**计数上报到统计**（可观察）。
  - 全局延迟队列**总内存上限**，触顶即进入旁路（直接转发或丢弃），记安全事件。
  - 参数入口经 `InputValidator` 严格范围校验，杜绝 `latency=Long.MAX` 之类放大。

---

## 12. 规则优先级方案

具体度固定序（输入文档 3.3）：
```text
包名+域名 > 包名 > 域名 > 全局 > 正常转发
```
- 同具体度：数字 `priority` 大者优先；再相同则 `updatedAt` 新者优先（或保存时禁止冲突，二选一，默认“最新优先 + UI 明示冲突”）。
- **不叠加**：只命中优先级最高且最具体的一条。
- **冲突检测**（`RuleConflictDetector`）：保存/编辑时检测同具体度、条件重叠且 `priority` 相同的规则，UI 标红并展示“最终命中结果”预览。
- `RuleEngine.match(FlowContext)` 返回 `MatchResult`，供转发与 UI 命中预览共用同一逻辑（避免两套实现分叉）。

---

## 13. 存储方案

| 数据 | 载体 | 持久化 | 备注 |
|---|---|---|---|
| 规则 / 弱网配置 | Room | 是 | 应用私有目录 |
| 用户偏好 | DataStore | 是 | 轻量 |
| DNS / SNI / FlowContext / 实时统计 | 内存 | **否** | 退出即清 |
| 可选加密密钥 | Android Keystore | 密钥不导出 | 见 Q19 |

要求：不保存完整历史流量；不自动上传备份；敏感配置从 **Auto Backup 排除**（见 Q20）；数据库迁移必须有测试；导出走 SAF 版本化 JSON；导入严格校验、禁止任意代码/脚本/动态表达式。

---

## 14. UI 页面规划

- **Dashboard**：VPN 状态、接管范围、生效规则数、实时上/下行速率、活跃连接数、丢包计数、延迟队列长度、启动/停止/**紧急恢复**。
- **AppSelector**：图标/名称/包名/搜索/多选/系统应用过滤；默认不选全部；全局接管二次确认；系统关键应用（更新/电话/企业认证/密码管理器）警告。
- **RuleEditor**：应用条件、域名条件（精确/通配/含子域）、绑定配置、优先级、启用、**命中预览**、冲突检测。
- **ProfileEditor**：内置只读预设 + 复制后编辑；参数范围校验实时反馈。
- **Diagnostics**：诊断模式开关（带醒目警告、自动过期、重启即关）、日志占用、导出（SAF，导出前展示将包含的数据类型）、一键清除。
- **Security & Privacy**：是否含遥测（恒为“无”）、当前权限、当前接管应用、诊断日志状态与占用、一键清除全部本地数据、开源许可证、隐私说明、安全设计说明。

---

## 15. 日志与诊断方案

- **Release 默认最小日志**：VPN 启停、配置加载结果、转发错误码、队列溢出计数、规则命中计数、异常类型、无 Payload 性能统计。
- **默认不记录**：Payload / Header / Cookie / Token / 完整 DNS / 完整 ClientHello / 账号 / Body。
- **诊断模式（仅 Debug 或显式开启）**：包名、目标域名、目标 IP:端口、UID、规则 ID、连接起止时间；开启显警告、自动过期、重启恢复关闭、文件大小与数量上限、一键清除、导出前预告数据类型。
- **脱敏（`LogRedactor`）**：Token/Authorization/Cookie 关键字过滤；内网 IP 可脱敏；包名/域名可选哈希；从源头不采集原始 Payload。

---

## 16. 安全威胁模型

沿用输入文档第 7.2 节 A–E，逐项落到模块与测试：

- **A 流量泄露**：核心零远程上报；无联网 SDK；Release 关详细日志；导出用户触发 + SAF；不静默写公共存储。→ 测试 16.2#1/2/5/13。
- **B 转发到错误目标**：所有真实 socket 过 `SocketProtector`；五元组/UID/目标严格绑定；弱网引擎禁改目标与 Payload；连接复用/端口重用单测。→ 测试 16.1 + 16.2。
- **C 本地敏感数据泄露**：私有目录；可选 Keystore 加密；一键清除；DNS/SNI 仅内存；不存连接历史；Auto Backup 排除。→ 测试 16.2#6/7。
- **D 恶意/畸形包**：解析器长度与边界校验、不信任声明长度；连接数/单连接/总队列上限；超限旁路 + 安全事件；解析器 Fuzz。→ 第 23 节。
- **E 依赖与供应链**：开源可审计依赖、锁版本、SBOM、Gradle dependency verification、禁运行时下载 native、核心转发源码审计。→ 第 19 节。

---

## 17. 权限清单

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<!-- QUERY_ALL_PACKAGES 默认不申请，优先 <queries> + 用户手输包名 -->
```
- **VpnService** 通过 `BIND_VPN_SERVICE`（系统在 `<service>` 上声明，非普通权限）。
- **包名可见性**：优先 `<queries>` 声明有限范围 + 仅列可启动应用 + 允许手输包名；`QUERY_ALL_PACKAGES` 仅在内部企业分发且确有需要时评估。
- **前台通知**必须显示：弱网模拟运行中、接管应用数、生效规则、停止按钮。
- **禁止**：位置/联系人/短信/电话/麦克风/相机/日历/无障碍/设备管理员/通知读取/全盘文件访问。

---

## 18. 隐私与数据处理说明

- 全部流量处理在本机；不上传业务流量、DNS、SNI、连接元数据、日志。
- 无广告/统计/崩溃/追踪 SDK；不 MITM；不生成/安装/请求 CA 信任；不记录 Payload；默认不导出 PCAP。
- DNS/SNI/连接缓存仅内存，退出即清；不保存完整连接历史。
- 提供“一键清空日志、规则、缓存”。
- 隐私说明与安全设计说明在 Security 页面内可见（随包发布，非远程拉取）。

---

## 19. 依赖与供应链安全方案

- 优先开源、活跃、许可证兼容依赖；**锁定版本**；保留依赖清单与许可证清单。
- **Gradle dependency verification**（校验和 + 签名）；CI 跑 OSV/OWASP 漏洞扫描；生成 **CycloneDX SBOM**。
- 禁运行时下载 native 库或规则脚本；Rust 核心（若启用）**从源码编译**并纳入校验和与源码审计。
- Release APK 静态检查：广告/统计/崩溃上报域名黑名单、硬编码密钥、调试开关、明文存储。

---

## 20. 分阶段开发计划（沿用输入文档第 15 节，落到里程碑）

| 阶段 | 内容 | MVP 边界 |
|---|---|---|
| **Phase 0** | 技术验证 PoC：VpnService 建/销、指定应用进 VPN、`protect()`、TCP/UDP 基础转发、DNS、`getConnectionOwnerUid()` 机型行为、转发核心选型 | 出 PoC + 技术风险报告 + 依赖审计报告 + API29 决定 |
| **Phase 1** | VPN 基础：权限申请、前台服务、TUN、IPv4 路由、应用选择、全局接管、启停、紧急恢复 | ✅ |
| **Phase 2** | TCP/UDP 转发：连接表、超时清理、网络切换恢复 | ✅ |
| **Phase 3** | 弱网引擎：延迟/抖动/丢包/上下行限速/断网/预设 | ✅ |
| **Phase 4** | 多应用与包名规则：UID 查询与缓存、FlowContext、包名规则 | ✅ |
| **Phase 5** | 域名规则：DNS、TTL 缓存、SNI、精确/通配域名、包名+域名组合、冲突检测 | 里程碑 2 |
| **Phase 6** | 稳定性与内部发布：配置持久化、导入导出、日志导出、实时统计、多机型、Wi-Fi/移动切换、睡眠唤醒、异常恢复、SBOM、安全审计 | 里程碑 2 |

**MVP = Phase 0–4**（Android 10+ / IPv4 / TCP+UDP / 包名规则 / 弱网引擎，无域名规则、无 IPv6、无日志导出）。

---

## 21. 每阶段验收标准

直接采用输入文档第 15 节各阶段“验收 + 安全验收”条目，作为 Definition of Done。关键红线：
- 每阶段安全验收全部通过方可进入下一阶段。
- Phase 0 抓包必须确认工具只连用户目标服务、不连开发者服务器，所有真实 Socket 均被 `protect()`。
- Phase 3 队列内存受控、超限不 OOM、参数严格校验。
- Phase 6 依赖锁定 + SBOM + Release 关调试日志 + Auto Backup 策略明确 + 完成内部安全审计。

---

## 22. 测试策略

- **单元测试**：解析器（正常/边界/畸形）、TokenBucket、时间轮延迟、丢包统计、DomainMatcher（精确/通配/子域）、RuleEngine 优先级、冲突检测、导入校验、DB 迁移。
- **集成测试**：split-TCP 中继回环、UDP 回环、DNS 缓存 TTL 失效、UID 降级路径。
- **真机测试**：多机型（不同厂商 ROM）、Wi-Fi/移动数据切换、前后台、VPN 重启、系统杀进程、长连接、多应用并发、CDN 多 IP。
- **端到端可测性**：弱网参数效果可测量、可重复（延迟/限速/丢包误差在目标范围内）。
- 覆盖输入文档 16.1 全部功能项与 16.2 全部安全项。

---

## 23. Fuzz 测试策略

- 用 **Jazzer**（JVM 覆盖引导）对 `Ipv4Parser / TcpParser / UdpParser / DnsParser / TlsClientHelloParser` 做 fuzz：
  - 种子语料：真实抓包样本（脱敏）+ 手工畸形样本。
  - 断言：不崩溃、不 OOM、不越界、不信任声明长度、超长字段被安全截断。
- 对**配置导入**做畸形数据 fuzz（错误类型、超范围、超长字符串、未知字段、超量规则）。
- 资源消耗测试：大连接数、大队列、超长域名、异常 TLS 报文 → 验证上限与旁路策略生效。
- CI 定时跑 fuzz 语料回归，崩溃样本入回归库。

---

## 24. 性能目标

| 指标 | 目标（初版，真机基准后校准） |
|---|---|
| 空闲 CPU | < 1% |
| 正常转发 CPU（中等吞吐） | < 8% 单核 |
| 高吞吐 CPU | 可持续，不掉帧、不 ANR |
| 内存占用 | 稳态 < 120 MB，含队列上限封顶 |
| 最大并发连接 | 可配置，默认上限 1024，超限拒绝并计数 |
| 转发吞吐 | 无塑形时 ≥ 设备物理链路的 70%（MVP 基线，后续优化） |
| 附加延迟误差 | 目标 ±10% 或 ±20ms（取大） |
| 限速误差 | ±10% |
| 丢包率误差 | ±2 个百分点 |
| 规则匹配开销 | 每包 < 数 µs（不可变快照 + 哈希） |
| 域名识别命中率 | 尽力，非 SLA，记录统计供评估 |
| 电量 | 前台服务常驻下可接受，提供停止入口 |

---

## 25. 兼容性目标

- **minSdk 29 / target 最新稳定**。
- 覆盖主流厂商 ROM（Pixel/Samsung/Xiaomi/OPPO/vivo 等）的 VpnService 行为差异，Phase 0 出机型行为报告。
- 处理多用户 / 工作资料 / 企业设备策略下的行为（测试 16.2#15）。
- IPv6：MVP 默认不注册 IPv6 默认路由（旁路）并 UI 明示；不静默丢弃（见 Q 中 IPv6 决策）。

---

## 26. 风险与降级方案

| 风险 | 降级 / 缓解 |
|---|---|
| UID 查询失败/机型不支持 | 只匹配“所有应用”规则，绝不误归属（第 9 节） |
| 域名无法识别（DoH/ECH/QUIC/长连接复用） | 降级到包名→全局→正常转发，不阻断（第 10 节） |
| 延迟队列膨胀 | QueueGuard 三重上限 + 旁路 + 计数（第 11 节） |
| 畸形包 | 解析器边界校验 + Fuzz（第 23 节） |
| split-TCP 正确性/性能不足 | 设备侧状态机替换为 smoltcp（Rust，源码编译） |
| CDN 共享 IP 误匹配 | 优先 SNI、DNS 绑定来源、IP 缓存短 TTL 兜底 |
| 网络切换/VPN 重建 | ConnectivityManager 回调驱动整表失效重建 |
| 系统杀进程遗留 VPN 状态 | onRevoke/onDestroy 清理，重启自愈，不残留无效状态 |

---

## 27. MVP 工作量 & 完整内部版本工作量

> 估算基于 1 名有 Android + 网络经验的工程师，含测试与联调；范围性估算，非承诺。

| 里程碑 | 内容 | 估算（人日） |
|---|---|---|
| Phase 0 | PoC + 选型 + 风险报告 | 8–12 |
| Phase 1 | VPN 基础 | 8–10 |
| Phase 2 | TCP/UDP 转发（split-TCP 是主要不确定性） | 15–25 |
| Phase 3 | 弱网引擎 | 8–12 |
| Phase 4 | 多应用 + 包名规则 | 6–8 |
| **MVP 小计** | Phase 0–4 | **≈ 45–67 人日** |
| Phase 5 | 域名规则（DNS/SNI/匹配/冲突） | 12–18 |
| Phase 6 | 稳定性 + 内部发布 + 安全审计 + SBOM | 15–22 |
| **完整内部版小计** | Phase 0–6 | **≈ 72–107 人日** |

若 Phase 0 判定需 Rust/smoltcp 热路径，Phase 2 上限再增 10–15 人日。

---

## 28. 项目目录结构

沿用输入文档第 14 节 `app/` 模块划分（ui / vpn / packet / forwarding / ownership / domain / shaping / rules / storage / diagnostics / security），并补充：

```text
Pakomo/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml          # 版本目录 + 依赖锁定
├── gradle/verification-metadata.xml   # Gradle dependency verification
├── docs/
│   ├── SECURITY.md  PRIVACY.md  THREAT_MODEL.md  SBOM.md  LICENSES.md
├── app/src/main/kotlin/com/pakomo/... # 第 14 节模块
├── app/src/test/  app/src/androidTest/
└── native/ (可选) rust-core/          # smoltcp 集成，cargo-ndk 从源码构建
```

---

## 29. 第一阶段可直接执行的任务列表（Phase 0 → Phase 1 开工清单）

**T0 工程初始化**
1. 初始化 Git 仓库 + `.gitignore` + Gradle(KTS) + 版本目录 + minSdk 29。
2. 配置 Compose/Coroutines/Room/DataStore 依赖，启用 dependency verification 骨架。
3. 建立第 14 节包结构空壳 + `SecurityPolicy` 常量（全局上限、超时、队列阈值）。

**T1 VpnService 骨架（Phase 0 验证）**
4. `WeakNetworkVpnService`：建立/销毁 TUN，`Builder` 配 MTU/IPv4 地址/路由/DNS。
5. `SocketProtector`：统一 `protect(socket)` 封装 + 单测保证“所有出站 socket 必过它”。
6. `addAllowedApplication` 单应用会话打通；前台服务 + 通知（运行中/接管数/停止）。

**T2 最小转发闭环（Phase 0 验证）**
7. `PacketReader/Writer` + `Ipv4Parser`（带边界校验）+ `TcpParser`/`UdpParser`。
8. UDP datagram 中继打通（先 DNS 场景，验证 `protect` 生效、无回环）。
9. split-TCP 最小中继打通一条 HTTP/HTTPS 连接（验证正确性）。

**T3 归属与验证**
10. `ConnectionOwnerResolver.getConnectionOwnerUid()` 在 2–3 台目标机型上验证行为，出机型报告。
11. 抓包验证：工具只连目标服务、不连开发者服务器；所有 socket 已 `protect`。

**T4 Phase 0 交付**
12. 输出 PoC、技术风险报告、第三方依赖审计报告、API29 支持最终决定、转发核心选型结论。

**Phase 1 收尾**
13. VPN 权限申请 UI 流程、全局接管二次确认、紧急恢复、停止即恢复、杀进程自愈。

---

## 30. 第 18 节 27 个问题的逐一回答

1. **整体技术架构**：Kotlin/Compose 应用层 + 前台服务内的用户态转发内核（TUN→解析→归属→域名→规则→弱网→protect socket），规则不可变快照下发，见第 1、7 节。
2. **推荐哪个开源转发核心**：MVP **不引入代理型核心**，用纯 Kotlin split-TCP/UDP 中继；性能演进期用 **smoltcp（Rust，MIT/Apache，源码编译）**。不选 tun2socks/hev-socks5-tunnel（SOCKS 代理导向，不契合按流塑形）。
3. **为何不从零写完整 TCP/IP 栈**：完整栈（拥塞控制、重传、窗口、SACK、边缘用例）工程量与正确性风险极高，且我们的目标是**塑形**而非替代内核栈；split-TCP 让真实 OS 栈承担端到端语义，我们只在中间插入延迟/丢包/限速，成本与风险都低一个量级。
4. **Kotlin / Rust / C++ 分工**：Kotlin 承担 UI、规则、存储、归属、域名、弱网引擎与 MVP 全部转发；Rust（可选，仅热路径）承担设备侧 TCP 状态机（smoltcp）；**不使用 C/C++ 闭源核心**。
5. **上下行独立限速**：每方向独立 `TokenBucketLimiter`（分别用 `uploadKbps`/`downloadKbps`），独立延迟队列与丢包控制器，互不影响（第 11 节）。
6. **延迟/丢包贴近真实**：延迟+抖动模拟排队与无线波动；丢包由真实 TCP 栈触发重传/拥塞退避（split-TCP 不伪造 ACK，故行为真实），效果自然涌现（第 11 节）。
7. **防延迟队列 OOM**：QueueGuard 三重上限（长度/字节/驻留时间）+ 全局总内存上限 + 超限旁路/丢弃 + 计数可观察 + 参数范围校验（第 11 节）。
8. **准确查 UID/包名**：`getConnectionOwnerUid()`（API29）按五元组查 UID → `getPackagesForUid()`；每流一次并缓存（第 9 节）。
9. **UID 失败降级**：只匹配“所有应用”规则，绝不匹配指定包名规则、绝不误归属，记安全事件（第 9 节）。
10. **DNS+SNI 识别链路**：DNS 响应建 `域名→IP→TTL`（仅内存、按 TTL 失效）；TCP ClientHello 取 SNI；优先级 SNI > DNS > IP 缓存 > 未知（第 10 节）。
11. **降 CDN 误匹配**：优先用连接自身 SNI；DNS 映射绑定来源应用/流；IP→域名仅短 TTL 兜底（第 10 节）。
12. **QUIC/DoH/Private DNS/ECH**：明确为“尽力识别”的已知限制——QUIC(UDP) 域名识别有限、DoH/Private DNS 隐藏普通 DNS、ECH 隐藏 SNI；无法识别一律安全降级到包名→全局→正常转发，不阻断（第 10、26 节）。
13. **多规则冲突检测**：`RuleConflictDetector` 在保存时检测同具体度、条件重叠、priority 相同的规则；UI 标红并展示最终命中预览；判定与转发共用 `RuleEngine`（第 12 节）。
14. **线程/并发模型**：单读单写 TUN + FlowKey 哈希分片 worker（流内有序无锁）+ 时间轮 + 有界 Channel 背压 + 不可变规则快照（第 7 节）。
15. **网络切换/VPN 重建**：`ConnectivityManager` 回调驱动 FlowTable 整表失效重建；`onRevoke/onDestroy` 清理，重启自愈（第 26 节）。
16. **确保自身不上传/泄露**：核心零远程上报、无联网 SDK、Release 关详细日志、导出用户触发 + SAF、抓包验证只连目标服务（第 16、18、19 节 + 测试 16.2#1）。
17. **依赖/供应链审计**：开源可审计依赖 + 锁版本 + Gradle dependency verification + OSV/OWASP 扫描 + CycloneDX SBOM + 核心源码审计 + 禁运行时下载（第 19 节）。
18. **日志脱敏与诊断模式**：Release 最小日志、诊断模式带警告/过期/重启即关/上限/一键清、`LogRedactor` 过滤 Token/Cookie/Authorization、内网 IP 脱敏、包名/域名可哈希（第 15 节）。
19. **配置是否 Keystore 加密**：默认规则库存应用私有目录已隔离；**提供可选 Keystore 加密**（用于含内部域名等敏感规则的场景），密钥不导出、不随备份。属可选增强，非 MVP 阻塞项。
20. **Auto Backup 配置**：默认排除敏感配置——`android:fullBackupContent` / `dataExtractionRules` 明确 exclude 规则库与设置；或整体关闭 `allowBackup`；DNS/SNI 本就不持久化（第 13、16 节）。
21. **最低 Android 版本**：**API 29 / Android 10**，因 `getConnectionOwnerUid()` 由此可用，是可靠归属基础（第 0 节）。
22. **MVP 与完整版工作量**：MVP（Phase 0–4）≈ 45–67 人日；完整内部版（Phase 0–6）≈ 72–107 人日（第 27 节）。
23. **每阶段验收标准**：采用输入文档第 15 节各阶段“验收+安全验收”作为 DoD，安全验收全过方进入下阶段（第 21 节）。
24. **第一阶段可直接拆的任务**：见第 29 节 T0–T4 + Phase 1 收尾，13 项可执行任务。
25. **应用哪些测试**：单元（解析器/塑形/匹配/导入/迁移）、集成（中继回环/DNS TTL/降级）、真机（多机型/切网/杀进程/并发/长连接/CDN）、Fuzz（解析器 + 导入 + 资源消耗），见第 22、23 节。
26. **Release 如何证明无广告/遥测/上报**：无联网 SDK 依赖（SBOM 佐证）+ Release 关详细日志 + APK 域名黑名单静态检查 + 抓包验证外联 + 静态分析硬编码/明文（第 19 节 + 测试 16.2#13）。
27. **需产出的安全文档**：`SECURITY.md`、`PRIVACY.md`、`THREAT_MODEL.md`、`SBOM.md`（CycloneDX）、`LICENSES.md`（依赖许可证清单），见第 28 节 `docs/`。

---

## 附：IPv6 决策（输入文档 4.2 要求 Agent 给出推荐）

**推荐（MVP）：方案 1 —— 不向 TUN 注册 IPv6 默认路由，让 IPv6 绕过 VPN，并在 UI 明示。**
- 安全影响：被明示旁路的 IPv6 流量**不经弱网塑形也不被本工具接管**，用户需知悉“IPv6 场景弱网可能不生效”；但**不会静默丢弃**（满足输入文档 4.2 的“不得静默丢弃”）。
- 不选“完全关闭 IPv6”（可能影响设备正常连接），不选“完整实现 IPv6 转发”（MVP 成本过高）。
- Phase 6 再评估完整 IPv6 转发。UI 的 Security 页面必须展示当前 IPv6 处理策略。
