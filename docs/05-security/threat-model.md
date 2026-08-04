# 威胁模型（Threat Model）

本文关注 Pakomo 作为一个能够接管流量、且能被程序化驱动的工具，其攻击面与收口方式。数据与隐私边界参见
[隐私边界](privacy-boundary.md)。

## 资产与信任边界

- **被测流量**：不出设备、不解密、不落盘，详见隐私边界。
- **本地 SOCKS5**：使用随机端口与随机凭据，仅监听本机，不是对外服务。
- **VPN 授权**：`VpnService` 需要用户或系统的一次性授权（`prepare()`）。该授权是环境前置条件，Pakomo 只断言其存在，
  不绕过。

## 自动化控制面

自动化控制协议允许外部通过 adb 广播驱动 Pakomo，包括启停与切换故障 profile。一个能够启动 VPN、丢弃流量、修改 DNS
的 exported 组件如果进入 release，构成严重风险。为此采用三层收口：

1. **构建门禁**：控制组件（`ControlReceiver` 等）的源码与 manifest 仅在 debug 构建（`src/debug`）中存在，release 产物
   不含该接收器。`AUTOMATION_ENABLED` BuildConfig 作为纵深防御。
2. **共享令牌**：放置 `automation.token` 后即开启强制校验，之后每条命令须携带匹配的 token；release 场景要求配置该令牌。
3. **前置断言**：只断言、不满足。VPN 未授权时返回 `NEED_VPN_CONSENT` 并快速失败，不尝试绕过系统弹窗。

Pakomo 未采用基于 calling-uid 的校验，因为广播接收器在 `onReceive` 中无法获取可靠的发送方 uid，此类检查不构成真实
边界。真实边界是 debug-only 与令牌。详见 [自动化控制接口](../automation-control.md) 的安全一节。

## 显式广播要求

清单接收器在 Android 8 及以上收不到隐式广播，必须显式指定组件（`-n <pkg>/…ControlReceiver`）。因此任意应用无法用一个
宽泛的 action 广而告之地触发该接收器。

## 误用与滥用

Pakomo 仅限授权测试用途，不进行检测规避，不隐藏自身，以前台通知与悬浮球明示运行状态。Pakomo 不提供大规模或远程
干扰能力，不含远程网关，也不含对外监听服务。

## 不在威胁模型内

- 设备本身已被 root 或植入后门等平台级妥协，不在 Pakomo 的防御范围内。
- 被测应用自身的加密解析（DoH、DoT）导致域名级故障不命中，属能力限制，而非安全问题。
