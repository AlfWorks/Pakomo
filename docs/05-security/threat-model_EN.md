# Threat Model

English | [简体中文](threat-model.md)

This document concerns Pakomo's attack surface and how it is contained, as a tool that can take over traffic and be driven programmatically. For the data and privacy boundaries, see [Privacy Boundary](privacy-boundary_EN.md).

## Assets and trust boundaries

- **Traffic under test**: never leaves the device, never decrypted, never persisted; see the privacy boundary.
- **Local SOCKS5**: uses a random port and random credentials, listens only on the loopback, and is not an externally facing service.
- **VPN authorization**: `VpnService` requires a one-time authorization from the user or system (`prepare()`). That authorization is an environmental precondition; Pakomo only asserts its existence and does not bypass it.

## Automation control plane

The automation control protocol lets an external party drive Pakomo via adb broadcasts, including start/stop and switching the fault profile. The control entry `ControlReceiver` is declared in `src/main/AndroidManifest.xml` with `exported="true"` and no permission — **it is included and exported in both debug and release builds** (the component name can be learned from the docs or by decompilation). The real security boundary is therefore not "whether the component exists," but the following containment:

1. **Shared token (the primary boundary)**: `AutomationConfig.verifyToken` checks `automation.token`.
   - **release**: when no token is configured, every command returns `BAD_TOKEN` — installing a release build does not expose an unauthenticated control plane.
   - **debug**: when no token is configured, commands are allowed for local diagnostics; once configured, verification is enforced.
   - Once configured, every command must carry a value that constant-time-matches the token file.
2. **Precondition assertion (not bypassed)**: it only asserts, never satisfies. When the VPN is not authorized it returns `NEED_VPN_CONSENT` and fails fast, without trying to bypass the system dialog.
3. **No state disclosure before authorization**: responses to command-parse failure and token-verification failure (pre-authorization) return only an error code and **carry no runtime snapshot** (`stage`/`stats`), preventing a co-located app from sending a token-less ordered broadcast and reading the engine state from the result side channel.

Pakomo does not use calling-uid-based validation, because a broadcast receiver cannot obtain a reliable sender uid in `onReceive`, and such a check would not constitute a real boundary. **The real boundary is the token** (enforced on release). See the security section of [Automation Control Interface](../automation-control_EN.md).

## Explicit-broadcast requirement

On Android 8 and above a manifest receiver does not receive implicit broadcasts and must be addressed by explicit component (`-n <pkg>/…ControlReceiver`). An arbitrary app therefore cannot trigger the receiver by broadly advertising a generic action.

## Misuse and abuse

Pakomo is for authorized testing only; it does not evade detection, does not hide itself, and shows its running state clearly via a foreground notification and the floating ball. Pakomo provides no large-scale or remote interference capability, contains no remote gateway, and has no externally listening service.

## Out of the threat model

- Platform-level compromise such as the device already being rooted or backdoored is outside Pakomo's defensive scope.
- A domain-level fault missing because the app under test does its own encrypted resolution (DoH, DoT) is a capability limitation, not a security issue.
