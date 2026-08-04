# Hev 后端（原生 hev-socks5-tunnel）

Hev 版使用原生 `hev-socks5-tunnel` 作为转发核心，保留更完整、更成熟的底层能力范围。Hev 版与 Kernel 版是不同的
实现路径，两者的中继、整形与故障逻辑完全一致，均位于共享的 `forwarding/`。

## 组成

- **转发核心**：`third_party/hev-socks5-tunnel`，以 git submodule 形式引入，采用 MIT 许可并从源码构建，
  连带其上游子模块 `hev-task-system`、`yaml`、`lwip` 与 `hev-socks5-core`。
- **JNI 桥**：`hev.htproxy.TProxyService`，提供 `TProxyStartService`、`TProxyStopService` 与 `TProxyGetStats`。
- **配置**：`vpn/HevTunnelConfig` 生成 hev 的 YAML 配置，包含 TUN 地址与 MTU、本地 SOCKS5 端口与凭据、超时等。
- **归属前导补丁**：`patches/hev-attribution-preamble.patch` 使原生核心透传原始连接五元组，以便 Kotlin 侧按应用与
  域名归属。原生改动以补丁形式维护，不进入子模块提交。

## 构建

- 只有 Hev flavor 编译原生库：构建脚本以"任务名包含 `hev`"触发 `ndkBuild`，编译 `libhev-socks5-tunnel.so`。
- 需要 NDK 28.2。首次构建前需执行 `git submodule update --init --recursive` 并应用归属补丁；在 Windows 上还需运行
  `scripts/prepare-third-party.ps1` 还原符号链接。详见 [README](../../README.md) 的"准备 hev 转发核心"。
- Hev 版必须与 Kernel 版分开调用 Gradle 任务，否则 native 会连带编入 Kernel 版包。

## 与 Kernel 版的关系

对 Pakomo 现有业务功能，Hev 版与 Kernel 版无可见差异，参见 [能力矩阵](../01-capabilities/capability-matrix.md) 的差异说明。
两个 flavor 通过同一套自动化冒烟与双 flavor 对拍校验业务一致性（`scripts/automation-compare.sh`）。隧道层的实现细节，
例如原生栈的成熟度与边角协议处理，不要求与 Kernel 逐一对齐。
