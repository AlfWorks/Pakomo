# Android 非 Root 真机弱网模拟工具：需求与实施计划输入

## 1. 项目概述

### 1.1 项目目标

开发一个运行在 **非 Root Android 真机** 上的本地弱网模拟工具，用于软件、游戏及移动端服务测试。

工具基于 Android `VpnService` 接管本机网络流量，在设备本地完成流量转发和网络条件模拟，不依赖：

- Root 权限
- Android 模拟器
- 外部路由器
- 外部代理设备
- 电脑持续连接
- 远程 VPN 节点
- HTTPS 中间人解密
- 用户安装 CA 证书

工具的核心目标是：

1. 在真实 Android 设备上模拟可控、可重复的弱网环境。
2. 支持全局、指定应用、指定域名以及“指定应用 + 指定域名”的组合规则。
3. 默认不读取、不保存、不上传业务数据内容。
4. 作为内部测试基础设施，优先保证稳定性、可审计性和信息安全。

---

## 2. 功能范围

### 2.1 弱网能力

第一阶段至少支持：

- 固定延迟
- 网络抖动
- 随机丢包
- 上传限速
- 下载限速
- 完全断网
- 一键恢复正常网络

建议参数模型：

```kotlin
data class NetworkProfile(
    val id: String,
    val name: String,
    val uploadKbps: Int?,
    val downloadKbps: Int?,
    val latencyMs: Long,
    val jitterMs: Long,
    val packetLossPercent: Double,
    val enabled: Boolean
)
```

约定：

- `uploadKbps == null`：不限上传速度。
- `downloadKbps == null`：不限下载速度。
- `latencyMs == 0`：不附加固定延迟。
- `jitterMs == 0`：不附加抖动。
- `packetLossPercent == 100`：完全断网。
- 上行和下行应分别维护调度和限速状态。

建议实现方式：

- 延迟：定时优先队列、时间轮或高效延迟队列
- 抖动：基于基础延迟的随机偏移
- 丢包：按概率随机丢弃
- 限速：Token Bucket 或 Leaky Bucket
- 队列保护：设置最大队列长度、最大内存及超时淘汰策略

---

## 3. 统一规则模型

不要将“全局模式”“应用模式”“域名模式”实现为三套互斥逻辑。

应统一建模为：

```text
应用条件 + 域名条件 + 弱网配置
```

包名和域名均为可选条件。

### 3.1 支持的规则组合

| 应用条件 | 域名条件 | 实际效果 |
|---|---|---|
| 所有应用 | 所有域名 | 全局弱网 |
| 指定应用 | 所有域名 | 指定应用全部流量弱网 |
| 所有应用 | 指定域名 | 所有应用访问该域名时弱网 |
| 指定应用 | 指定域名 | 仅指定应用访问指定域名时弱网 |

最重要的业务场景：

```text
应用：com.example.game
域名：login.example.com

延迟：2000 ms
抖动：300 ms
丢包：10%
上传：128 Kbps
下载：512 Kbps
```

只有 `com.example.game` 访问 `login.example.com` 时，才应用该弱网配置。

### 3.2 建议数据结构

```kotlin
data class WeakNetworkRule(
    val id: String,
    val name: String,
    val packages: Set<String>?,
    val domains: Set<String>?,
    val profileId: String,
    val priority: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
```

约定：

- `packages == null` 或空集合：所有应用。
- `domains == null` 或空集合：所有目标地址。
- 支持一个或多个包名。
- 支持一个或多个域名。
- 支持精确域名。
- 支持通配域名，例如 `*.example.com`。
- 支持“是否包含子域名”的显式选项。
- 同一规则只引用一个弱网配置。
- 规则默认不叠加，仅使用优先级最高且最具体的一条。

### 3.3 规则优先级

建议固定为：

```text
包名 + 域名
    >
包名
    >
域名
    >
全局
    >
正常转发
```

当具体程度相同时：

1. 数字优先级更高的规则优先。
2. 优先级相同时，最后修改的规则优先，或直接禁止冲突规则保存。
3. UI 中必须明确展示冲突和最终命中结果。

---

## 4. VPN 与流量转发架构

### 4.1 基础数据流

```text
Android 应用
    ↓
VpnService / TUN
    ↓
IPv4 数据包解析
    ↓
TCP / UDP 用户态转发
    ↓
连接归属识别
    ↓
域名识别
    ↓
规则匹配
    ↓
弱网调度
    ↓
真实网络 Socket
    ↓
目标服务器
```

所有真实网络 Socket 必须调用：

```kotlin
vpnService.protect(socket)
```

避免转发流量再次进入 VPN 造成死循环。

### 4.2 第一阶段协议范围

必须支持：

- IPv4
- TCP
- UDP
- DNS

后续可选支持：

- IPv6
- ICMP
- 更完整的 QUIC/HTTP3 识别
- PCAP 导出

第一阶段不应因 IPv6 未实现而静默丢弃所有 IPv6 流量。需要明确选择：

1. 不向 TUN 注册 IPv6 默认路由，让 IPv6 绕过 VPN；或
2. 明确关闭 IPv6 并在 UI 提示；或
3. 完整实现 IPv6 转发。

Agent 需要给出推荐方案及安全影响。

---

## 5. 应用识别方案

### 5.1 单应用或固定应用集合

通过：

```kotlin
VpnService.Builder.addAllowedApplication(packageName)
```

仅让选中的应用进入 VPN。

如果整个 VPN 会话只处理一个应用，则所有流量可以直接归属于该应用。

### 5.2 多应用分别匹配规则

建议最低支持：

```text
Android 10 / API 29
```

使用：

```kotlin
ConnectivityManager.getConnectionOwnerUid(...)
```

根据 TCP 或 UDP 连接五元组查询 UID，再通过：

```kotlin
PackageManager.getPackagesForUid(uid)
```

转换为包名。

建议连接上下文：

```kotlin
data class FlowContext(
    val protocol: Int,
    val sourceAddress: InetSocketAddress,
    val destinationAddress: InetSocketAddress,
    val uid: Int?,
    val packages: Set<String>,
    var domain: String?,
    var matchedRuleId: String?,
    val createdAt: Long,
    var lastActivityAt: Long
)
```

要求：

- 每条连接只查询一次 UID。
- 将结果缓存到连接表。
- 连接关闭或超时后清理。
- UID 查询失败时必须有明确降级策略。
- 不允许因 UID 无法识别而误将流量归到其他应用。

建议降级：

```text
无法识别应用
→ 仅匹配“所有应用”规则
→ 不匹配任何指定包名规则
```

---

## 6. 域名识别方案

工具只需要识别域名，不需要识别 HTTPS URL Path。

支持：

```text
api.example.com
*.example.com
cdn.example.com
```

不支持：

```text
https://api.example.com/user/login
```

### 6.1 DNS 识别

解析经过 VPN 的 DNS 查询和响应，维护：

```text
域名 → IP 地址集合 → TTL → 来源应用或连接
```

要求：

- 支持 A 记录。
- 后续可支持 AAAA、CNAME。
- 严格按照 TTL 失效。
- 限制缓存大小。
- 不永久保存历史域名。
- 避免将共享 CDN IP 永久绑定到单一域名。

### 6.2 TLS SNI 识别

对 TCP TLS ClientHello 进行最小解析，提取 SNI。

要求：

- 只解析握手元数据。
- 不解密 TLS。
- 不保存完整 TLS 报文。
- 设置最大解析长度，防止异常报文造成内存消耗。
- 解析失败时正常转发，不得中断连接。

### 6.3 域名识别优先级

建议：

```text
当前连接的 TLS SNI
    >
当前应用发起的 DNS 映射
    >
短期 IP/域名缓存
    >
未知域名
```

### 6.4 已知限制

需要在产品说明和测试计划中明确：

- CDN 共享 IP 可能造成误匹配。
- 一个域名可能对应多个动态 IP。
- 应用可能使用 DoH。
- Android Private DNS 可能隐藏普通 DNS。
- TLS ECH 可能隐藏真实 SNI。
- 已建立的长连接不会重复进行 DNS 或 TLS 握手。
- HTTP/2 和 HTTP/3 可能复用连接。
- QUIC 使用 UDP，域名识别能力可能有限。
- 域名匹配是“尽力识别”，不是绝对准确。

无法识别域名时，降级为：

```text
包名规则
→ 全局规则
→ 正常转发
```

不能因为无法识别域名而默认阻断流量。

---

## 7. 安全目标

安全是本项目的一等需求，不是后期附加功能。

### 7.1 核心安全原则

1. **全部流量处理默认在本机完成。**
2. **不上传业务流量、DNS 记录、SNI、连接元数据或测试日志。**
3. **不包含广告 SDK、统计 SDK、崩溃上报 SDK或第三方追踪 SDK。**
4. **不进行 HTTPS MITM。**
5. **不生成、不安装、不请求用户信任任何 CA 证书。**
6. **不记录 TCP/UDP Payload。**
7. **不默认导出 PCAP。**
8. **最小权限。**
9. **安全默认值。**
10. **代码和依赖可审计。**

### 7.2 威胁模型

Agent 需要围绕以下威胁设计安全方案：

#### A. 流量泄露

风险：

- 被接管流量被意外上传。
- 第三方 SDK 收集网络元数据。
- 调试日志包含 Token、Cookie、域名或内部地址。
- 导出文件被其他应用读取。

要求：

- 核心版本不实现任何远程上报。
- 不引入具有联网遥测能力的 SDK。
- Release 构建默认关闭详细日志。
- 导出功能必须由用户主动触发。
- 导出文件使用 Android Storage Access Framework，由用户选择保存位置。
- 不在公共存储目录静默写入数据。

#### B. VPN 流量被转发到错误目标

风险：

- Socket 未调用 `protect()` 导致循环。
- DNS 劫持或错误映射。
- 连接表串线。
- 多应用流量被错误归属。

要求：

- 对五元组、UID、目标地址进行严格绑定。
- 所有真实网络 Socket 统一经过 `SocketProtector`。
- 对转发目标做一致性检查。
- 不允许规则修改目标 IP、端口或 Payload。
- 单元测试覆盖连接复用和端口重用。

#### C. 本地敏感数据泄露

风险：

- 规则中包含内部域名。
- 日志中包含包名、内网地址、测试环境信息。
- 配置备份被系统云备份。

要求：

- 默认关闭 Android Auto Backup，或明确排除敏感配置。
- 配置存储在应用私有目录。
- 可选择使用 Android Keystore 加密规则数据库。
- 提供“一键清空日志、规则和缓存”。
- DNS/SNI 缓存仅保存在内存中，应用退出后清空。
- 不保存完整连接历史。

#### D. 恶意或异常数据包攻击

风险：

- 畸形 IP/TCP/UDP/DNS/TLS 数据包导致崩溃。
- 超大长度字段造成越界或内存耗尽。
- 大量连接造成连接表膨胀。
- 弱网延迟队列造成 OOM。

要求：

- 所有解析器必须进行长度和边界验证。
- 禁止信任数据包中的声明长度。
- 设置连接数上限。
- 设置单连接缓存上限。
- 设置总延迟队列内存上限。
- 超限时采用丢弃或旁路策略，并记录安全事件。
- 对解析器进行 Fuzz 测试。

#### E. 依赖与供应链风险

风险：

- 引入闭源转发核心。
- 第三方库内置遥测或远程下载。
- 依赖被替换或污染。

要求：

- 优先使用开源、活跃、许可证兼容的依赖。
- 锁定依赖版本。
- 保留依赖清单和许可证清单。
- 使用 Gradle dependency verification 或校验和。
- 禁止运行时下载原生库或规则脚本。
- CI 中执行依赖漏洞扫描和 SBOM 生成。
- 对核心 TUN/TCP/UDP 转发库进行源码审计。

---

## 8. 权限设计

仅申请实现功能所必需的权限。

可能需要：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

但 `QUERY_ALL_PACKAGES` 属于高敏感权限，Agent 必须评估是否可以避免。

优先方案：

- 使用 `<queries>` 声明有限范围。
- 仅展示可启动应用。
- 允许用户手工输入包名。
- 内部企业分发时再评估 `QUERY_ALL_PACKAGES`。

禁止申请与功能无关的权限，例如：

- 位置
- 联系人
- 短信
- 电话
- 麦克风
- 相机
- 日历
- 无障碍服务
- 设备管理员
- 通知读取
- 全盘文件访问

前台服务通知必须明确显示：

```text
弱网模拟正在运行
当前接管应用数量
当前生效规则
停止按钮
```

---

## 9. 日志与隐私设计

### 9.1 默认日志级别

Release 默认只记录：

- VPN 启动与停止
- 配置加载结果
- 转发核心错误码
- 队列溢出计数
- 规则命中计数
- 异常类型
- 不含 Payload 的性能统计

默认不记录：

- TCP/UDP Payload
- HTTP Header
- Cookie
- Token
- 完整 DNS 报文
- 完整 TLS ClientHello
- 用户账号
- 请求 Body
- 响应 Body

### 9.2 可选调试日志

仅 Debug 构建或用户显式开启诊断模式时允许：

- 包名
- 目标域名
- 目标 IP 和端口
- UID
- 规则 ID
- 连接建立和关闭时间

要求：

- 开启时显示明显警告。
- 自动设置过期时间。
- 应用重启后恢复关闭。
- 日志文件有大小和数量上限。
- 支持一键清除。
- 导出前显示将包含的数据类型。

### 9.3 日志脱敏

至少支持：

- Token、Authorization、Cookie 关键字过滤。
- 内网 IP 可配置脱敏。
- 包名和域名可选哈希化。
- 不记录原始 Payload，从源头避免敏感信息进入日志。

---

## 10. 数据存储

建议使用：

- Room：规则、弱网配置、用户设置
- DataStore：轻量偏好
- 内存缓存：DNS、SNI、FlowContext、实时统计
- Android Keystore：可选加密密钥

要求：

- DNS/SNI/连接缓存不持久化。
- 不保存完整历史流量。
- 不自动上传备份。
- 敏感配置从 Android Auto Backup 中排除。
- 数据库迁移必须有测试。
- 配置导出采用明确版本格式。
- 配置导入需要完整校验，禁止任意代码、脚本或动态表达式。

建议配置导出格式：

```json
{
  "schemaVersion": 1,
  "profiles": [],
  "rules": []
}
```

导入时验证：

- 字段类型
- 数值范围
- 域名格式
- 包名格式
- 最大规则数量
- 最大字符串长度
- 不接受未知执行字段

---

## 11. 用户界面需求

### 11.1 主界面

显示：

- VPN 状态
- 当前接管范围
- 当前生效规则数量
- 实时上传速率
- 实时下载速率
- 活跃连接数量
- 已丢弃数据包数量
- 延迟队列长度
- 启动
- 停止
- 紧急恢复正常网络

### 11.2 应用选择

显示：

- 应用图标
- 应用名称
- 包名
- 搜索
- 多选
- 系统应用过滤

安全要求：

- 默认不选择所有应用。
- 首次使用推荐只选择目标测试包。
- “全局接管”需要二次确认。
- 对系统关键应用进行警告，例如系统更新、电话、企业认证或密码管理器。

### 11.3 规则编辑

支持：

- 所有应用或指定应用
- 所有域名或指定域名
- 精确域名
- 通配域名
- 是否包含子域名
- 绑定弱网配置
- 设置优先级
- 启用或禁用
- 规则命中预览
- 冲突检测

### 11.4 安全与隐私页面

必须展示：

- 当前版本是否包含任何遥测
- 当前申请的权限
- 当前接管的应用列表
- 当前是否保存诊断日志
- 当前日志占用空间
- 一键清除全部本地数据
- 开源许可证
- 隐私说明
- 安全设计说明

---

## 12. 内置弱网预设

建议提供：

### 正常网络

```text
不限速
延迟 0 ms
抖动 0 ms
丢包 0%
```

### 轻度弱网

```text
下载 2 Mbps
上传 1 Mbps
延迟 100 ms
抖动 30 ms
丢包 1%
```

### 中度弱网

```text
下载 512 Kbps
上传 128 Kbps
延迟 300 ms
抖动 100 ms
丢包 5%
```

### 严重弱网

```text
下载 128 Kbps
上传 64 Kbps
延迟 800 ms
抖动 300 ms
丢包 15%
```

### 完全断网

```text
丢包 100%
```

所有预设允许复制后编辑，系统预设本身只读。

---

## 13. 非目标范围

第一阶段明确不实现：

- HTTPS MITM
- 安装或生成 CA 证书
- 解密 HTTPS
- URL Path 匹配
- HTTP Method 匹配
- Header 或 Body 匹配
- 修改请求或响应
- 模拟 HTTP 404、500
- 绕过证书固定
- Root 或 `tc/netem`
- 远程 VPN
- 远程控制平面
- 云端账号系统
- 广告
- 用户行为分析
- 自动崩溃上报
- 完整抓包分析器
- Wireshark 或 Charles 替代功能

---

## 14. 推荐模块划分

```text
app/
├── ui/
│   ├── dashboard/
│   ├── appselector/
│   ├── ruleeditor/
│   ├── profileeditor/
│   ├── diagnostics/
│   └── security/
│
├── vpn/
│   ├── WeakNetworkVpnService
│   ├── TunInterfaceManager
│   ├── SocketProtector
│   ├── ForegroundNotification
│   └── NetworkMonitor
│
├── packet/
│   ├── Ipv4Parser
│   ├── TcpParser
│   ├── UdpParser
│   ├── DnsParser
│   └── TlsClientHelloParser
│
├── forwarding/
│   ├── TcpForwarder
│   ├── UdpForwarder
│   ├── FlowTable
│   ├── FlowContext
│   └── ConnectionCleanup
│
├── ownership/
│   ├── ConnectionOwnerResolver
│   └── UidPackageResolver
│
├── domain/
│   ├── DomainCache
│   ├── DnsDomainResolver
│   ├── SniDomainResolver
│   └── DomainMatcher
│
├── shaping/
│   ├── DelayScheduler
│   ├── PacketLossController
│   ├── TokenBucketLimiter
│   ├── QueueGuard
│   └── WeakNetworkEngine
│
├── rules/
│   ├── RuleEngine
│   ├── RuleConflictDetector
│   ├── WeakNetworkRule
│   ├── NetworkProfile
│   └── RuleRepository
│
├── storage/
│   ├── AppDatabase
│   ├── RuleDao
│   ├── ProfileDao
│   ├── SettingsStore
│   └── SecureKeyProvider
│
├── diagnostics/
│   ├── EventLogger
│   ├── StatisticsCollector
│   ├── LogRedactor
│   └── ExportManager
│
└── security/
    ├── InputValidator
    ├── DependencyAudit
    ├── SecurityPolicy
    └── DataWiper
```

---

## 15. 分阶段实施计划

### Phase 0：技术验证

验证：

- `VpnService` 建立和销毁
- 指定应用进入 VPN
- `protect()` 正常
- TCP 基础转发
- UDP 基础转发
- DNS 正常
- `getConnectionOwnerUid()` 在目标机型上的行为
- 选定 TUN 转发核心的许可证和安全性

交付：

- 最小 PoC
- 技术风险报告
- 第三方依赖审计报告
- 是否支持 API 29 的最终决定

安全验收：

- PoC 不包含任何第三方统计 SDK。
- 无远程上报。
- 抓包确认应用只连接用户目标服务，不连接开发者服务器。
- 所有真实 Socket 均被 `protect()`。

### Phase 1：VPN 基础能力

实现：

- VPN 权限申请
- 前台服务
- TUN 创建
- IPv4 路由
- 应用选择
- 全局接管
- 启动和停止
- 紧急恢复

验收：

- 指定应用正常联网。
- 未选应用不进入 VPN。
- 全局模式正常。
- 停止后网络立即恢复。
- 系统杀进程后不遗留无效 VPN 状态。

安全验收：

- 全局模式二次确认。
- 前台通知持续可见。
- 无多余权限。
- 不写公共存储。
- 不保存流量内容。

### Phase 2：TCP/UDP 转发

实现：

- IPv4
- TCP
- UDP
- DNS
- 连接表
- 超时清理
- 网络切换恢复

验收：

- HTTP、HTTPS、WebSocket 基础场景正常。
- 常见 UDP 流量正常。
- 长连接正常。
- 前后台切换正常。

安全验收：

- 畸形包不导致崩溃。
- 连接数和缓存有上限。
- Fuzz 测试覆盖解析器。
- 不允许修改目标地址和 Payload。

### Phase 3：弱网引擎

实现：

- 延迟
- 抖动
- 丢包
- 上下行限速
- 完全断网
- 预设

验收：

- 参数效果可测量。
- 上传和下载独立。
- 结果具备可重复性。
- 停止后立即恢复。

安全验收：

- 队列内存受控。
- 超限不 OOM。
- 超限策略可观察。
- 参数范围有严格校验。

### Phase 4：多应用与包名规则

实现：

- 多应用接管
- UID 查询
- UID 到包名
- FlowContext
- 包名规则

验收：

- 两个应用可分别使用不同配置。
- UID 查询结果缓存。
- UID 查询失败不误匹配。

安全验收：

- 不将一个应用的流量归属给另一个应用。
- 日志默认不记录完整连接明细。
- 包名列表仅保存在本机。

### Phase 5：域名规则

实现：

- DNS 解析
- TTL 缓存
- TLS SNI
- 精确域名
- 通配域名
- 包名 + 域名组合
- 规则冲突检测

验收：

- 指定应用访问指定域名命中规则。
- 同应用访问其他域名不受影响。
- 其他应用访问同域名根据其规则处理。
- TTL 正常失效。

安全验收：

- 不解密 TLS。
- 不保存完整 DNS 或 TLS 报文。
- SNI 解析长度有上限。
- 域名缓存只在内存中。
- 域名无法识别时安全降级，不阻断正常流量。

### Phase 6：稳定性与内部发布

实现：

- 配置持久化
- 导入导出
- 日志导出
- 实时统计
- 多机型适配
- Wi-Fi/移动数据切换
- 睡眠唤醒
- 异常恢复

安全验收：

- 依赖锁定和 SBOM。
- Release 构建关闭调试日志。
- 无遥测、广告和远程上报。
- Auto Backup 策略明确。
- 导出由用户主动触发。
- 支持一键清除本地数据。
- 完成内部安全审计。

---

## 16. 测试计划

### 16.1 功能测试

覆盖：

- 全局弱网
- 指定应用弱网
- 指定域名弱网
- 指定应用 + 指定域名
- 多规则优先级
- 规则冲突
- 网络切换
- 应用前后台
- VPN 重启
- 系统杀进程
- 多应用并发
- 长连接
- TCP
- UDP
- DNS
- TLS SNI
- CDN 多 IP
- 域名 TTL 失效

### 16.2 安全测试

至少包含：

1. 抓取工具自身外联，确认无未声明连接。
2. 验证无 Payload 日志。
3. 验证不安装 CA。
4. 验证不请求无关权限。
5. 验证导出文件只有用户主动操作时产生。
6. 验证清除数据后规则、日志、缓存均消失。
7. 验证 Android Auto Backup 不包含敏感配置。
8. 对所有数据包解析器做 Fuzz。
9. 对配置导入做畸形数据测试。
10. 对大连接数、大队列、超长域名、异常 TLS 报文做资源消耗测试。
11. 对依赖做漏洞扫描。
12. 生成 SBOM。
13. 检查 Release APK 中是否包含广告、统计或崩溃上报域名。
14. 使用静态分析检查硬编码密钥、调试开关和明文存储。
15. 测试多用户、工作资料和企业设备策略下的行为。

### 16.3 性能目标

Agent 需要给出合理指标，建议至少评估：

- 空闲 CPU
- 正常转发 CPU
- 高吞吐 CPU
- 内存占用
- 最大并发连接数
- 最大延迟队列长度
- 转发吞吐
- 额外延迟误差
- 限速误差
- 丢包率误差
- 规则匹配开销
- 域名识别命中率
- 电量消耗

---

## 17. 推荐技术栈

建议 Agent 对以下方案进行比较并给出最终选择：

### Android 层

- Kotlin
- Jetpack Compose
- Coroutines
- Flow
- Room
- DataStore
- Foreground Service
- Android Keystore

### 转发核心

优先考虑：

- 复用成熟的开源 TUN/TCP/UDP 用户态转发核心
- Kotlin/Java 与 Rust/C/C++ 的 JNI 方案比较
- 性能、稳定性、许可证、安全审计成本比较

禁止：

- 闭源二进制核心
- 无法审计的预编译 `.so`
- 运行时下载核心组件
- 依赖远程控制服务器

---

## 18. Agent 需要重点回答的问题

请基于本需求输出完整实施计划，并重点回答：

1. 推荐的整体技术架构是什么？
2. 推荐使用哪个开源 TUN/TCP/UDP 转发核心？
3. 为什么不建议从零实现完整用户态 TCP/IP 栈？
4. Kotlin、Rust、C/C++ 各自承担哪些模块？
5. 如何实现上下行独立限速？
6. 如何保证延迟和丢包效果接近真实弱网？
7. 如何防止延迟队列造成 OOM？
8. 如何准确查询连接 UID 和包名？
9. UID 查询失败如何安全降级？
10. 如何建立 DNS 和 SNI 的域名识别链路？
11. 如何降低 CDN 共用 IP 导致的误匹配？
12. QUIC、DoH、Private DNS 和 ECH 如何处理？
13. 多规则冲突如何检测？
14. 线程模型和并发模型如何设计？
15. 如何处理网络切换和 VPN 重建？
16. 如何确保工具自身不上传或泄露流量？
17. 如何进行依赖和供应链审计？
18. 如何设计日志脱敏和诊断模式？
19. 配置是否需要 Keystore 加密？
20. Android Auto Backup 应如何配置？
21. 最低 Android 版本应定为多少？
22. MVP 和稳定内部版本分别需要多少工作量？
23. 每个阶段的验收标准是什么？
24. 第一阶段可以直接拆成哪些开发任务？
25. 应使用哪些单元测试、集成测试、真机测试和 Fuzz 测试？
26. Release 构建如何证明不存在广告、遥测和远程上报？
27. 项目需要生成哪些安全文档、SBOM 和许可证清单？

---

## 19. MVP 定义

MVP 建议只包含：

- Android 10+
- 非 Root
- 本地 `VpnService`
- IPv4
- TCP
- UDP
- 一个或多个指定应用
- 全局规则
- 包名规则
- 固定延迟
- 抖动
- 丢包
- 上下行限速
- 完全断网
- 基础预设
- 本地配置
- 无遥测
- 无抓包
- 无日志导出
- 无 IPv6
- 无域名规则

第二个里程碑加入：

- DNS 识别
- TLS SNI
- 域名规则
- 包名 + 域名组合规则
- 冲突检测
- 诊断日志
- 安全审计

这样可以避免在第一阶段同时承担转发、弱网、应用归属和域名识别四类高风险问题。

---

## 20. 最终交付要求

Agent 输出的 Plan 至少包括：

1. 项目架构图
2. 数据流图
3. 模块职责
4. 核心数据结构
5. 技术栈选择
6. 开源依赖推荐及许可证
7. 线程和并发模型
8. TCP/UDP 转发方案
9. UID/包名识别方案
10. DNS/SNI 域名识别方案
11. 弱网算法方案
12. 规则优先级方案
13. 存储方案
14. UI 页面规划
15. 日志与诊断方案
16. 安全威胁模型
17. 权限清单
18. 隐私与数据处理说明
19. 依赖与供应链安全方案
20. 分阶段开发计划
21. 每阶段验收标准
22. 测试策略
23. Fuzz 测试策略
24. 性能目标
25. 兼容性目标
26. 风险与降级方案
27. MVP 工作量
28. 完整内部版本工作量
29. 项目目录结构
30. 第一阶段可直接执行的任务列表

最终目标是交付一个：

```text
非 Root
本地处理
无遥测
无广告
无远程上报
可审计
可重复测试
支持包名 + 域名组合规则
```

的 Android 真机弱网模拟工具。
