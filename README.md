# Pakomo

Android 10+ 非 Root 弱网模拟工具。

## 当前程序状态

首批可运行程序已经包含：

- Home、接管范围、弱网规则、规则编辑、诊断、设置、安全与隐私页面。
- `全局 / 指定应用 / 指定地址` 三种互斥接管范围。
- 从设备读取应用名称、包名和图标，支持搜索与系统应用过滤。
- 一个应用一张卡片；点击卡片头展开，不设置额外折叠按钮。
- 每个应用支持多个域名；指定地址模式支持独立的多域名列表。
- 弱网规则单选互斥，卡片直接展示延迟、抖动、丢包和上下行限速。
- 内置规则只读，复制后可编辑；自定义规则可编辑、复制和删除。
- 选择状态、域名和规则保存在应用私有目录，Android 云备份已禁用。
- 全局/指定应用模式通过本地 TUN → HEV → 认证 SOCKS → `protect()` Socket 转发。
- 延迟、抖动、上下行限速、UDP 丢包和完全断网规则。

## 重要安全边界

- 转发核心固定为 MIT 许可的 `hev-socks5-tunnel` 2.14.4，并从源码构建。
- SOCKS 服务只监听随机本机端口，使用每次启动生成的随机凭据。
- 所有连接目标服务器的 TCP/UDP Socket 必须先通过 `VpnService.protect()`。
- 指定地址模式会观察普通 DNS 的 IPv4 解析结果，只对命中域名及其子域名的连接整形；未命中流量正常旁路。
- DNS-over-HTTPS/TLS 当前无法识别；应用卡片内的按应用域名限制仍在接入连接归属识别。
- TCP 流不能直接丢弃字节；非 100% TCP 丢包当前以重传等待模拟，UDP 使用真实概率丢包。

## 构建

项目使用 JDK 17、Gradle Wrapper、Android SDK 36、NDK 28.2：

```powershell
git submodule update --init --recursive
powershell -ExecutionPolicy Bypass -File .\scripts\prepare-third-party.ps1
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

第二条命令准备 vendored 转发核心:还原 Windows 上的 Git 符号链接占位符，并打上 HEV 归属前导补丁 `patches/hev-attribution-preamble.patch`（该原生改动存放在补丁里，不在子模块提交中）。脚本幂等，`git submodule update` 之后重跑即可。Linux/macOS（符号链接可用）无需还原链接，但仍需应用补丁：

```bash
git apply --directory=third_party/hev-socks5-tunnel patches/hev-attribution-preamble.patch
```

调试 APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 下一阶段

1. 真机验证全局/指定应用/指定地址的 TCP、UDP、DNS、网络切换和紧急停止。
2. 接入连接归属识别，使应用卡片内域名只影响对应应用。
3. 接入实时统计、前台通知错误状态和转发核心健康检查。
4. 在 TUN 与 HEV 之间增加 IP 数据包级塑形，实现真实 TCP 丢包/重传。
5. 完成解析器 Fuzz、队列上限、长连接和多应用并发测试。
