# Common Architecture

English | [简体中文](common-architecture.md)

This document explains how Pakomo implements its capabilities. The authoritative list of capabilities is the [Capability Matrix](../01-capabilities/capability-matrix_EN.md).

## Overall path

```
App → TUN → forwarding engine [ Kernel: Tun2SocksEngine (pure Kotlin) | Hev: hev-socks5-tunnel (native) ]
          → local authenticated SOCKS5 (Socks5Server) → protect()-ed socket → target server
```

Device TUN traffic is forwarded by the engine to a SOCKS5 relay that listens only on a random local port; every socket connecting out to a target server is first passed through `VpnService.protect()` and does not loop back into the TUN. After the two engine paths, the relay, shaping, and fault logic is completely identical; the difference is only in the one layer between TUN and SOCKS — see [Kernel Backend](kernel-backend_EN.md) and [Hev Backend](hev-backend_EN.md).

## Layers and responsibilities

| Layer | Key classes | Responsibility |
|---|---|---|
| VPN lifecycle | `vpn/WeakNetworkVpnService`, `vpn/VpnServiceController` | Establish, hot-update, and stop takeover; publish runtime state and `appliedConfigId` |
| Config passing | `vpn/VpnRuntimeConfigStore` | Keep the large config graph in-process; the Intent carries only a one-shot configId, avoiding large Binder transactions |
| Forwarding engine (one of two) | `kernel/Tun2SocksEngine` or `hev-socks5-tunnel` | Convert between raw TUN packets and SOCKS5 streams |
| Local relay | `forwarding/Socks5Server`, `forwarding/NioRelay` | Authenticate SOCKS5, relay per connection, `protect()` outbound |
| Shaping | `shaping/TrafficShaper`, `forwarding/ShapingPolicy` | Latency, jitter, packet loss, and rate limiting |
| Faults | `forwarding/FaultPolicy`, `forwarding/DnsMessage` | Decision and injection of the four special faults |
| Attribution | `vpn/AndroidConnectionAttributor`, `forwarding/DomainRoutingPolicy`, `forwarding/HostSniffer` | Per-app and per-domain attribution based on UID, SNI, and learned IP |
| Observability | `forwarding/FlowLog`, `vpn/TunnelStatsSampler`, `vpn/RecentHitTracker` | Per-connection logging, runtime statistics, and hits |
| Automation (debug) | `app/src/debug/java/com/alphynia/pakomo/automation/` | Driven by adb broadcasts, forwarded to `VpnServiceController` |

## Data-plane principles

- **Single relay**: whichever engine is used, shaping and faults act on the same `Socks5Server` instance; weak-network parameters and special faults are layered on the same data plane and are not applied redundantly.
- **Per-connection decision**: because TLS is not decrypted, the minimum granularity of shaping and faults is the connection.
- **Hot-update consistency**: rule and domain changes are hot-switched via `ACTION_UPDATE` without rebuilding the tunnel or dropping connections; changes to the takeover scope and selected apps rebuild the interface via `ACTION_START`. A config taking effect is confirmed by `appliedConfigId`; for the wait semantics see [Automation Control Interface](../automation-control_EN.md).

## Flavors and building

- The `engine` dimension has two flavors: Kernel (the default, applicationId `com.alphynia.pakomo.kernel`, no native) and Hev (`com.alphynia.pakomo.hev`, native).
- The build script decides whether to compile the native library by "whether the task name contains `hev`"; the two flavors must be invoked separately, otherwise the native code is compiled into the Kernel edition too.
- The automation control component is registered only in debug builds (`src/debug`) and is not present in release artifacts.

## See also

- End-to-end data flow and fault injection points: [Data Flow](data-flow_EN.md).
