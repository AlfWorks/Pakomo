# Capability Matrix

English | [简体中文](capability-matrix.md)

This document lists Pakomo's capabilities and their implementation status, and explains the differences between the two implementation paths, Kernel and Hev.

## Status labels

| Label | Meaning |
|---|---|
| `Implemented` | Present and runnable in the code, with clear evidence |
| `Designed` | The approach is settled, not yet implemented |
| `Experimental` | An implementation or idea exists but still needs validation |
| `Planned` | A roadmap capability, not started |
| `Conceptual` | A conceptual note only; no commitment to implement |
| `Out-of-Scope` | **Deliberately not done** in the current version (a boundary declaration) |

The current version records only `Implemented` and `Out-of-Scope` entries: the former are present-day capabilities, the latter are what this version deliberately does not do.

## Two implementation paths

Pakomo's forwarding engine has two alternative implementations, distinguished by Android build flavor, that **can coexist on the same device**:

- **Kernel (the k edition, `com.alphynia.pakomo.kernel`, the default)**: a self-developed, pure-Kotlin tun2socks kernel (`com.alphynia.pakomo.kernel`) that implements **the subset of user-space tunneling and forwarding that Pakomo's current work requires** (IPv4, TCP/UDP→SOCKS5, ICMP echo, connection reclamation). The Kernel edition is sufficient to carry Pakomo's current features, but is **not** a full Kotlin rewrite of `hev-socks5-tunnel`.
- **Hev (the h edition, `com.alphynia.pakomo.hev`)**: uses the native `hev-socks5-tunnel` forwarding core, retaining a more complete and mature low-level capability range.

The two are different implementation paths, not a full-vs-lite relationship. The relay, shaping, and fault-injection logic is completely identical across the two implementations and lives in the shared `forwarding/Socks5Server` and policy layer; the difference is only in the one layer between TUN and SOCKS. See [Kernel Backend](../02-architecture/kernel-backend_EN.md) and [Hev Backend](../02-architecture/hev-backend_EN.md).

## Capability table

Legend: ✔ supported · ✘ not supported · — not applicable

### Connection-layer capabilities (achievable without decryption)

| Capability | Kernel | Hev | Status | UI-exposed | Boundary / measurement |
|---|:--:|:--:|---|:--:|---|
| Fixed latency | ✔ | ✔ | `Implemented` | Yes | Simple mode splits one overall value across up/down; advanced mode sets each direction independently. See [Measurement Methodology](../06-testing/measurement-methodology_EN.md) |
| Jitter | ✔ | ✔ | `Implemented` | Yes | Distribution model in the measurement methodology |
| Packet loss | ✔ | ✔ | `Implemented` | Yes | Measured differently from third-party speed tests (per-direction vs. combined); see the measurement methodology |
| Bandwidth limit (up/down) | ✔ | ✔ | `Implemented` | Yes | Independent per direction |
| Connection reset (TCP RST) | ✔ | ✔ | `Implemented` | Yes | `SO_LINGER(0)`, best-effort; no RST for UDP/QUIC |
| DNS resolution failure (NXDOMAIN/SERVFAIL/REFUSED/timeout + cache resistance) | ✔ | ✔ | `Implemented` | Yes | Visible only on plaintext UDP 53; DoH/DoT cannot be hit |
| Network outage (silent timeout / immediate failure) | ✔ | ✔ | `Implemented` | Yes | Acts on the connection layer only; does not block DNS queries; does not trigger a real system disconnect broadcast |
| **Slow response / Late Response** (gate hold) | ✔ | ✔ | `Implemented` | Yes | A distinct fault model, separate from ordinary latency; see [Fault Models](fault-models_EN.md) |

### Attribution and observability

| Capability | Kernel | Hev | Status | UI-exposed | Boundary / measurement |
|---|:--:|:--:|---|:--:|---|
| Per-app attribution (UID) | ✔ | ✔ | `Implemented` | Yes | `getConnectionOwnerUid()`; the attribution preamble is identical across both editions |
| Per-domain attribution (SNI + learned IP) | ✔ | ✔ | `Implemented` | Yes | Best-effort for QUIC / no-SNI; IPs cannot be learned for DoH |
| Per-connection traffic log (FlowLog) | ✔ | ✔ | `Implemented` | Yes | TCP only; UDP/QUIC/ICMP not shown |
| Runtime statistics (rate / active connections / cumulative drops / holds / uptime) | ✔ | ✔ | `Implemented` | Yes | `RuntimeStats`; **no** stable derived "current loss rate / current RTT" |
| Automation control protocol (adb-broadcast driven) | ✔ | ✔ | `Implemented` | adb | Included in debug/release; mandatory token on release. See [Automation Control Interface](../automation-control_EN.md) |

### Product / presentation

| Capability | Kernel | Hev | Status | UI-exposed | Boundary / measurement |
|---|:--:|:--:|---|:--:|---|
| Takeover scope: global / selected apps / selected addresses | ✔ | ✔ | `Implemented` | Yes | Three mutually exclusive modes; domain subdomain matching |
| Rule presets + custom rules (with special faults, saved with the rule) | ✔ | ✔ | `Implemented` | Yes | normal/light/medium/severe/offline + user rules |
| Theme switching + Pako artwork | ✔ | ✔ | `Implemented` | Yes | Basic switching (`ThemeMode`) + `PakomoArtwork` |
| Mascot visual states (Stopped/Starting/Running/Idle/Error, 5 states) | ✔ | ✔ | `Implemented` | Yes | Derived from `EngineStage` (`mascotStateOf`), rendered by `StatusDecor`. See [State Mapping](../03-product/state-mapping_EN.md) |
| Localization (CN / EN, instant switching) | ✔ | ✔ | `Implemented` | Yes | |
| Floating quick control (floating-ball takeover toggle) | ✔ | ✔ | `Implemented` | Yes | Requires `SYSTEM_ALERT_WINDOW` |
| Diagnostics (real-time status / attribution hits / Logcat) | ✔ | ✔ | `Implemented` | Yes | |

### Distribution and updates

| Capability | Kernel | Hev | Status | UI-exposed | Boundary / measurement |
|---|:--:|:--:|---|:--:|---|
| In-app self-update (novi) | ✔ | ✔ | `Implemented` | Yes | Based on [novi](https://github.com/AlfWorks/Novi) (`com.alphynia.novi`); two-layer trust of a P-256 manifest signature + APK signer verification; the update source is the dual-track (kernel/hev) published to GitLab Pages when CI tags a release; an in-app dialog runs detect → download → verify → install with verification details. See the novi docs for the trust model and protocol |

### Application-layer content faults — deliberately not done

| Capability | Kernel | Hev | Status | UI-exposed | Boundary |
|---|:--:|:--:|---|:--:|---|
| HTTP status-code injection (404 / 500 / 503) | ✘ | ✘ | `Out-of-Scope` | No | See below |
| Malformed JSON / empty response / truncated response | ✘ | ✘ | `Out-of-Scope` | No | See below |
| Rewriting the response body | ✘ | ✘ | `Out-of-Scope` | No | See below |

Pakomo does not decrypt TLS, does not perform man-in-the-middle (MITM) decryption, does not hold certificates, and does not read or store payloads; see [Principles & Boundaries](../00-overview/principles-and-boundaries_EN.md) for the relevant boundaries. For HTTPS and QUIC traffic, without decryption it is impossible to generically identify and rewrite HTTP responses, so such capabilities are outside Pakomo's supported scope. They hold only locally when all of the following are met, and none is a default Pakomo capability: they apply to plaintext HTTP only, require cooperation from the target app, require a test build to separately provide proxy or certificate capability, or are offered as a standalone experimental extension. See [Limitations](limitations_EN.md) for the full explanation.

## Kernel / Hev differences

| Dimension | Description |
|---|---|
| **Shared capabilities** | Every `Implemented` row above: relay, shaping, the four special faults, attribution, FlowLog, automation, and UI are all identical across editions |
| **Kernel-only** | No third-party native dependency, no NDK required to build; the tunnel layer is a self-developed pure-Kotlin one covering the subset Pakomo currently needs |
| **Hev-only** | The native `hev-socks5-tunnel`, with a more complete and mature low-level capability range |
| **Current functional difference** | **No visible difference** for Pakomo's existing business features (the relay/fault logic is shared) |
| **Consistency requirement** | Business behavior must be consistent; the two paths are validated by the same automation smoke test + a dual-flavor diff |
| **Low-level differences not guaranteed consistent** | Tunnel-layer implementation details (e.g. the maturity of the native stack, handling of edge-case protocols) are not required to match one for one |

If Pakomo adds low-level networking capability in the future, the Kernel needs to be extended in step to reach parity with Hev. This is a natural difference between implementation paths. No such gap exists at present.

## Where it is implemented

- Weak-network shaping: `shaping/TrafficShaper.kt`, `forwarding/ShapingPolicy.kt`
- Special faults: `forwarding/FaultPolicy.kt`, `forwarding/Socks5Server.kt`, `data/SpecialFaultCodec.kt`, `core/model/PakomoModels.kt`
- Attribution: `vpn/AndroidConnectionAttributor.kt`, `forwarding/DomainRoutingPolicy.kt`
- FlowLog: `forwarding/FlowLog.kt`, `core/model/FlowRecord.kt`
- Kernel: `com/alphynia/pakomo/kernel/` (tun/ip/tcp/udp/icmp/socks)
- Mascot states: `ui/components/PakomoArtwork.kt` (`MascotState` / `mascotStateOf` / `StatusDecor`)
- Engine state: `core/model/PakomoModels.kt` (`EngineStage`: STOPPED/STARTING/FORWARDING/ERROR)
- Automation: `app/src/debug/java/com/alphynia/pakomo/automation/`
