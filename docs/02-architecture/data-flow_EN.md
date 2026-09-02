# Data Flow

English | [简体中文](data-flow.md)

This document traces how a connection is taken over, attributed, shaped, and fault-injected. The definition of fault behavior is authoritative in [Fault Models](../01-capabilities/fault-models_EN.md).

## 1. Establishing takeover

1. The UI or automation triggers `VpnServiceController.start` or `update`, generating a configId, storing the config graph in `VpnRuntimeConfigStore`, with the Intent carrying that configId.
2. `WeakNetworkVpnService` consumes the config, starts the local `Socks5Server` (random port and random credentials), establishes the TUN via `VpnService.Builder.establish()` (setting the allowed apps per the takeover scope), and starts the forwarding engine (Kernel or Hev).
3. Once the pipeline is ready, it publishes `EngineStage.FORWARDING` and `appliedConfigId`.

## 2. The path of a single connection

```
App initiates a connection
  → kernel routes it into the TUN
  → the forwarding engine reassembles it into a stream and, as a SOCKS5 client (carrying the attribution preamble), connects to the local Socks5Server
  → Socks5Server.handleConnect:
       attribution: getConnectionOwnerUid() locates the UID and app; the domain is decided from SNI and learned IPs
       pre-connect fault decision: on "network blackout · immediate" that can be predicted, return refused; on a DNS cache guard hit, reset or drop
       when not matched or when sniffing is needed: first connect out via protect(), read the SNI or HTTP Host, then decide, degrading to RST if necessary
       on a shaping or slow-response hit: apply it on the relay pump
  → the protect()-ed outbound socket connects to the target server
  → downstream data returns to the app through the relay (possibly shaped or held)
  → FlowLog records the connection (protocol, display host, port, up/down bytes, whether held/shaped,
    status, owning app, source/destination IP:port, close time; DNS flows also record the domains
    queried and the IPs they resolved to)
```

## 3. Attribution and domain learning

- **App attribution**: `ConnectivityManager.getConnectionOwnerUid()` resolves on the Kotlin side from the original five-tuple (passed through by the attribution preamble).
- **Domain matching**: `DomainRoutingPolicy` uses the TLS SNI suffix, plus IP matching against target IPs learned from plaintext DNS, to cover QUIC and no-SNI connections.
- **DNS learning**: the relay reads plaintext DNS on the UDP response path via `observeDnsResponse`, learning domain-to-IP mappings across SOCKS sessions; DoH and DoT cannot be learned.
- Shaping and faults reuse the same attributor to keep attribution consistent for one connection and avoid duplicate lookups (`AndroidConnectionAttributor` has an origin-to-packages cache).
- **Display-side reverse lookup**: `DnsNameCache` learns IP→domain on the DNS response path (globally, independent of the shaping/fault domain learning) and, at flow open, maps the destination IP back to a domain for the traffic list — no sniffing, no blocking; when DoH/DoT or pre-capture cached resolution leaves it unknown, it falls back to the IP.
- **Display-side attribution**: `AndroidConnectionAttributor.displayPackageFor` resolves the owning package of any app (not just the selected set) with a single, no-retry lookup for the traffic list's source label, avoiding the retry cost of the shaping/fault path and adding no setup latency.
- **DNS query records**: a DNS flow is aggregated per resolver (`IP:53`); the request path records the queried names and the response path parses A records to record their results, so the connection detail lists each "domain → resolved IPs". IPv4 (A records) only.
- **Snapshot reuse**: a closed connection's `FlowRecord` snapshot is built once and reused (reference-equal), so the once-per-second list refresh only rebuilds the few still-active connections — cutting GC churn as closed connections pile up.

## 4. Injection points for faults and shaping

| Fault | Injection point |
|---|---|
| Network blackout · immediate (predictable) | Before connect in `handleConnect`, return SOCKS refused |
| Network blackout · immediate (domain first connect) | After connecting out and sniffing, degrade to RST |
| Network blackout · silent | Accept then suspend, discard client data |
| Connection reset | After the connection is established, send RST via `SO_LINGER(0)` |
| DNS failure | In the DNS path of `handleUdpAssociate`, synthesize an RCODE or drop |
| Weak-network shaping | On the relay pump, apply latency, jitter, packet loss, and rate limiting |
| Slow response | Gate hold on the downstream `delayedPump` |

## 4.1 Latency compensation (optional)

Off by default; enabled in Settings. When on, each connection takes its own **tunnel setup overhead**
(tun2socks + SOCKS establishment, **excluding** the real outbound RTT and the SNI sniff) as a credit
and draws it down from the injected delay of the connection's **earliest chunks** (floored at 0, shared
across both directions, consumed once), so the configured latency becomes the *observed result* rather
than an addition on top of the baseline; once spent, the steady stream is unaffected.

- **Measuring the overhead**: the Kernel engine records the SYN-arrival time in `TcpConnection.accept()`;
  `Socks5Client` records it (keyed by the loopback port) in `ConnectionSetupRegistry` **before** writing
  the preamble, and `Socks5Server` takes it **after** reading the preamble — the write→read edge makes it
  visible with no race. Overhead = pre-connect time − SYN time.
- **Draw-down**: `drawDownCompensation` (atomic CAS, floored at 0, capped at 2 s) applies in `delayedPump`.
- **HEV**: the native preamble carries no SYN time, so the lookup misses and compensation degrades to the
  SOCKS-visible overhead only.
- Compensates only the tool's own overhead, **not the real network RTT**; when the configured latency is
  below the tool overhead it cannot be fully offset (injection is never negative). See
  [Limitations](../01-capabilities/limitations_EN.md).

## 5. Hot update

- Rule and domain changes trigger `reconfigure()` via `ACTION_UPDATE`, building a new runtime in the background, switching the data plane first (`socks.reconfigure`) and, on success, publishing the metadata and calling `publishAppliedConfig(configId)`; on failure it calls `publishFailedConfig`. The whole process does not rebuild the tunnel or drop connections.
- Changes to the takeover scope or selected apps rebuild the TUN interface via `ACTION_START`, because the allowed-app set can only be set at establish time.
- The latency-compensation toggle hot-refreshes the running `Socks5Server` via `ACTION_SET_COMPENSATION`, taking effect on new connections without rebuilding the tunnel; while the tunnel is stopped it only writes the preference, which is read on the next start.

## 6. Stopping

`ACTION_STOP` stops the relay and engine, closes the TUN, and publishes `EngineStage.STOPPED`.
