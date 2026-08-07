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
  → FlowLog records the connection (protocol, host, port, up/down bytes, whether held, whether shaped, status)
```

## 3. Attribution and domain learning

- **App attribution**: `ConnectivityManager.getConnectionOwnerUid()` resolves on the Kotlin side from the original five-tuple (passed through by the attribution preamble).
- **Domain matching**: `DomainRoutingPolicy` uses the TLS SNI suffix, plus IP matching against target IPs learned from plaintext DNS, to cover QUIC and no-SNI connections.
- **DNS learning**: the relay reads plaintext DNS on the UDP response path via `observeDnsResponse`, learning domain-to-IP mappings across SOCKS sessions; DoH and DoT cannot be learned.
- Shaping and faults reuse the same attributor to keep attribution consistent for one connection and avoid duplicate lookups (`AndroidConnectionAttributor` has an origin-to-packages cache).

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

## 5. Hot update

- Rule and domain changes trigger `reconfigure()` via `ACTION_UPDATE`, building a new runtime in the background, switching the data plane first (`socks.reconfigure`) and, on success, publishing the metadata and calling `publishAppliedConfig(configId)`; on failure it calls `publishFailedConfig`. The whole process does not rebuild the tunnel or drop connections.
- Changes to the takeover scope or selected apps rebuild the TUN interface via `ACTION_START`, because the allowed-app set can only be set at establish time.

## 6. Stopping

`ACTION_STOP` stops the relay and engine, closes the TUN, and publishes `EngineStage.STOPPED`.
