# Pakomo 自动化控制接口(Automation Control)

外部自动化(CI / UI 自动化 / 设备农场)通过 `adb shell am broadcast` 程序化驱动 Pakomo:
启停 VPN、切换弱网/故障 profile、回读状态。控制层只解析协议并转发到现有
`VpnServiceController`,**不改动引擎与策略**。

> **仅 debug 构建可用。** 控制组件的源码与 manifest 都在 `app/src/debug/`,release APK
> 根本不含 exported 接收器(安全收口见下文「安全」)。

---

## 快速开始

```bash
# 变量:被测 flavor 的组件(kernel 版;hev 版把 .kernel 换成 .hev)
COMP=com.pakomo.kernel/com.pakomo.automation.ControlReceiver

# 1) 环境前置:授予 VPN 权限(消除系统弹窗,见「VPN 授权」)
adb shell appops set com.pakomo.kernel ACTIVATE_VPN allow

# 2) 查询状态
adb shell am broadcast -a com.pakomo.automation.CONTROL -n $COMP --es cmd status

# 3) 注入「中度弱网」预设并等待进入 forwarding
adb shell am broadcast -a com.pakomo.automation.CONTROL -n $COMP --es cmd start --es rule medium --es wait true

# 4) 恢复正常 / 停止
adb shell am broadcast -a com.pakomo.automation.CONTROL -n $COMP --es cmd reset
adb shell am broadcast -a com.pakomo.automation.CONTROL -n $COMP --es cmd stop --es wait true
```

> **必须用显式广播**(`-n <pkg>/com.pakomo.automation.ControlReceiver`)。Android 8+ 的后台限制下,
> 清单声明的接收器**收不到隐式广播**(只带 `-a` 而不带 `-n`/`-p` 时),命令会静默无效(`result=0`
> 但无 `data=`、无 logcat、无 status.json)。
>
> `applicationId` 按 flavor 带后缀:kernel 版 = `com.pakomo.kernel`,hev 版 = `com.pakomo.hev`;
> 接收器类名两版都是 `com.pakomo.automation.ControlReceiver`。

一键冒烟(含从 `stopped` 的真冷启动 + profile + token 全路径断言)。Linux/CI/Git-Bash 用
[`scripts/automation-smoke.sh`](../scripts/automation-smoke.sh),Windows PowerShell 用
[`scripts/automation-smoke.ps1`](../scripts/automation-smoke.ps1):
```powershell
.\scripts\automation-smoke.ps1 -Pkg com.pakomo.kernel     # add: $env:TEST_TOKEN=1  to also verify the token gate
```
```bash
bash scripts/automation-smoke.sh com.pakomo.kernel        # prefix: TEST_TOKEN=1  to also verify the token gate
```

---

## 命令

action 统一为 `com.pakomo.automation.CONTROL`,命令由 `--es cmd <verb>` 指定。

| cmd | 作用 | 关键 extras |
|---|---|---|
| `status` | 回读引擎 stage + stats | `--es wait true`(等到 forwarding) |
| `start` | 建 VPN + 接管(冷启,重建隧道) | `--es profile <名>` 或 `--es rule <名>`;`--es wait` |
| `update` | 热切规则/域名(不重建隧道,不断连) | 同 start;要求隧道运行中 |
| `stop` | 停止接管 | `--es wait` |
| `reset` | 切回 `normal` 预设 | — |
| `load_profile` | 仅校验 profile 文件不启动 | `--es profile <名>` |

**规则来源(start/update)优先级:**
- `--es profile <名>` → 加载 `profiles/<名>.json`,**完整指定** scope/apps/domains/rule(hermetic,推荐 CI 用)。
- `--es rule <名>` → 保留 App 已持久化的 scope/apps,仅**按预设名或已保存规则名覆盖规则**。
- 都不给 → 用 App 当前持久化的活动规则。

内置预设名:`normal` / `light` / `medium` / `severe` / `offline`。

**wait 语义:** `--es wait true` 用默认超时(10s);`--es wait 8000` 指定毫秒;`--es wait false`/`0` 不等。
start 默认会等到 forwarding;status/update/load_profile 默认不等。

---

## 回读(三路,任选)

统一 JSON,同时输出到三处:

1. **广播结果**:`am broadcast` 回显 `result=0`(ok)/`result=1`(error),`data={...}`。
2. **logcat**:`adb logcat -d -s PAKOMO_AUTO:I`。
3. **状态文件**:`adb shell cat /sdcard/Android/data/<pkg>/files/pakomo/status.json`。

```json
{
  "cmd": "start", "flavor": "kernel", "ts": 1723000000000,
  "ok": true, "error": null,
  "stage": "forwarding", "active": true,
  "appliedRule": "medium", "scope": "applications",
  "stats": { "upBps": 1234, "downBps": 5678, "activeFlows": 12,
             "dropped": 0, "delayed": 3, "uptimeMs": 45000 }
}
```

### 错误码(`error` 字段)

| 码 | 含义 | 归属 |
|---|---|---|
| `AUTOMATION_DISABLED` | 自动化未启用(非 debug) | 构建 |
| `BAD_TOKEN` | token 缺失/不匹配 | 调用方 |
| `INVALID_ARGS` | cmd 未知或参数非法 | 调用方 |
| `PROFILE_NOT_FOUND` / `PROFILE_INVALID` | profile 文件缺失/格式错 | 调用方 |
| `NEED_VPN_CONSENT` | 未授予 VPN 权限 | **环境**(见下) |
| `APP_NOT_INSTALLED` | 目标应用未安装(附包名列表) | 环境 |
| `FGS_START_BLOCKED` / `ENGINE_ERROR` | 前台服务/引擎启动失败 | 运行时 |
| `WRONG_STATE` | update 时隧道未运行 | 调用方 |
| `TIMEOUT` | wait 超时未达 forwarding | 运行时 |

---

## VPN 授权(环境前置条件,不在控制流程内)

`VpnService.prepare()` 的系统弹窗属于**设备准备**,不归控制协议。控制层只**断言**该前置条件
(未授权 → `NEED_VPN_CONSENT` 快速失败),从不尝试**满足**它(不点弹窗)。设备侧满足方式:

```bash
# root / 测试镜像,首选:授权后 prepare() 返回 null,无弹窗
adb shell appops set com.pakomo.kernel ACTIVATE_VPN allow
```

受管设备可用 always-on VPN(`settings put secure always_on_vpn_app <pkg>`)。

### Headless 冷启动(应用未开启时)

Android 12+ 限制**从后台启动前台服务**:应用在前台(或刚在前台)时有豁免,冷启动正常;但若应用
从未打开 / 被 `force-stop`,`start` 广播能唤醒进程却会被这条限制拦下。这也是环境前置条件——
Pakomo 声明了 `SYSTEM_ALERT_WINDOW`,**持有该权限即是官方 FGS 后台启动豁免项**,授予它即可
headless 冷启动:

```bash
adb shell appops set com.pakomo.kernel SYSTEM_ALERT_WINDOW allow
```

冒烟脚本已在 setup 里自动授予以上两项。兜底方案:开机后 `am start -n <pkg>/com.pakomo.MainActivity`
把应用拉起一次;或把应用加入电池/deviceidle 白名单。可用
`adb shell am force-stop <pkg>` 后再跑冒烟脚本来验证 headless 路径。

---

## 安全

三层收口(见设计书 §9):
1. **构建门禁(主)**:控制组件只在 `src/debug` 注册,release 产物不含。
2. **token**(可选,默认关):在设备放置 token 文件即开启强制校验——
   ```bash
   adb push token.txt /sdcard/Android/data/<pkg>/files/pakomo/automation.token
   ```
   之后每条命令需 `--es token <值>`。CI setup 时 `adb pull` 读回该值。
3. `UNAUTHORIZED_CALLER` 有意不实现:广播接收器在 onReceive 里拿不到可靠的发送方 uid,
   uid 校验只是安全表演。debug-only + token 才是真实边界。

---

## Profile 文件格式

放 `/sdcard/Android/data/<pkg>/files/pakomo/profiles/<名>.json`,`adb push` 下发。
字段一一对应内部模型;`specialFaults` 复用 App 的持久化格式(键为故障类型名)。

```json
{
  "scope": "applications",
  "apps": ["com.example.sut"],
  "domainsByApp": { "com.example.sut": ["api.example.com"] },
  "domains": [],
  "rule": {
    "id": "auto-medium-reset", "name": "Medium+Reset",
    "latencyMs": 300, "jitterMs": 100, "packetLossPercent": 5,
    "downloadKbps": 512, "uploadKbps": 128,
    "specialFaults": {
      "CONNECTION_RESET": {
        "enabled": true, "resetTiming": "IMMEDIATE",
        "appTargets": { "com.example.sut": { "enabled": true, "domains": ["api.example.com"] } }
      }
    }
  }
}
```

`rule` 也可以是一个字符串(预设名或已保存规则名),例如 `"rule": "medium"`。
`scope` 取 `global` | `applications` | `addresses`。

---

## 双 flavor 对拍

同一 profile 分别在 kernel / hev 安装包上跑,比对 `stats` 与被测表现,把 hev 版当可信基线
守护 kernel 版。示例脚本见 [`scripts/automation-compare.sh`](../scripts/automation-compare.sh)。
