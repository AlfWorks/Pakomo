<div align="center">
  <img src="docs/pakomo-icon.png" width="120" height="120" alt="Pakomo icon">

  <h1>Pakomo</h1>

  <p>
    面向选定应用与域名的本地弱网与故障模拟工具，基于 Android <code>VpnService</code>。
  </p>

  <p>
    <img alt="Platform" src="https://img.shields.io/badge/Platform-Android%2010%2B-3ddc84?logo=android&logoColor=white">
    <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.3-7f52ff?logo=kotlin&logoColor=white">
    <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-UI-4285f4?logo=jetpackcompose&logoColor=white">
    <img alt="minSdk" src="https://img.shields.io/badge/minSdk-29-555">
    <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-36-555">
  </p>

  <p>
    <a href="#简介">简介</a>
    ·
    <a href="#功能特性">功能特性</a>
    ·
    <a href="#快速开始">快速开始</a>
    ·
    <a href="#项目结构">项目结构</a>
    ·
    <a href="#技术原理">技术原理</a>
    ·
    <a href="#文档">文档</a>
    ·
    <a href="#声明">声明</a>
  </p>
</div>

## 简介

<img src="docs/pako.png" align="right" width="190" alt="Pako">

Pakomo 是一款非 Root 的 Android 弱网与故障模拟工具。它以本地 `VpnService` 接管选定应用或域名的流量，在设备本机注入可控的网络劣化与特殊故障，用于验证客户端在弱网、故障与迟到响应场景下的行为。

流量处理全部在本机完成，不经过外部代理。设备 TUN 流量经**转发引擎**送至仅监听本机的认证 SOCKS5 中继；中继按连接对命中规则的流量整形或注入故障，其余流量原样旁路。

转发引擎有两个可选实现，以 Android build flavor 区分，**中继逻辑与故障注入两版完全一致**、可同机并存：

- **k 版**（`kernel`，默认，applicationId `com.pakomo.kernel`）：纯 Kotlin 自研 tun2socks 内核（`com.pakomo.kernel`），无原生代码、构建不依赖 NDK。
- **h 版**（`hev`，applicationId `com.pakomo.hev`）：使用原生 `hev-socks5-tunnel` 转发核心。

适用场景：

- 验证请求超时后旧请求是否真正取消，以及重试期间是否会收到旧请求的响应；
- 观察客户端在高延迟、抖动、丢包与限速下的表现；
- 复现连接重置、DNS 失败、网络中断与迟到响应（Late Response）等特殊故障；
- 逐连接查看经过设备的实际流量，辅助定位问题。

## 功能特性

- **双转发引擎**：k 版（纯 Kotlin 内核）与 h 版（hev native）两个可选实现，功能一致、可同机并存。
- **接管范围**：全局 / 指定应用 / 指定地址（域名）三种互斥模式，域名支持子域匹配。
- **弱网参数**：固定延迟、抖动、丢包率、上下行限速；提供「简单」与「高级（分方向独立设置）」两种模式。
- **特殊故障**（随规则保存，可同时启用多种）：
  - 连接重置（TCP RST）；
  - DNS 失败（NXDOMAIN / SERVFAIL / 超时，含抗缓存）；
  - 网络中断（静默超时 / 立即失败）；
  - 慢响应（Slow Response）：将命中连接的下行响应暂扣指定时长后一次性放行，模拟客户端观察到的迟到响应；可设「放行小响应」阈值以放过心跳与探测。
- **流量记录**：逐连接列出经过 Pakomo 的流量（协议、主机、端口、上下行字节、是否被暂扣、是否整形、状态），支持按主机 / 端口 / 协议过滤。
- **多语言**：简体中文与 English，可在设置内即时切换。
- **主题**：内置可切换的 Pako 装饰主题。
- **快捷悬浮控制**：以悬浮球即时开关接管。
- **诊断**：实时运行状态、归属命中统计与原始 Logcat 输出。

## 快速开始

### 环境要求

- JDK 17
- Android SDK 36
- NDK 28.2 —— **仅 h 版需要**（编译 `hev-socks5-tunnel` 原生库）；只构建 k 版可不装
- Gradle Wrapper（随仓库提供）

### 准备 hev 转发核心（仅 h 版需要）

**k 版为纯 Kotlin 实现，无需此步**，可直接跳到「构建」。仅当要构建 h 版时，首次构建前初始化子模块并准备 vendored 转发核心：

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-third-party.ps1
```

`prepare-third-party.ps1` 在 Windows 上还原 Git 符号链接占位符，并应用 HEV 归属前导补丁 `patches/hev-attribution-preamble.patch`（原生改动以补丁形式维护，不进入子模块提交）。该脚本幂等，可在 `git submodule update` 之后重复运行。

Linux / macOS 环境支持符号链接，无需还原链接，但仍需应用补丁：

```bash
git apply --directory=third_party/hev-socks5-tunnel patches/hev-attribution-preamble.patch
```

> 两版对应 Android product flavor `kernel` / `hev`；下方 Gradle 任务名带上 flavor 即选择版本。

### 调试构建

用于本地开发与验证（两版分开调用）：

```powershell
# k 版（纯 Kotlin，无 native）
.\gradlew.bat :app:testKernelDebugUnitTest :app:lintKernelDebug :app:assembleKernelDebug
# h 版（hev native，需先完成上一步「准备 hev 转发核心」）
.\gradlew.bat :app:assembleHevDebug
```

输出 `app/build/outputs/apk/kernel/debug/app-kernel-debug.apk` 与 `.../hev/debug/app-hev-debug.apk`。调试包未经代码混淆与资源压缩，体积明显大于发布包。

> h 版必须与 k 版**分开调用**：构建脚本以「任务名是否含 `hev`」决定是否编译原生库；两版混在一次调用里会把 native 连带编进 k 版包。

### 发布构建

用于分发，需自行配置签名。发布构建启用 R8 代码混淆与资源压缩，体积显著小于调试包：

```powershell
.\gradlew.bat :app:assembleKernelRelease   # k 版
.\gradlew.bat :app:assembleHevRelease       # h 版
```

输出 `app/build/outputs/apk/kernel/release/app-kernel-release.apk` 与 `.../hev/release/app-hev-release.apk`，applicationId 分别为 `com.pakomo.kernel` 与 `com.pakomo.hev`，可同机并存。CI（`.gitlab-ci.yml`）在打 `vX.Y.Z` tag 时自动构建并发布两版。

## 项目结构

```text
.
|-- app/src/main/java/com/pakomo/
|   |-- core/            # 数据模型、输入校验、界面语言枚举
|   |-- data/            # 偏好持久化、故障配置编解码、应用清单
|   |-- kernel/          # k 版：纯 Kotlin tun2socks 内核（IP/TCP/UDP、SOCKS5 客户端、连接回收）
|   |-- forwarding/      # SOCKS5 中继、整形与故障策略、NIO 反应堆、流量记录
|   |-- shaping/         # 弱网整形器（延迟 / 抖动 / 丢包 / 限速）
|   |-- vpn/             # VpnService、隧道配置、连接归属、运行时统计
|   |-- overlay/         # 悬浮球快捷控制
|   `-- ui/              # Jetpack Compose 界面（screens / components / theme）
|-- app/src/main/res/                # 资源（图标、drawable、主题）
|-- third_party/hev-socks5-tunnel/   # h 版 vendored 转发核心（git submodule）
|-- patches/                         # h 版 HEV 归属前导补丁
|-- scripts/                         # 第三方准备脚本
`-- docs/                            # 项目方向与设计文档
```

## 技术原理

```text
App → TUN → 转发引擎 [ Tun2SocksEngine（k 版·纯 Kotlin） | hev-socks5-tunnel（h 版·native） ]
          → 本地认证 SOCKS5 (Socks5Server) → protect() Socket → 目标服务器
```

- **转发链路**：设备 TUN 流量由转发引擎（k 版为自研 Kotlin 内核 `Tun2SocksEngine`，h 版为 `hev-socks5-tunnel`）转发至仅监听本机随机端口的 SOCKS5 中继；所有连出目标服务器的 Socket 先经 `VpnService.protect()`，不再回环进 TUN。两版之后的中继、整形与故障逻辑（`Socks5Server`）完全相同。
- **按应用与域名归属**：转发引擎透传原始连接五元组（归属前导，HEV 补丁与 Kotlin `Socks5Client` 实现一致），归属由 `ConnectivityManager.getConnectionOwnerUid()` 在 Kotlin 侧解析；域名匹配依据 TLS SNI 与从明文 DNS 学到的目标 IP，以覆盖 QUIC 与无 SNI 的连接。
- **整形与故障**：中继按连接判定是否整形或注入故障；弱网参数与特殊故障叠加于同一数据面，且互不重复施加。
- **慢响应**：下行方向以「闸门」缓存命中连接的响应，到期一次性放行，得到恒定的迟到时长，而非将服务器的流式过程整体平移。

> 注：Pakomo 不解密 TLS，故整形与故障的最小粒度为连接，无法只延迟同一条 HTTP/2 连接内的单个请求。HTTPS 与 QUIC 的粒度限制详见文档。

## 文档

- [项目方向](docs/PROJECT-DIRECTION.md)
- [内核替换与吞吐优化 postmortem](docs/kernel-replacement-postmortem.md)
- [故障错误码方案](docs/fault-error-codes.md)
- [实现计划](IMPLEMENTATION_PLAN.md)
- [UI 设计](UI_DESIGN.md)

## 声明

Pakomo 为网络测试工具，仅限在具备测试授权的网络与应用上用于测试、研究与学习，不得用于未经授权的流量拦截或干扰。

- 转发核心 `hev-socks5-tunnel` 采用 MIT 许可，并从源码构建。
- SOCKS 服务仅监听本机随机端口，使用每次启动生成的随机凭据。
- `QUERY_ALL_PACKAGES` 权限仅用于在接管范围内选择已安装应用。
- 角色「Pako」及相关美术资源为界面装饰，版权归其原作者所有。
