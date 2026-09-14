# Current Status

English | [简体中文](current-status.md)

> A snapshot of the present state. The authoritative list of capabilities is the [Capability Matrix](../01-capabilities/capability-matrix_EN.md); this document is only a narrative overview.
> It records only what is **Implemented** today and the boundaries that are **Out-of-Scope**; abandoned future ideas are not listed here.

## Implemented

- **Two coexisting forwarding engines**: Kernel (a self-developed, pure-Kotlin tun2socks, `com.alphynia.pakomo.kernel`, the default, no NDK) and Hev (the native `hev-socks5-tunnel`, `com.alphynia.pakomo.hev`). The relay/shaping/fault logic is identical across the two. The Kernel edition **passed on-device acceptance in 2026-08**. TCP forwarding runs on a **shared event loop (NIO reactor)**: non-blocking, with no per-connection read/write threads, and corrects half-close / FIN sequencing, handshake-carried data delivery, inbound back-pressure, and idle reclamation — steadier under weak networks, long-lived connections, and network switches.
- **Weak-network shaping**: latency / jitter / packet loss / up- and down-link rate limiting, in a simple and an advanced (per-direction) mode.
- **Four special faults**: connection reset; DNS failure (NXDOMAIN/SERVFAIL/REFUSED/timeout, plus cache resistance); network outage (silent / immediate); slow response (late response, with a gate that holds traffic and a small-response release threshold). Saved with the rule; can be enabled simultaneously.
- **Takeover scope**: global / selected apps / selected addresses, with domain subdomain matching.
- **Attribution and observability**: attribution by UID + SNI/learned IP; per-connection FlowLog on the traffic page (owning app, source/dest IP, duration, shaped/held; tap a row for a detail sheet; DNS records queried domains and resolved IPs; key fields are copyable); runtime statistics (including **per-connection latency**: measured handshake RTT + injected shaping delay); real-time diagnostics + Logcat.
- **Automation control protocol**: driven by adb broadcasts (start/update/stop/status/reset/load_profile), three-way read-back, config-applied confirmation, mandatory token on release. The control component is included in debug builds only. See [automation-control](../automation-control_EN.md).
- **Product**: theme switching + Pako artwork, five mascot visual states (bound to `EngineStage`), first-launch language following the system locale, instant CN/EN switching, and an **edge floating-window quick control** (one-tap start/stop + in-place rule switching; a **per-connection latency-trend chart** slides out sideways — a coloured curve per connection plus a composite average, updating live on a rule switch to confirm shaping is taking effect).

## Out-of-Scope

- Application-layer content faults (HTTP status-code injection, malformed JSON, empty/truncated responses, rewriting the body) — they violate the no-decrypt / no-MITM boundary.

## See also

- The basis for the boundaries: [Principles & Boundaries](principles-and-boundaries_EN.md)
