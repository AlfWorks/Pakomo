# 项目概览（Project Overview）

Pakomo 是一款基于 Android `VpnService` 的非 Root 弱网与故障模拟工具。Pakomo 通过本地 TUN 接管选定应用或域名的流量，
在设备本机注入可控的网络劣化与特殊故障，用于验证客户端在弱网、故障与迟到响应场景下的行为。

所有流量处理均在设备本机完成，不经过外部代理，也不对业务内容进行解密。相关边界的完整定义参见
[原则与边界](principles-and-boundaries.md)。

## 能力范围

能力的权威清单以 [能力矩阵](../01-capabilities/capability-matrix.md) 为准。主要能力概括如下：

- 连接层弱网整形，包括延迟、抖动、丢包与上下行限速，并区分简单模式与分方向设置的高级模式。
- 四种特殊故障，即连接重置、DNS 失败、网络中断与慢响应（Late Response）。故障配置随规则保存，可同时启用。
- 按应用与域名归属，逐连接流量记录，以及实时诊断。
- 两条实现路径，即 Kernel 与 Hev。两者功能一致，可同机并存。
- 自动化控制协议，通过 adb 广播驱动，可将 Pakomo 作为可编程的弱网与故障注入器使用。

## 明确不做的范围

Pakomo 不进行 TLS 中间人（MITM）解密，也不修改报文内容。因此应用层内容故障，例如 HTTP 404/500、非法 JSON、
空响应与截断响应，均属于明确不做的范围（`Out-of-Scope`）。完整说明参见 [限制](../01-capabilities/limitations.md)。

## 典型场景

- 验证请求超时后原请求是否真正取消，以及重试期间是否会收到原请求的迟到响应。
- 观察客户端在高延迟、抖动、丢包与限速条件下的表现。
- 复现连接重置、DNS 失败、网络中断与迟到响应。
- 逐连接查看经过设备的实际流量，辅助定位问题。

## 文档导航

- 能做什么，参见 [能力](../01-capabilities/capability-matrix.md)。
- 如何实现，参见 [架构](../02-architecture/common-architecture.md)。
- 如何呈现，参见 [产品](../03-product/ui-specification.md)。
- 不可越过的边界，参见 [原则与边界](principles-and-boundaries.md) 与 [安全](../05-security/privacy-boundary.md)。
- 如何验证，参见 [测试](../06-testing/acceptance-criteria.md)。
- 自动化接口，参见 [自动化控制接口](../automation-control.md)。
