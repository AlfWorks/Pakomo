# 威胁模型（Threat Model）

[English](threat-model_EN.md) | 简体中文

本文关注 Pakomo 作为一个能够接管流量、且能被程序化驱动的工具，其攻击面与收口方式。数据与隐私边界参见
[隐私边界](privacy-boundary.md)。

## 资产与信任边界

- **被测流量**：不出设备、不解密、不落盘，详见隐私边界。
- **本地 SOCKS5**：使用随机端口与随机凭据，仅监听本机，不是对外服务。
- **VPN 授权**：`VpnService` 需要用户或系统的一次性授权（`prepare()`）。该授权是环境前置条件，Pakomo 只断言其存在，
  不绕过。

## 自动化控制面

自动化控制协议允许外部通过 adb 广播驱动 Pakomo，包括启停与切换故障 profile。控制入口 `ControlReceiver` 声明在
`src/main/AndroidManifest.xml`，`exported="true"` 且不带 permission——**debug 与 release 构建都包含并导出**（组件名可
从文档或反编译获知）。因此真实的安全边界不是「该组件是否存在」，而是下列收口：

1. **共享令牌（主边界）**：`AutomationConfig.verifyToken` 校验 `automation.token`。
   - **release**：未配置 token 时任何命令一律 `BAD_TOKEN`——安装正式包不会暴露未鉴权的控制面。
   - **debug**：未配置 token 时放行以便本地诊断；一旦配置则强制校验。
   - 配置后每条命令须携带与 token 文件常量时间匹配的值。
2. **前置断言（不绕过）**：只断言、不满足。VPN 未授权时返回 `NEED_VPN_CONSENT` 并快速失败，不尝试绕过系统弹窗。
3. **鉴权前不泄露状态**：命令解析失败与 token 校验失败（pre-authorization）的响应只回错误码，**不附带运行时快照**
   （`stage`/`stats`），避免同机应用发无 token 的 ordered 广播从 result 侧信道读取引擎状态。

Pakomo 未采用基于 calling-uid 的校验，因为广播接收器在 `onReceive` 中无法获取可靠的发送方 uid，此类检查不构成真实
边界。**真实边界是令牌**（release 强制）。详见 [自动化控制接口](../automation-control.md) 的安全一节。

## 显式广播要求

清单接收器在 Android 8 及以上收不到隐式广播，必须显式指定组件（`-n <pkg>/…ControlReceiver`）。因此任意应用无法用一个
宽泛的 action 广而告之地触发该接收器。

## 误用与滥用

Pakomo 仅限授权测试用途，不进行检测规避，不隐藏自身，以前台通知与悬浮球明示运行状态。Pakomo 不提供大规模或远程
干扰能力，不含远程网关，也不含对外监听服务。

## 不在威胁模型内

- 设备本身已被 root 或植入后门等平台级妥协，不在 Pakomo 的防御范围内。
- 被测应用自身的加密解析（DoH、DoT）导致域名级故障不命中，属能力限制，而非安全问题。
