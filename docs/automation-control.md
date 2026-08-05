# Pakomo 自动化接入

Pakomo 是可由测试代码控制的弱网与网络故障注入工具。debug 和 release APK 使用同一套稳定
协议。仓库不提供语言、测试框架或设备农场封装；接入项目根据所用技术栈实现适配层。
Android explicit ordered broadcast 是协议传输，不要求在终端手工操作。

控制层只解析协议并转发到现有 `VpnServiceController`，不复制引擎或策略逻辑。

---

## 协议范围

自动化协议包含：

- 显式 ordered broadcast 的 action、component 与字符串字段。
- profile JSON 格式、命令语义、错误码和结构化响应。
- release token 校验，以及等待具体配置生效的确认语义。
- 每个响应中的 `protocolVersion`；不兼容变更才递增该整数。

项目适配层负责：

- 按自身框架选择设备并发送协议请求。
- 从项目采用的密钥存储中取得 token，准备设备并下发 profile。
- 解析 JSON、处理超时与错误，并在用例结束或失败时调用 `reset` 和 `stop`。
- 将这些操作组织成自己框架中的 fixture、hook、rule 或 helper。

典型生命周期为：设备准备与认证 → 下发 profile → `start(wait=true)` → 运行被测流程 → 按需
`update`/`status` → `reset(wait=true)` → `stop(wait=true)`。

### 常用语言示例

以下代码直接实现协议调用，不依赖 Pakomo SDK。每个示例都包含设备准备、token/profile 写入、
命令调用和清理；运行前只需安装 release APK、准备本地的 `profiles/checkout.json` 与
`profiles/checkout_offline.json`，并设置 `PAKOMO_TOKEN`。`runSystemUnderTest()` 和
`runReconnectAssertions()` 代表已有的自动化测试流程。

各示例中的 `control` 函数用法相同：

```text
control("status")                                  # 查询当前阶段和统计
control("start",  profile="checkout", wait=true) # 建立 VPN 并等待 profile 生效
control("update", profile="checkout_offline", wait=true) # 运行中热切 profile，不重建隧道
control("reset",  wait=true)                      # 恢复 normal 规则
control("stop",   wait=true)                      # 停止 VPN
```

`profile`、`wait` 等写法是意图示意；复制时按所选语言的参数或 Map 语法调整。

#### Python（pytest / Appium）

```python
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

PACKAGE = "com.alphynia.pakomo.kernel"
COMPONENT = f"{PACKAGE}/com.alphynia.pakomo.automation.ControlReceiver"
DEVICE_FILES = f"/sdcard/Android/data/{PACKAGE}/files/pakomo"
TOKEN = os.environ["PAKOMO_TOKEN"]
# ANDROID_SERIAL 可选；连接多台设备时用于选择目标设备。
ADB = ["adb", *(["-s", os.environ["ANDROID_SERIAL"]] if os.getenv("ANDROID_SERIAL") else [])]


class PakomoError(RuntimeError):
    """保留协议错误码，便于区分设备准备、测试配置和引擎错误。"""

    def __init__(self, response):
        self.code = response.get("error", "UNKNOWN")
        self.response = response
        super().__init__(f'{self.code}: {response.get("message", "")}')


def adb(*args):
    """执行 ADB，不经过 shell；自动应用可选的 ANDROID_SERIAL。"""
    return subprocess.run([*ADB, *args], check=True, capture_output=True, text=True).stdout


def prepare_device(profile: Path, update_profile: Path):
    """每台设备或每个 suite 执行一次：授权、前置启动、写入 token 和两个 profile。"""
    adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
    adb("shell", "am", "start", "-n", f"{PACKAGE}/com.alphynia.pakomo.MainActivity")
    adb("shell", "mkdir", "-p", f"{DEVICE_FILES}/profiles")

    # token 不写入仓库；临时文件仅用于 adb push，完成后自动删除。
    with tempfile.TemporaryDirectory() as directory:
        token_file = Path(directory) / "automation.token"
        token_file.write_text(TOKEN, encoding="utf-8")
        adb("push", str(token_file), f"{DEVICE_FILES}/automation.token")
    adb("push", str(profile), f"{DEVICE_FILES}/profiles/checkout.json")
    adb("push", str(update_profile), f"{DEVICE_FILES}/profiles/checkout_offline.json")


def control(cmd: str, **fields):
    """发送一条 Pakomo 命令，返回已经校验成功的响应字典。"""
    # 所有字段使用 --es 发送为字符串；release 的每条请求都必须携带 token。
    args = [
        "shell", "am", "broadcast",
        "-a", "com.pakomo.automation.CONTROL", "-n", COMPONENT,
        "--es", "cmd", cmd,
    ]
    for key, value in {**fields, "token": TOKEN}.items():
        args += ["--es", key, str(value).lower() if isinstance(value, bool) else str(value)]
    # am broadcast 等待 ordered broadcast 完成，result data 中包含 Pakomo JSON。
    output = adb(*args)
    payload = re.search(r'data="(.*)"$', output, re.MULTILINE).group(1)
    try:
        response = json.loads(payload)
    except json.JSONDecodeError:  # Android 版本可能转义 result data
        response = json.loads(json.loads(f'"{payload}"'))
    if not response["ok"]:
        raise PakomoError(response)
    return response


def run_case():
    try:
        # start：建立 VPN，并等待 checkout profile 真正生效。
        started = control("start", profile="checkout", wait=True)
        assert started["protocolVersion"] == 1 and started["confirmed"]

        # status(wait=True)：独立等待 forwarding，并查询实时阶段和统计。
        status = control("status", wait=True)
        assert status["stage"] == "forwarding" and status["active"]

        runSystemUnderTest()

        # update：不中断 VPN，直接切换到另一个完整 profile。
        updated = control("update", profile="checkout_offline", wait=True)
        assert updated["confirmed"]
        runReconnectAssertions()
    finally:
        # 即使用例失败也恢复正常网络；嵌套 finally 保证 reset 失败后仍尝试 stop。
        try:
            control("reset", wait=True)
        finally:
            control("stop", wait=True)


prepare_device(Path("profiles/checkout.json"), Path("profiles/checkout_offline.json"))
try:
    run_case()
except PakomoError as error:
    if error.code in {"BAD_TOKEN", "NEED_VPN_CONSENT", "APP_NOT_INSTALLED"}:
        raise RuntimeError(f"Pakomo device setup failed: {error}") from error
    if error.code in {"PROFILE_NOT_FOUND", "PROFILE_INVALID", "INVALID_ARGS", "WRONG_STATE"}:
        raise RuntimeError(f"Pakomo test configuration failed: {error}") from error
    raise  # ENGINE_ERROR / TIMEOUT 保留为运行时失败
```

#### TypeScript（Appium / WebdriverIO）

```typescript
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const pkg = "com.alphynia.pakomo.kernel";
const component = `${pkg}/com.alphynia.pakomo.automation.ControlReceiver`;
const deviceFiles = `/sdcard/Android/data/${pkg}/files/pakomo`;
const token = process.env.PAKOMO_TOKEN!;
// ANDROID_SERIAL 可选；连接多台设备时用于选择目标设备。
const serialArgs = process.env.ANDROID_SERIAL ? ["-s", process.env.ANDROID_SERIAL] : [];

class PakomoError extends Error {
  constructor(readonly code: string, readonly response: Record<string, unknown>) {
    super(`${code}: ${String(response.message ?? "")}`);
  }
}

function adb(args: string[]) {
  // 使用参数数组而不是 shell 字符串，并自动应用可选的 ANDROID_SERIAL。
  return execFileSync("adb", [...serialArgs, ...args], { encoding: "utf8" });
}

function prepareDevice(profile: string, updateProfile: string) {
  // 每台设备或每个 suite 执行一次：授权、前置启动、写入 token 和两个 profile。
  adb(["shell", "appops", "set", pkg, "ACTIVATE_VPN", "allow"]);
  adb(["shell", "am", "start", "-n", `${pkg}/com.alphynia.pakomo.MainActivity`]);
  adb(["shell", "mkdir", "-p", `${deviceFiles}/profiles`]);

  const directory = mkdtempSync(join(tmpdir(), "pakomo-"));
  try {
    const tokenFile = join(directory, "automation.token");
    writeFileSync(tokenFile, token, { encoding: "utf8", mode: 0o600 });
    adb(["push", tokenFile, `${deviceFiles}/automation.token`]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
  adb(["push", profile, `${deviceFiles}/profiles/checkout.json`]);
  adb(["push", updateProfile, `${deviceFiles}/profiles/checkout_offline.json`]);
}

function control(cmd: string, fields: Record<string, string> = {}) {
  // 组装字符串 extras，并确保 release 请求携带 token。
  const extras = { cmd, ...fields, token };
  const args = ["shell", "am", "broadcast", "-a", "com.pakomo.automation.CONTROL", "-n", component];
  for (const [key, value] of Object.entries(extras)) args.push("--es", key, value);

  // execFileSync 不经过 shell；返回时 ordered broadcast 已经完成。
  const output = adb(args);
  const payload = output.match(/data="(.*)"$/m)?.[1];
  if (!payload) throw new Error(`Missing Pakomo response: ${output}`);

  let response;
  try {
    response = JSON.parse(payload);
  } catch {
    response = JSON.parse(JSON.parse(`"${payload}"`));
  }
  if (!response.ok) throw new PakomoError(String(response.error ?? "UNKNOWN"), response);
  return response;
}

async function runCase() {
  try {
    // start：建立 VPN，并等待 checkout profile 真正生效。
    const started = control("start", { profile: "checkout", wait: "true" });
    if (started.protocolVersion !== 1 || !started.confirmed) throw new Error("Pakomo not ready");

    // status(wait=true)：独立等待 forwarding，并查询实时阶段和统计。
    const status = control("status", { wait: "true" });
    if (status.stage !== "forwarding" || !status.active) throw new Error("Pakomo is not forwarding");

    await runSystemUnderTest();

    // update：不中断 VPN，直接切换到另一个完整 profile。
    const updated = control("update", { profile: "checkout_offline", wait: "true" });
    if (!updated.confirmed) throw new Error("Pakomo update was not confirmed");
    await runReconnectAssertions();
  } finally {
    // 无论测试结果如何，都恢复 normal 并停止 VPN。
    try {
      control("reset", { wait: "true" });
    } finally {
      control("stop", { wait: "true" });
    }
  }
}

prepareDevice("profiles/checkout.json", "profiles/checkout_offline.json");
try {
  await runCase();
} catch (error) {
  if (error instanceof PakomoError) {
    if (["BAD_TOKEN", "NEED_VPN_CONSENT", "APP_NOT_INSTALLED"].includes(error.code)) {
      throw new Error(`Pakomo device setup failed: ${error.message}`, { cause: error });
    }
    if (["PROFILE_NOT_FOUND", "PROFILE_INVALID", "INVALID_ARGS", "WRONG_STATE"].includes(error.code)) {
      throw new Error(`Pakomo test configuration failed: ${error.message}`, { cause: error });
    }
  }
  throw error; // ENGINE_ERROR / TIMEOUT 保留为运行时失败
}
```

#### Java（JUnit / Appium Java Client）

下面使用 Jackson 解析 JSON；可替换为项目已有的 JSON 库。

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class PakomoExample {
    // kernel/hev 只需替换包名；组件类名和 action 保持不变。
    static final String PACKAGE = "com.alphynia.pakomo.kernel";
    static final String COMPONENT = PACKAGE + "/com.alphynia.pakomo.automation.ControlReceiver";
    static final String DEVICE_FILES = "/sdcard/Android/data/" + PACKAGE + "/files/pakomo";
    static final String TOKEN = Objects.requireNonNull(System.getenv("PAKOMO_TOKEN"));
    // ANDROID_SERIAL 可选；连接多台设备时用于选择目标设备。
    static final String SERIAL = System.getenv("ANDROID_SERIAL");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern DATA = Pattern.compile("data=\\\"(.*)\\\"$", Pattern.MULTILINE);

    static final class PakomoException extends RuntimeException {
        final String code;
        final JsonNode response;

        PakomoException(JsonNode response) {
            super(response.path("error").asText("UNKNOWN") + ": " + response.path("message").asText(""));
            this.code = response.path("error").asText("UNKNOWN");
            this.response = response;
        }
    }

    static String runAdb(List<String> commandArgs) throws Exception {
        // 使用参数列表启动 adb，不拼接 shell 命令；自动应用可选的 ANDROID_SERIAL。
        var args = new ArrayList<String>();
        args.add("adb");
        if (SERIAL != null && !SERIAL.isBlank()) args.addAll(List.of("-s", SERIAL));
        args.addAll(commandArgs);
        var process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }

    static void prepareDevice(Path profile, Path updateProfile) throws Exception {
        // 每台设备或每个 suite 执行一次：授权、前置启动、写入 token 和两个 profile。
        runAdb(List.of("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow"));
        runAdb(List.of("shell", "am", "start", "-n", PACKAGE + "/com.alphynia.pakomo.MainActivity"));
        runAdb(List.of("shell", "mkdir", "-p", DEVICE_FILES + "/profiles"));

        var tokenFile = Files.createTempFile("pakomo-token-", ".txt");
        try {
            Files.writeString(tokenFile, TOKEN, StandardCharsets.UTF_8);
            runAdb(List.of("push", tokenFile.toString(), DEVICE_FILES + "/automation.token"));
        } finally {
            Files.deleteIfExists(tokenFile);
        }
        runAdb(List.of("push", profile.toString(), DEVICE_FILES + "/profiles/checkout.json"));
        runAdb(List.of("push", updateProfile.toString(), DEVICE_FILES + "/profiles/checkout_offline.json"));
    }

    static JsonNode control(String cmd, Map<String, String> fields) throws Exception {
        var args = new ArrayList<>(List.of(
            "shell", "am", "broadcast",
            "-a", "com.pakomo.automation.CONTROL", "-n", COMPONENT,
            "--es", "cmd", cmd
        ));
        // release 的每条命令都携带与设备端 automation.token 相同的 token。
        var extras = new LinkedHashMap<>(fields);
        extras.put("token", TOKEN);
        extras.forEach((key, value) -> args.addAll(List.of("--es", key, value)));

        // ordered broadcast 完成后，从 result data 解析 Pakomo JSON。
        var output = runAdb(args);
        var match = DATA.matcher(output);
        if (!match.find()) throw new IllegalStateException("Missing Pakomo response: " + output);

        // 不同 Android 版本可能对 result data 做一次字符串转义，两种形态都兼容。
        JsonNode response;
        try {
            response = JSON.readTree(match.group(1));
        } catch (Exception escaped) {
            response = JSON.readTree(JSON.readValue("\"" + match.group(1) + "\"", String.class));
        }
        if (!response.path("ok").asBoolean()) throw new PakomoException(response);
        return response;
    }

    static void runCase() throws Exception {
        try {
            // start：建立 VPN，并等待 checkout profile 真正生效。
            var started = control("start", Map.of("profile", "checkout", "wait", "true"));
            if (started.path("protocolVersion").asInt() != 1 || !started.path("confirmed").asBoolean()) {
                throw new IllegalStateException("Pakomo not ready");
            }

            // status(wait=true)：独立等待 forwarding，并查询实时阶段和统计。
            var status = control("status", Map.of("wait", "true"));
            if (!status.path("active").asBoolean() || !"forwarding".equals(status.path("stage").asText())) {
                throw new IllegalStateException("Pakomo is not forwarding");
            }

            runSystemUnderTest();

            // update：不中断 VPN，直接切换到另一个完整 profile。
            var updated = control("update", Map.of("profile", "checkout_offline", "wait", "true"));
            if (!updated.path("confirmed").asBoolean()) throw new IllegalStateException("Update not confirmed");
            runReconnectAssertions();
        } finally {
            // 嵌套 finally 保证 reset 失败后仍会尝试 stop。
            try {
                control("reset", Map.of("wait", "true"));
            } finally {
                control("stop", Map.of("wait", "true"));
            }
        }
    }

    static void runExample() throws Exception {
        prepareDevice(Path.of("profiles/checkout.json"), Path.of("profiles/checkout_offline.json"));
        try {
            runCase();
        } catch (PakomoException error) {
            if (List.of("BAD_TOKEN", "NEED_VPN_CONSENT", "APP_NOT_INSTALLED").contains(error.code)) {
                throw new IllegalStateException("Pakomo device setup failed: " + error.getMessage(), error);
            }
            if (List.of("PROFILE_NOT_FOUND", "PROFILE_INVALID", "INVALID_ARGS", "WRONG_STATE").contains(error.code)) {
                throw new IllegalStateException("Pakomo test configuration failed: " + error.getMessage(), error);
            }
            throw error; // ENGINE_ERROR / TIMEOUT 保留为运行时失败
        }
    }

    static void runSystemUnderTest() {
        // 调用已有的 JUnit / Appium 测试流程。
    }

    static void runReconnectAssertions() {
        // 验证切换到 checkout_offline 后的重连行为。
    }
}
```

#### C++（主机端测试工具）

下面使用 Boost.Process 安全传递 ADB 参数，使用 nlohmann/json 解析响应；示例要求 C++17、Boost 和
nlohmann/json。`runSystemUnderTest()` 可替换为现有 C++ 测试程序或其他测试进程入口。

```cpp
#include <boost/process.hpp>
#include <nlohmann/json.hpp>

#include <chrono>
#include <cstdlib>
#include <exception>
#include <filesystem>
#include <fstream>
#include <regex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace bp = boost::process;
using Json = nlohmann::json;

const std::string package = "com.alphynia.pakomo.kernel";
const std::string component = package + "/com.alphynia.pakomo.automation.ControlReceiver";
const std::string deviceFiles = "/sdcard/Android/data/" + package + "/files/pakomo";

// 由现有 C++ 自动化项目实现。
void runSystemUnderTest();
void runReconnectAssertions();

class PakomoError : public std::runtime_error {
public:
    std::string code;
    Json response;

    explicit PakomoError(Json value)
        : std::runtime_error(
              value.value<std::string>("error", "UNKNOWN") + ": " +
              value.value<std::string>("message", "")),
          code(value.value<std::string>("error", "UNKNOWN")),
          response(std::move(value)) {}
};

std::string requireEnv(const char* name) {
    const char* value = std::getenv(name);
    if (value == nullptr || *value == '\0') throw std::runtime_error(std::string(name) + " is required");
    return value;
}

std::string runAdb(std::vector<std::string> args) {
    // ANDROID_SERIAL 可选；连接多台设备时用于选择目标设备。
    // Boost.Process 直接传参数，不经过 shell 字符串拼接。
    if (const char* serial = std::getenv("ANDROID_SERIAL"); serial != nullptr && *serial != '\0') {
        args.insert(args.begin(), {"-s", serial});
    }

    bp::ipstream stdoutStream;
    bp::child process(bp::search_path("adb"), bp::args(args), bp::std_out > stdoutStream);
    std::ostringstream output;
    for (std::string line; std::getline(stdoutStream, line);) output << line << '\n';
    process.wait();
    if (process.exit_code() != 0) throw std::runtime_error("adb failed with code " + std::to_string(process.exit_code()));
    return output.str();
}

void prepareDevice(const std::filesystem::path& profile, const std::filesystem::path& updateProfile) {
    // 每台设备或每个 suite 执行一次：授权、前置启动、写入 token 和两个 profile。
    runAdb({"shell", "appops", "set", package, "ACTIVATE_VPN", "allow"});
    runAdb({"shell", "am", "start", "-n", package + "/com.alphynia.pakomo.MainActivity"});
    runAdb({"shell", "mkdir", "-p", deviceFiles + "/profiles"});

    const auto nonce = std::chrono::steady_clock::now().time_since_epoch().count();
    const auto tokenFile = std::filesystem::temp_directory_path() /
        ("pakomo-token-" + std::to_string(nonce) + ".txt");
    {
        std::ofstream stream(tokenFile, std::ios::binary);
        if (!stream) throw std::runtime_error("cannot create temporary token file");
        stream << requireEnv("PAKOMO_TOKEN");
    }
    std::filesystem::permissions(
        tokenFile,
        std::filesystem::perms::owner_read | std::filesystem::perms::owner_write,
        std::filesystem::perm_options::replace
    );
    try {
        runAdb({"push", tokenFile.string(), deviceFiles + "/automation.token"});
    } catch (...) {
        std::filesystem::remove(tokenFile);
        throw;
    }
    std::filesystem::remove(tokenFile);
    runAdb({"push", profile.string(), deviceFiles + "/profiles/checkout.json"});
    runAdb({"push", updateProfile.string(), deviceFiles + "/profiles/checkout_offline.json"});
}

Json control(
    const std::string& cmd,
    const std::vector<std::pair<std::string, std::string>>& fields = {}
) {
    std::vector<std::string> args = {
        "shell", "am", "broadcast",
        "-a", "com.pakomo.automation.CONTROL", "-n", component,
        "--es", "cmd", cmd,
    };
    for (const auto& [key, value] : fields) args.insert(args.end(), {"--es", key, value});
    // release 的每条请求都携带与设备端 automation.token 相同的 token。
    args.insert(args.end(), {"--es", "token", requireEnv("PAKOMO_TOKEN")});

    const std::string output = runAdb(std::move(args));
    const std::regex dataPattern(R"(.*data="(.*)"\r?)");
    std::smatch match;
    std::string payload;
    for (std::istringstream lines(output); std::string line; ) {
        if (!std::getline(lines, line)) break;
        if (std::regex_match(line, match, dataPattern)) payload = match[1].str();
    }
    if (payload.empty()) throw std::runtime_error("Pakomo response missing: " + output);

    Json response;
    try {
        response = Json::parse(payload);
    } catch (const Json::parse_error&) {
        // Android 版本可能把 result data 额外转义为字符串。
        response = Json::parse(Json::parse("\"" + payload + "\"").get<std::string>());
    }
    if (!response.value("ok", false)) throw PakomoError(std::move(response));
    return response;
}

void cleanup() {
    // 两条清理命令都尝试执行；若有失败，保留第一条异常。
    std::exception_ptr firstError;
    try { control("reset", {{"wait", "true"}}); } catch (...) { firstError = std::current_exception(); }
    try { control("stop", {{"wait", "true"}}); } catch (...) { if (!firstError) firstError = std::current_exception(); }
    if (firstError) std::rethrow_exception(firstError);
}

void runCase() {
    try {
        // start 返回前等待 checkout profile 真正应用。
        const Json started = control("start", {{"profile", "checkout"}, {"wait", "true"}});
        if (started.value("protocolVersion", 0) != 1 || !started.value("confirmed", false)) {
            throw std::runtime_error("Pakomo not ready");
        }

        // status(wait=true)：独立等待 forwarding，并查询实时阶段和统计。
        const Json status = control("status", {{"wait", "true"}});
        if (!status.value("active", false) || status.value<std::string>("stage", "") != "forwarding") {
            throw std::runtime_error("Pakomo is not forwarding");
        }

        runSystemUnderTest();

        // update：不中断 VPN，直接切换到另一个完整 profile。
        const Json updated = control("update", {{"profile", "checkout_offline"}, {"wait", "true"}});
        if (!updated.value("confirmed", false)) throw std::runtime_error("Pakomo update was not confirmed");
        runReconnectAssertions();
    } catch (...) {
        const auto original = std::current_exception();
        try { cleanup(); } catch (...) { /* 保留原始测试异常 */ }
        std::rethrow_exception(original);
    }
    cleanup();
}

void runExample() {
    prepareDevice("profiles/checkout.json", "profiles/checkout_offline.json");
    try {
        runCase();
    } catch (const PakomoError& error) {
        if (error.code == "BAD_TOKEN" || error.code == "NEED_VPN_CONSENT" || error.code == "APP_NOT_INSTALLED") {
            throw std::runtime_error("Pakomo device setup failed: " + std::string(error.what()));
        }
        if (error.code == "PROFILE_NOT_FOUND" || error.code == "PROFILE_INVALID" ||
            error.code == "INVALID_ARGS" || error.code == "WRONG_STATE") {
            throw std::runtime_error("Pakomo test configuration failed: " + std::string(error.what()));
        }
        throw; // ENGINE_ERROR / TIMEOUT 保留为运行时失败
    }
}
```

---

## 控制协议

适配层按以下协议发送请求。传输必须是发往
`<applicationId>/com.alphynia.pakomo.automation.ControlReceiver` 的显式 ordered broadcast，action 为
`com.pakomo.automation.CONTROL`；字符串字段如下。kernel 与 hev 的 applicationId 分别是
`com.alphynia.pakomo.kernel` 和 `com.alphynia.pakomo.hev`。

一条 `start` 请求在线上的规范形态为：

```yaml
ordered: true
component: com.alphynia.pakomo.kernel/com.alphynia.pakomo.automation.ControlReceiver
action: com.pakomo.automation.CONTROL
extras:
  cmd: start
  profile: checkout
  wait: "true"
  token: "<由运行环境注入>"
```

这是协议数据示例，不是特定语言 SDK。适配层使用项目选定的 Android/ADB 驱动生成等价的显式 ordered
broadcast，并读取 result code 与 result data。

| `cmd` | 作用 | 关键字段 |
|---|---|---|
| `status` | 回读引擎 stage + stats | `wait`(等到 forwarding) |
| `start` | 建 VPN + 接管(冷启,重建隧道) | `profile` 或 `rule`;`wait` |
| `update` | 热切规则/域名(不重建隧道,不断连) | 同 start；要求隧道运行中 |
| `stop` | 停止接管 | `wait` |
| `reset` | 切回 `normal` 预设 | — |
| `load_profile` | 仅校验 profile 文件不启动 | `profile` |

**规则来源(start/update)优先级:**
- `profile` → 加载 `profiles/<名>.json`,**完整指定** scope/apps/domains/rule(推荐自动化用)。
- `rule` → 保留 App 已持久化的 scope/apps,仅**按预设名或已保存规则名覆盖规则**。
- 都不给 → 用 App 当前持久化的活动规则。

内置预设名:`normal` / `light` / `medium` / `severe` / `offline`。

**wait 语义:** `true` 用默认超时(10s);数字字符串指定毫秒;`false`/`0` 不等。
start 默认会等到 forwarding;status/update/load_profile 默认不等。

---

## 响应与诊断

ordered-broadcast 的 result data 是主响应。适配层解析 JSON，并将 `ok=false`、传输超时和非法
响应映射为测试框架中的失败。同一份 JSON 还写入 Logcat 和状态文件，供诊断：

1. **协议响应**：result code = `0`(ok) / `1`(error)，result data 为 JSON。
2. **诊断日志**：Logcat tag `PAKOMO_AUTO`。
3. **诊断快照**：`/sdcard/Android/data/<pkg>/files/pakomo/status.json`。

```json
{
  "protocolVersion": 1, "cmd": "start", "flavor": "kernel", "ts": 1723000000000,
  "ok": true, "error": null,
  "stage": "forwarding", "active": true,
  "appliedRule": "medium", "scope": "applications",
  "stats": { "upBps": 1234, "downBps": 5678, "activeFlows": 12,
             "dropped": 0, "delayed": 3, "uptimeMs": 45000 }
}
```

适配层应先检查 `protocolVersion`。当前版本为 `1`；同一版本内只允许增加可忽略字段，不改变
既有字段含义、命令语义或错误码。

### 错误码(`error` 字段)

| 码 | 含义 | 归属 |
|---|---|---|
| `BAD_TOKEN` | release 未配置 token，或请求 token 缺失/不匹配 | 设备准备/请求 |
| `INVALID_ARGS` | cmd 未知或参数非法 | 请求 |
| `PROFILE_NOT_FOUND` / `PROFILE_INVALID` | profile 文件缺失/格式错 | 请求 |
| `NEED_VPN_CONSENT` | 未授予 VPN 权限 | **环境**(见下) |
| `APP_NOT_INSTALLED` | 目标应用未安装(附包名列表) | 环境 |
| `ENGINE_ERROR` | 前台服务启动、配置应用或引擎运行失败 | 运行时 |
| `WRONG_STATE` | update 时隧道未运行 | 请求 |
| `TIMEOUT` | wait 超时未达 forwarding | 运行时 |

---

## 等待与并发语义

- `wait` 让 `start`/`update`/`reset` 等到**该次配置真正应用**(引擎 `appliedConfigId` 到位)
  才返回,而非仅看 `stage`。成功响应带 `"confirmed":true`;`wait=false`/未等待时为
  `"confirmed":false`,表示"已接受但未确认生效",此时 `appliedRule` 只是**请求值**,不保证已生效。
- 应用失败:冷启动失败 → `ENGINE_ERROR`;热更新(reconfigure)失败 → 也回 `ENGINE_ERROR`
  (引擎单独上报失败的 config id),不再干等到 `TIMEOUT`。
- **命令串行**:控制层用锁串行化 `START/UPDATE/STOP/RESET` 的处理;带等待的命令会持锁到确认完成,
  不会被后一条自动化命令抢跑。`wait=false` 是 fire-and-forget:返回后配置可能被后续命令覆盖,
  因此必须依据 `"confirmed":false` 将其理解为"已接受"而非"已应用"。
- **不支持与 UI 并发**:上述串行化**只覆盖自动化命令之间**。若在自动化运行的同时**手动操作 App
  UI 触发 reconfigure**(改规则/域名),两条路径不互斥,配置确认可能不准确。headless 测试期间
  请勿同时人工操作 UI。

---

## 设备准备与 Android 限制

`VpnService.prepare()` 的系统弹窗属于**设备准备**,不归控制协议。控制层只**断言**该前置条件
(未授权 → `NEED_VPN_CONSENT` 快速失败),从不尝试点击系统弹窗。设备准备流程需先完成
VPN 授权；受管设备也可预置 always-on VPN。

### 冷启动(应用未开启时)

现代 Android(targetSdk 34/36)**从后台启动前台服务**限制很严:应用在前台(或刚在前台)时才有
豁免。若应用从未打开 / 被 `force-stop`,`start` 广播能唤醒进程,但 `startForegroundService` 会被拦
(实测 `SYSTEM_ALERT_WINDOW` 等 appops 豁免在新版本上**不可靠**)。

设备准备流程还需把 Pakomo 前置一次，使紧随其后的 `start` 请求具备启动前台 VPN
服务所需的豁免。过程可以无人值守，但可能短暂显示 Pakomo Activity。纯后台冷启动在新版 Android
上通常不可行，属于平台限制。

---

## 安全

控制组件同时存在于 debug 与 release：

1. **release 默认拒绝**：`automation.token` 不存在或为空时，所有命令返回 `BAD_TOKEN`。
2. **凭据由环境注入**：设备准备流程把 token 写入 Pakomo 的 app-specific external files，并在
   每条请求的 `token` 字段中携带同一值。token 不应提交到仓库或打印到测试日志。
3. **debug 面向开发者**：未配置 token 时允许本地诊断；配置后同样强制匹配。
4. manifest `BroadcastReceiver` 无法可靠获得发送者 uid，因此不以伪造的 caller-uid 检查代替认证。

---

## Profile 文件格式

适配层将 JSON 写入 Pakomo 的 app-specific `pakomo/profiles/<name>.json` 目录，再在请求的
`profile` 字段中引用 `<name>`。建议适配层下发前先校验 JSON。字段一一对应内部模型；
`specialFaults` 复用 App 的持久化格式(键为故障类型名)。以下示例中的 `com.example.sut` 必须替换
为设备上实际安装的被测包名。

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

---

## Pakomo 开发自检

[`scripts/automation-smoke.sh`](../scripts/automation-smoke.sh) 与
[`scripts/automation-smoke.ps1`](../scripts/automation-smoke.ps1) 用于 Pakomo 自身开发时验证底层
广播、profile、等待与 token 通路；这些脚本不是项目集成 API，也不应被复制到自动化项目中。
