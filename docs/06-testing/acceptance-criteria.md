# 验收标准（Acceptance Criteria）

[English](acceptance-criteria_EN.md) | 简体中文

本文说明验证 Pakomo 已实现能力的方法。基本原则是先确认协议行为，再记录应用侧错误码；后者不作为唯一通过条件，
参见 [故障模型](../01-capabilities/fault-models.md) 与 [测量口径](measurement-methodology.md)。

## 1. 自动化冒烟

自动化控制协议自带冒烟脚本，覆盖 start、update、stop、status、reset、load_profile 的全路径断言与配置生效确认。

- 在 Linux、CI 或 Git Bash 环境使用 `scripts/automation-smoke.sh`，在 Windows 使用 `scripts/automation-smoke.ps1`。
- 覆盖从 stopped 启动、热更新、reset、profile 加载，以及令牌门（设置 `TEST_TOKEN=1`）。
- 详见 [自动化控制接口](../automation-control.md)。

## 2. 双 flavor 对拍

将同一 profile 分别在 Kernel（`com.alphynia.pakomo.kernel`）与 Hev（`com.alphynia.pakomo.hev`）上运行，比对行为相关的状态字段，
以 Hev 版作为可信基线守护 Kernel 版。相应脚本为 `scripts/automation-compare.sh`，用于本地按需运行，不进入 CI。

## 3. 特殊故障验证矩阵

**必测范围**：全局、整应用、应用内指定域名与指定域名。
**必测状态**：域名未缓存与已缓存；目标 IP 未学习与已从明文 DNS 学习；HTTP/TCP、HTTPS/TCP、QUIC/UDP 443、普通 UDP、
系统明文 DNS，以及使用 DoH 的应用。

协议行为的验收要点如下：

- **连接重置**：抓包或应用日志能确认 TCP RST。
- **网络中断·立即**：可预判时连接快速被拒绝；不可预判时明确降级为 RST。
- **网络中断·静默**：连接保持但无响应，直至应用或保护超时结束。
- **DNS**：NXDOMAIN 为 RCODE 3，SERVFAIL 为 2，REFUSED 为 5，超时无响应；网络中断单独开启时 DNS 仍能正常完成。
- **慢响应**：命中连接的下行在约 `holdMs` 后开始收到并全速收完，得到恒定迟到时长，而非按响应大小成倍增长；
  在"放行小响应"阈值下，小响应不被暂扣。

应用错误码记录但不作为唯一通过条件，参考目标为：连接重置 `-101`，立即中断 `-102`（域名首连允许 `-101`），
静默中断 `-7`，NXDOMAIN `-105`，SERVFAIL 与 REFUSED `-137`。测试至少覆盖 Android 10、一个中间版本与当前主要版本，
并分别记录 WebView 与 Chromium 版本。

## 4. 稳定性

长时间压测下，需监控文件描述符数量、内存、连接表大小与协程数量不出现无界增长；快速开关 VPN、切换网络与应用强杀
均不导致崩溃。

## 5. 构建验证

CI 执行 `testKernelDebugUnitTest`、`lintKernelRelease`、`assembleKernelRelease` 与 `assembleHevRelease`，
其定义详见 `.gitlab-ci.yml`。本地仅运行 `assembleKernelDebug` 不覆盖单元测试与 lint，建议在提交前补充运行上述任务。
