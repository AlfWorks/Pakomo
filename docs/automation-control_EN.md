# Pakomo Automation Integration

English | [简体中文](automation-control.md)

Pakomo is a weak-network and network-fault injection tool that can be controlled by test code. The debug and release APKs use the same stable protocol. The repository provides no language, test-framework, or device-farm wrapper; an integrating project implements an adapter layer for its own stack. The Android explicit ordered broadcast is the protocol transport and does not require manual terminal operation.

The control layer only parses the protocol and forwards to the existing `VpnServiceController`; it does not duplicate engine or policy logic.

---

## Protocol scope

The automation protocol covers:

- The action, component, and string fields of the explicit ordered broadcast.
- The profile JSON format, command semantics, error codes, and structured responses.
- Release token verification, and the confirmation semantics of waiting for a specific config to take effect.
- A `protocolVersion` in every response; only incompatible changes bump this integer.

The project's adapter layer is responsible for:

- Selecting a device and sending protocol requests using its own framework.
- Obtaining the token from the project's secret store, preparing the device, and delivering profiles.
- Parsing JSON, handling timeouts and errors, and calling `reset` and `stop` at the end of a case or on failure.
- Organizing these operations into its framework's fixtures, hooks, rules, or helpers.

A typical lifecycle is: device preparation and authentication → deliver profile → `start(wait=true)` → run the flow under test → `update`/`status` as needed → `reset(wait=true)` → `stop(wait=true)`.

### Common language examples

The code below implements the protocol calls directly, without depending on a Pakomo SDK. Each example includes device preparation, token/profile writing, command invocation, and cleanup; before running, you only need to install the release APK, prepare local `profiles/checkout.json` and `profiles/checkout_offline.json`, and set `PAKOMO_TOKEN`. `runSystemUnderTest()` and `runReconnectAssertions()` represent your existing automated test flow.

The `control` function is used the same way in each example:

```text
control("status")                                  # Query the current stage and stats
control("start",  profile="checkout", wait=true) # Establish the VPN and wait for the profile to take effect
control("update", profile="checkout_offline", wait=true) # Hot-switch the profile while running, without rebuilding the tunnel
control("reset",  wait=true)                      # Restore the normal rule
control("stop",   wait=true)                      # Stop the VPN
```

The `profile`, `wait`, etc. notation is illustrative of intent; when copying, adjust to the argument or map syntax of your chosen language.

#### Python (pytest / Appium)

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
# ANDROID_SERIAL is optional; used to select the target device when several are connected.
ADB = ["adb", *(["-s", os.environ["ANDROID_SERIAL"]] if os.getenv("ANDROID_SERIAL") else [])]


class PakomoError(RuntimeError):
    """Retains the protocol error code, to distinguish device-prep, test-config, and engine errors."""

    def __init__(self, response):
        self.code = response.get("error", "UNKNOWN")
        self.response = response
        super().__init__(f'{self.code}: {response.get("message", "")}')


def adb(*args):
    """Run ADB without going through a shell; auto-applies the optional ANDROID_SERIAL."""
    return subprocess.run([*ADB, *args], check=True, capture_output=True, text=True).stdout


def prepare_device(profile: Path, update_profile: Path):
    """Run once per device or per suite: authorize, foreground-launch, write the token and two profiles."""
    adb("shell", "appops", "set", PACKAGE, "ACTIVATE_VPN", "allow")
    adb("shell", "am", "start", "-n", f"{PACKAGE}/com.alphynia.pakomo.MainActivity")
    adb("shell", "mkdir", "-p", f"{DEVICE_FILES}/profiles")

    # The token is not committed to the repo; the temp file is only for adb push and is deleted afterward.
    with tempfile.TemporaryDirectory() as directory:
        token_file = Path(directory) / "automation.token"
        token_file.write_text(TOKEN, encoding="utf-8")
        adb("push", str(token_file), f"{DEVICE_FILES}/automation.token")
    adb("push", str(profile), f"{DEVICE_FILES}/profiles/checkout.json")
    adb("push", str(update_profile), f"{DEVICE_FILES}/profiles/checkout_offline.json")


def control(cmd: str, **fields):
    """Send one Pakomo command and return the already-validated response dict."""
    # All fields are sent as strings via --es; every release request must carry the token.
    args = [
        "shell", "am", "broadcast",
        "-a", "com.pakomo.automation.CONTROL", "-n", COMPONENT,
        "--es", "cmd", cmd,
    ]
    for key, value in {**fields, "token": TOKEN}.items():
        args += ["--es", key, str(value).lower() if isinstance(value, bool) else str(value)]
    # am broadcast waits for the ordered broadcast to finish; the result data contains the Pakomo JSON.
    output = adb(*args)
    payload = re.search(r'data="(.*)"$', output, re.MULTILINE).group(1)
    try:
        response = json.loads(payload)
    except json.JSONDecodeError:  # some Android versions escape the result data
        response = json.loads(json.loads(f'"{payload}"'))
    if not response["ok"]:
        raise PakomoError(response)
    return response


def run_case():
    try:
        # start: establish the VPN and wait for the checkout profile to actually take effect.
        started = control("start", profile="checkout", wait=True)
        assert started["protocolVersion"] == 1 and started["confirmed"]

        # status(wait=True): independently wait for forwarding, and query the live stage and stats.
        status = control("status", wait=True)
        assert status["stage"] == "forwarding" and status["active"]

        runSystemUnderTest()

        # update: switch to another complete profile without interrupting the VPN.
        updated = control("update", profile="checkout_offline", wait=True)
        assert updated["confirmed"]
        runReconnectAssertions()
    finally:
        # Restore the normal network even if the case failed; the nested finally still attempts stop after a failed reset.
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
    raise  # ENGINE_ERROR / TIMEOUT are kept as runtime failures
```

#### TypeScript (Appium / WebdriverIO)

```typescript
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const pkg = "com.alphynia.pakomo.kernel";
const component = `${pkg}/com.alphynia.pakomo.automation.ControlReceiver`;
const deviceFiles = `/sdcard/Android/data/${pkg}/files/pakomo`;
const token = process.env.PAKOMO_TOKEN!;
// ANDROID_SERIAL is optional; used to select the target device when several are connected.
const serialArgs = process.env.ANDROID_SERIAL ? ["-s", process.env.ANDROID_SERIAL] : [];

class PakomoError extends Error {
  constructor(readonly code: string, readonly response: Record<string, unknown>) {
    super(`${code}: ${String(response.message ?? "")}`);
  }
}

function adb(args: string[]) {
  // Use an argument array rather than a shell string, and auto-apply the optional ANDROID_SERIAL.
  return execFileSync("adb", [...serialArgs, ...args], { encoding: "utf8" });
}

function prepareDevice(profile: string, updateProfile: string) {
  // Run once per device or per suite: authorize, foreground-launch, write the token and two profiles.
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
  // Assemble the string extras, ensuring the release request carries the token.
  const extras = { cmd, ...fields, token };
  const args = ["shell", "am", "broadcast", "-a", "com.pakomo.automation.CONTROL", "-n", component];
  for (const [key, value] of Object.entries(extras)) args.push("--es", key, value);

  // execFileSync does not go through a shell; the ordered broadcast has finished by the time it returns.
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
    // start: establish the VPN and wait for the checkout profile to actually take effect.
    const started = control("start", { profile: "checkout", wait: "true" });
    if (started.protocolVersion !== 1 || !started.confirmed) throw new Error("Pakomo not ready");

    // status(wait=true): independently wait for forwarding, and query the live stage and stats.
    const status = control("status", { wait: "true" });
    if (status.stage !== "forwarding" || !status.active) throw new Error("Pakomo is not forwarding");

    await runSystemUnderTest();

    // update: switch to another complete profile without interrupting the VPN.
    const updated = control("update", { profile: "checkout_offline", wait: "true" });
    if (!updated.confirmed) throw new Error("Pakomo update was not confirmed");
    await runReconnectAssertions();
  } finally {
    // Whatever the test result, restore normal and stop the VPN.
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
  throw error; // ENGINE_ERROR / TIMEOUT are kept as runtime failures
}
```

#### Java (JUnit / Appium Java Client)

The following uses Jackson to parse JSON; you can substitute your project's existing JSON library.

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
    // For kernel/hev only the package name changes; the component class name and action stay the same.
    static final String PACKAGE = "com.alphynia.pakomo.kernel";
    static final String COMPONENT = PACKAGE + "/com.alphynia.pakomo.automation.ControlReceiver";
    static final String DEVICE_FILES = "/sdcard/Android/data/" + PACKAGE + "/files/pakomo";
    static final String TOKEN = Objects.requireNonNull(System.getenv("PAKOMO_TOKEN"));
    // ANDROID_SERIAL is optional; used to select the target device when several are connected.
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
        // Start adb with an argument list rather than concatenating a shell command; auto-applies the optional ANDROID_SERIAL.
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
        // Run once per device or per suite: authorize, foreground-launch, write the token and two profiles.
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
        // Every release command carries the same token as the device-side automation.token.
        var extras = new LinkedHashMap<>(fields);
        extras.put("token", TOKEN);
        extras.forEach((key, value) -> args.addAll(List.of("--es", key, value)));

        // After the ordered broadcast finishes, parse the Pakomo JSON from the result data.
        var output = runAdb(args);
        var match = DATA.matcher(output);
        if (!match.find()) throw new IllegalStateException("Missing Pakomo response: " + output);

        // Different Android versions may escape the result data once as a string; both forms are handled.
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
            // start: establish the VPN and wait for the checkout profile to actually take effect.
            var started = control("start", Map.of("profile", "checkout", "wait", "true"));
            if (started.path("protocolVersion").asInt() != 1 || !started.path("confirmed").asBoolean()) {
                throw new IllegalStateException("Pakomo not ready");
            }

            // status(wait=true): independently wait for forwarding, and query the live stage and stats.
            var status = control("status", Map.of("wait", "true"));
            if (!status.path("active").asBoolean() || !"forwarding".equals(status.path("stage").asText())) {
                throw new IllegalStateException("Pakomo is not forwarding");
            }

            runSystemUnderTest();

            // update: switch to another complete profile without interrupting the VPN.
            var updated = control("update", Map.of("profile", "checkout_offline", "wait", "true"));
            if (!updated.path("confirmed").asBoolean()) throw new IllegalStateException("Update not confirmed");
            runReconnectAssertions();
        } finally {
            // The nested finally still attempts stop after a failed reset.
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
            throw error; // ENGINE_ERROR / TIMEOUT are kept as runtime failures
        }
    }

    static void runSystemUnderTest() {
        // Invoke your existing JUnit / Appium test flow.
    }

    static void runReconnectAssertions() {
        // Verify the reconnect behavior after switching to checkout_offline.
    }
}
```

#### C++ (host-side test tool)

The following uses Boost.Process to pass ADB arguments safely and nlohmann/json to parse the response; the example requires C++17, Boost, and nlohmann/json. `runSystemUnderTest()` can be replaced by an existing C++ test program or another test-process entry point.

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

// Implemented by the existing C++ automation project.
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
    // ANDROID_SERIAL is optional; used to select the target device when several are connected.
    // Boost.Process passes arguments directly, without concatenating a shell string.
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
    // Run once per device or per suite: authorize, foreground-launch, write the token and two profiles.
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
    // Every release request carries the same token as the device-side automation.token.
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
        // Some Android versions additionally escape the result data as a string.
        response = Json::parse(Json::parse("\"" + payload + "\"").get<std::string>());
    }
    if (!response.value("ok", false)) throw PakomoError(std::move(response));
    return response;
}

void cleanup() {
    // Both cleanup commands are attempted; if either fails, the first exception is preserved.
    std::exception_ptr firstError;
    try { control("reset", {{"wait", "true"}}); } catch (...) { firstError = std::current_exception(); }
    try { control("stop", {{"wait", "true"}}); } catch (...) { if (!firstError) firstError = std::current_exception(); }
    if (firstError) std::rethrow_exception(firstError);
}

void runCase() {
    try {
        // start returns only after the checkout profile is actually applied.
        const Json started = control("start", {{"profile", "checkout"}, {"wait", "true"}});
        if (started.value("protocolVersion", 0) != 1 || !started.value("confirmed", false)) {
            throw std::runtime_error("Pakomo not ready");
        }

        // status(wait=true): independently wait for forwarding, and query the live stage and stats.
        const Json status = control("status", {{"wait", "true"}});
        if (!status.value("active", false) || status.value<std::string>("stage", "") != "forwarding") {
            throw std::runtime_error("Pakomo is not forwarding");
        }

        runSystemUnderTest();

        // update: switch to another complete profile without interrupting the VPN.
        const Json updated = control("update", {{"profile", "checkout_offline"}, {"wait", "true"}});
        if (!updated.value("confirmed", false)) throw std::runtime_error("Pakomo update was not confirmed");
        runReconnectAssertions();
    } catch (...) {
        const auto original = std::current_exception();
        try { cleanup(); } catch (...) { /* preserve the original test exception */ }
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
        throw; // ENGINE_ERROR / TIMEOUT are kept as runtime failures
    }
}
```

---

## Control protocol

The adapter layer sends requests per the following protocol. The transport must be an explicit ordered broadcast to `<applicationId>/com.alphynia.pakomo.automation.ControlReceiver` with action `com.pakomo.automation.CONTROL`; the string fields are below. The applicationIds for kernel and hev are `com.alphynia.pakomo.kernel` and `com.alphynia.pakomo.hev` respectively.

The canonical on-the-wire form of a `start` request is:

```yaml
ordered: true
component: com.alphynia.pakomo.kernel/com.alphynia.pakomo.automation.ControlReceiver
action: com.pakomo.automation.CONTROL
extras:
  cmd: start
  profile: checkout
  wait: "true"
  token: "<injected by the runtime environment>"
```

This is an example of the protocol data, not a specific-language SDK. The adapter layer uses the project's chosen Android/ADB driver to produce an equivalent explicit ordered broadcast and to read the result code and result data.

| `cmd` | Effect | Key fields |
|---|---|---|
| `status` | Read back the engine stage + stats | `wait` (wait until forwarding) |
| `start` | Establish the VPN + take over (cold start, rebuilds the tunnel) | `profile` or `rule`; `wait` |
| `update` | Hot-switch rules/domains (no tunnel rebuild, no dropped connections) | Same as start; requires the tunnel running |
| `stop` | Stop takeover | `wait` |
| `reset` | Switch back to the `normal` preset | — |
| `load_profile` | Only validate the profile file, do not start | `profile` |

**Rule-source (start/update) priority:**
- `profile` → load `profiles/<name>.json`, **fully specifying** scope/apps/domains/rule (recommended for automation).
- `rule` → keep the app's persisted scope/apps, only **override the rule by preset name or saved rule name**.
- Neither → use the app's currently persisted active rule.

Built-in preset names: `normal` / `light` / `medium` / `severe` / `offline`.

**wait semantics:** `true` uses the default timeout (10s); a numeric string specifies milliseconds; `false`/`0` does not wait. start waits until forwarding by default; status/update/load_profile do not wait by default.

---

## Response and diagnostics

The result data of the ordered broadcast is the primary response. The adapter layer parses the JSON and maps `ok=false`, a transport timeout, and an invalid response to a failure in its test framework. The same JSON is also written to Logcat and a status file for diagnostics:

1. **Protocol response**: result code = `0` (ok) / `1` (error), result data is JSON.
2. **Diagnostic log**: Logcat tag `PAKOMO_AUTO`.
3. **Diagnostic snapshot**: `/sdcard/Android/data/<pkg>/files/pakomo/status.json`.

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

The adapter layer should check `protocolVersion` first. The current version is `1`; within the same version only ignorable fields may be added, without changing the meaning of existing fields, the command semantics, or the error codes.

### Error codes (the `error` field)

| Code | Meaning | Attribution |
|---|---|---|
| `BAD_TOKEN` | No token configured on release, or the request token is missing/mismatched | Device prep / request |
| `INVALID_ARGS` | Unknown cmd or invalid arguments | Request |
| `PROFILE_NOT_FOUND` / `PROFILE_INVALID` | Profile file missing / malformed | Request |
| `NEED_VPN_CONSENT` | VPN permission not granted | **Environment** (see below) |
| `APP_NOT_INSTALLED` | Target app not installed (includes a package list) | Environment |
| `ENGINE_ERROR` | Foreground service start, config apply, or engine run failed | Runtime |
| `WRONG_STATE` | The tunnel is not running at update time | Request |
| `TIMEOUT` | wait timed out before reaching forwarding | Runtime |

---

## Wait and concurrency semantics

- `wait` makes `start`/`update`/`reset` return only when **that config is actually applied** (the engine's `appliedConfigId` is in place), rather than looking only at `stage`. A successful response carries `"confirmed":true`; with `wait=false`/no wait it is `"confirmed":false`, meaning "accepted but not confirmed to have taken effect," in which case `appliedRule` is only the **requested value** and is not guaranteed to be in effect.
- Apply failure: a cold-start failure → `ENGINE_ERROR`; a hot-update (reconfigure) failure → also returns `ENGINE_ERROR` (the engine separately reports the failed config id), instead of waiting idly until `TIMEOUT`.
- **Commands are serialized**: the control layer serializes the handling of `START/UPDATE/STOP/RESET` with a lock; a waiting command holds the lock until confirmation completes and will not be jumped by a later automation command. `wait=false` is fire-and-forget: after it returns, the config may be overwritten by a subsequent command, so it must be understood, per `"confirmed":false`, as "accepted" rather than "applied."
- **Concurrency with the UI is not supported**: the serialization above **covers automation commands only**. If you **manually operate the app UI to trigger a reconfigure** (changing rules/domains) while automation is running, the two paths are not mutually exclusive and config confirmation may be inaccurate. Do not operate the UI manually during a headless test.

---

## Device preparation and Android restrictions

The `VpnService.prepare()` system dialog is part of **device preparation**, not the control protocol. The control layer only **asserts** this precondition (not authorized → `NEED_VPN_CONSENT` fails fast) and never tries to click the system dialog. The device-preparation flow must complete VPN authorization first; a managed device can also preset an always-on VPN.

### Cold start (when the app is not open)

Modern Android (targetSdk 34/36) is strict about **starting a foreground service from the background**: an app is exempt only while it is in the foreground (or was just there). If the app has never been opened / was `force-stop`ped, the `start` broadcast can wake the process, but `startForegroundService` is blocked (in practice, appops exemptions such as `SYSTEM_ALERT_WINDOW` are **unreliable** on newer versions).

The device-preparation flow therefore also needs to bring Pakomo to the foreground once, so the immediately following `start` request has the exemption needed to start the foreground VPN service. The process can be unattended but may briefly show the Pakomo Activity. A pure background cold start is generally not feasible on newer Android, which is a platform restriction.

---

## Security

The control component exists in both debug and release:

1. **Release rejects by default**: when `automation.token` is absent or empty, all commands return `BAD_TOKEN`.
2. **Credentials injected by the environment**: the device-preparation flow writes the token into Pakomo's app-specific external files (`getExternalFilesDir()/pakomo/automation.token`) and carries the same value in every request's `token` field. External files are chosen so that **a release (non-debuggable) build can still be injected via adb push**; the internal `/data/data/<pkg>/` cannot be written to a release build via `run-as`. The token must not be committed to the repo or printed to test logs (use a masked variable in CI, and do not print the full command containing `--es token`).
3. **Debug is for developers**: local diagnostics are allowed when no token is configured; once configured, matching is enforced too. Debug builds are not distributed externally.
4. **No state disclosure before authorization**: an unknown/missing `cmd` and a token-verification failure go through `StatusReporter.rejected` — the response contains only the error code and necessary metadata, and **does not carry** runtime snapshots such as `stage`/`stats`. Success/failure responses after authorization may still include the snapshot.
5. A manifest `BroadcastReceiver` cannot reliably obtain the sender uid, so a forged caller-uid check does not replace authentication; **the token is the boundary**.

For the fuller attack surface and handling, see [Threat Model](05-security/threat-model_EN.md).

---

## Profile file format

The adapter layer writes the JSON into Pakomo's app-specific `pakomo/profiles/<name>.json` directory, then references `<name>` in the request's `profile` field. It is recommended that the adapter validate the JSON before delivery. Fields map one-to-one to the internal model; `specialFaults` reuses the app's persistence format (keyed by fault-type name). The `com.example.sut` in the example below must be replaced with the actual package name of the app under test installed on the device.

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

`rule` may also be a string (a preset name or a saved rule name), e.g. `"rule": "medium"`. `scope` takes `global` | `applications` | `addresses`.

---

## Dual-flavor diff

Run the same profile on the kernel / hev installs respectively, compare `stats` and the behavior under test, and use the hev edition as a trusted baseline to guard the kernel edition. See the example script [`scripts/automation-compare.sh`](../scripts/automation-compare.sh).

---

## Pakomo development self-check

[`scripts/automation-smoke.sh`](../scripts/automation-smoke.sh) and [`scripts/automation-smoke.ps1`](../scripts/automation-smoke.ps1) are for verifying the low-level broadcast, profile, wait, and token path during Pakomo's own development; these scripts are not a project-integration API and should not be copied into an automation project.
