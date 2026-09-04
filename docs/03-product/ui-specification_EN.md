# UI Specification

English | [简体中文](ui-specification.md)

This document explains how Pakomo's implemented UI presents its capabilities. For the mapping between visual states and state labels, see [State Mapping](state-mapping_EN.md).

## Design language

- A near-monochrome direction with a single accent and light surfaces (inspired by Clash Meta for Android). The UI is mostly neutral grays, and the accent `#3B6FE0` is used only where it is meaningful — the main switch being on, an active navigation item, primary buttons and the FAB, running and conflict-free states, key numbers, and highlighting a taken-over target.
- Cards are restrained, using hairline dividers, and data such as rates, connection counts, package names, and domains use a monospace font.
- Navigation follows a hub-and-push model. Home is the hub; each feature page is pushed in from an entry and returns via the back arrow; there is no bottom tab bar.
- The theme supports light and dark, switched via `ThemeMode`; for the related visuals see [State Mapping](state-mapping_EN.md).

## Pages

| Page | Key classes | Contents |
|---|---|---|
| Home (hub) | `ui/screens/HomeScreen.kt`, `PakomoApp.kt` | Start/stop status bar with live rate and connection count; an inline segmented control for the takeover scope; rows leading into each sub-page |
| Takeover scope | `ScopeScreen.kt` | Three mutually exclusive single-choice modes — global, selected apps, selected addresses; the app card includes icon, package name, domains, and a takeover switch; domains support subdomain matching. Each domain/address entry can be **enabled/disabled** individually (the leading dot, or tapping the entry text) to pause a rule without deleting it and restore it later; an inline `⋮` menu offers **edit** and **delete**. Only enabled entries reach the runtime; when all of an app's domains are disabled it behaves as having no domain filter (captures the whole app). App list: selected apps float to the top **on the next refresh** (never reordering under the tap), the search field shows a clear button while editing, and tapping outside it drops focus |
| Rule list | `RulesScreen.kt` | Rule cards use single choice, with quantified parameters (e.g. `300ms · jitter 100ms · loss 5% · 512/128 Kbps`); a three-dot menu offers edit, duplicate, and delete; built-in rules are read-only |
| Rule editor | `RuleEditorScreen.kt` | Latency, jitter, packet loss, and up/down rate limiting, distinguishing simple and advanced modes; the special-fault entry is at the bottom |
| Special-fault targets | `SpecialFaultScreens.kt` | Enabling and parameters for the four faults (DNS result, outage behavior, slow-response duration and release threshold), plus selecting app and domain targets |
| Traffic & diagnostics | `UtilityScreens.kt` (`DiagnosticsScreen`) | Default "Traffic" tab: per-connection records with a top-bar search and a filter panel; tapping a row opens a connection detail sheet. The "Diagnostics" tab shows live status, attribution hits, and Logcat |
| Settings | `UtilityScreens.kt` (`SettingsScreen`) | Theme mode and language (Chinese and English, instant switching); a "Shaping" section holds the latency-compensation toggle (off by default, hot-toggleable) |
| Latency test | `LatencyTestScreen.kt` | Measures the target connection latency via the local SOCKS or a direct connection |
| Floating quick control | `overlay/QuickControlService.kt` | Instantly toggles takeover via a floating ball; requires the `SYSTEM_ALERT_WINDOW` permission |

## Traffic page and connection details

"Traffic" is this screen's default tab (diagnostics is secondary).

- **List row**: the destination domain is shown on two lines (a dimmed subdomain prefix on top, the emphasized registrable domain plus port below); a long domain stays on those two lines and never overflows the card. The name comes from TLS SNI / HTTP Host sniffing or observed plaintext DNS; when neither is available it falls back to the destination IP. The second line is metadata: the source app's icon (a small full-colour badge), up/down bytes, a status dot (active/closed), and the start time.
- **Filtering**: a top-bar search box filters by host, port, and protocol; a filter panel offers active/closed, shaped-only, held-only, and a minimum-bytes threshold.
- **Connection details**: tapping a row opens a bottom sheet (no page switch, `ModalBottomSheet`) with the app (icon and name), protocol (with a port-based L7 guess, e.g. `HTTPS (TCP)`), source `IP:port`, SNI / host (split into two lines), destination `IP:port`, status, traffic, payload, duration, first-seen time, and **Pakomo effects** (shaped, held). DNS flows additionally list the **domains queried and the IPs they resolved to**.
- **Tap to copy**: the host, source, destination, and DNS-queried domains copy to the clipboard on tap, to paste into a capture scope or rule.

## Main control and status

The main switch on the Home status bar is the only start/stop control. The status copy comes from the real `EngineStage` — e.g. STOPPED shows as "Stopped," FORWARDING shows as running — and no imaginary intermediate states are presented. When running, it shows the live rate, active connection count, and number of effective rules, with data from `RuntimeStats`.

## The UI obeys the capability boundaries

The actions the UI describes all correspond to capabilities the engine actually has.

- Pakomo does not provide a "restore network automatically" capability, and there is no "emergency restore network" button in the UI. The control that stops takeover performs "stop simulation" or "stop VPN," and its copy states this faithfully, without implying it automatically repairs the device's network. If a "restore network" capability is needed in the future, the engine-side behavior, failure handling, and verifiable boundary must be defined first, and only then the corresponding UI.
- The UI does not show condition states such as "high latency," "packet loss," or "disconnected" derived from real-time statistics, because `RuntimeStats` does not provide such stable derived values; see [Limitations](../01-capabilities/limitations_EN.md).
