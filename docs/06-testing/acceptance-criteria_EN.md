# Acceptance Criteria

English | [简体中文](acceptance-criteria.md)

This document describes how to verify Pakomo's implemented capabilities. The basic principle is to confirm the protocol behavior first and then record the application-side error code; the latter is not the sole pass condition. See [Fault Models](../01-capabilities/fault-models_EN.md) and [Measurement Methodology](measurement-methodology_EN.md).

## 1. Automation smoke test

The automation control protocol ships with a smoke script covering full-path assertions and config-applied confirmation for start, update, stop, status, reset, and load_profile.

- Use `scripts/automation-smoke.sh` on Linux, CI, or Git Bash; use `scripts/automation-smoke.ps1` on Windows.
- It covers starting from stopped, hot update, reset, profile loading, and the token gate (set `TEST_TOKEN=1`).
- See [Automation Control Interface](../automation-control_EN.md) for details.

## 2. Dual-flavor diff

Run the same profile on Kernel (`com.alphynia.pakomo.kernel`) and Hev (`com.alphynia.pakomo.hev`) respectively and compare the behavior-relevant state fields, using the Hev edition as a trusted baseline to guard the Kernel edition. The corresponding script is `scripts/automation-compare.sh`, for local on-demand runs, not part of CI.

## 3. Special-fault verification matrix

**Required scope**: global, whole-app, an in-app selected domain, and a selected domain.
**Required states**: domain uncached and cached; target IP not learned and learned from plaintext DNS; HTTP/TCP, HTTPS/TCP, QUIC/UDP 443, ordinary UDP, system plaintext DNS, and an app using DoH.

The acceptance points for protocol behavior are:

- **Connection reset**: a packet capture or app log can confirm the TCP RST.
- **Network blackout · immediate**: when predictable, the connection is quickly refused; when not predictable, it clearly degrades to RST.
- **Network blackout · silent**: the connection is held but returns no response, until the app or the protective timeout ends it.
- **DNS**: NXDOMAIN is RCODE 3, SERVFAIL is 2, REFUSED is 5, timeout has no response; when a network blackout is enabled separately, DNS still completes normally.
- **Slow response**: the downstream of a matched connection starts arriving after about `holdMs` and is received at full speed, yielding a constant lateness rather than growing in proportion to the response size; under the "release small responses" threshold, small responses are not held.

Application error codes are recorded but are not the sole pass condition; the reference targets are: connection reset `-101`, immediate blackout `-102` (a domain's first connect may be `-101`), silent blackout `-7`, NXDOMAIN `-105`, SERVFAIL and REFUSED `-137`. Testing should cover at least Android 10, one intermediate version, and the current major version, recording the WebView and Chromium versions separately.

## 4. Stability

Under long-running stress, monitor that the file-descriptor count, memory, connection-table size, and coroutine count do not grow unbounded; rapidly toggling the VPN, switching networks, and force-killing apps must not crash.

## 5. Build verification

CI runs `testKernelDebugUnitTest`, `lintKernelRelease`, `assembleKernelRelease`, and `assembleHevRelease`; their definitions are in `.gitlab-ci.yml`. Running only `assembleKernelDebug` locally does not cover unit tests and lint, so it is recommended to also run the above tasks before committing.
