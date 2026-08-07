# Fault Models

English | [简体中文](fault-models.md)

This document defines the behavior of the four special faults and of weak-network shaping. All entries are implemented; their source locations are at the end. Boundaries and known limitations are collected in [Limitations](limitations_EN.md) and are not repeated here.

Understanding fault behavior requires distinguishing two levels:

- **Protocol behavior**: the actions Pakomo can control — refusing a connection, sending a TCP RST, dropping data, synthesizing a DNS response, and holding downstream data.
- **Application error codes**: determined jointly by the Android network stack, the forwarding engine, the parser the app uses, and the Chromium version. Pakomo can only set the intent; it does not guarantee that every device returns the same number. When validating, first confirm that the protocol behavior is correct, then record the application error code; Chromium `net::Error` values are for reference only.

The four special faults are independent, can be enabled simultaneously, and are saved with the rule. At runtime they are decided per protocol type, not with a single branch covering all traffic.

## A. Weak-network shaping

Weak-network shaping provides fixed latency, jitter, packet loss, and rate limiting, layered on the same data plane and not applied redundantly with the special faults.

- In simple mode, latency, jitter, and packet loss are overall (round-trip) values, split by the shaper across the upstream and downstream directions.
- In advanced mode, latency, jitter, and packet loss are set independently per direction; bandwidth is inherently set per direction.
- For the measurement definitions (whether up/down are independent, at which layer loss occurs, differences from speed tests), see [Measurement Methodology](../06-testing/measurement-methodology_EN.md).

## B. Connection Reset

For a matched TCP connection, Pakomo first confirms the connection is established on the tunnel side, then sets `SO_LINGER(0)` on the client socket and closes it immediately, causing the tunnel to present a TCP RST to the app. The target error is `ERR_CONNECTION_RESET (-101)`.

UDP has no TCP RST. For QUIC (UDP 443), Pakomo can only drop data to try to make the app fall back to TCP; whether it falls back is up to the app and is not guaranteed.

## C. DNS Failure

DNS failure only changes the result of plaintext UDP 53 queries; it does not affect data connections.

| Result | RCODE | Protocol behavior | Chromium target (reference only) |
|---|---:|---|---|
| NXDOMAIN | 3 | Synthesize RCODE=3 | `-105 ERR_NAME_NOT_RESOLVED` |
| SERVFAIL | 2 | Synthesize RCODE=2 | Commonly `-137`, some parsers fold it into `-105` |
| REFUSED | 5 | Synthesize RCODE=5 | Commonly `-137` |
| Timeout | No response | Drop the query | Determined by the parser |

The synthesized response preserves the transaction ID and the original Question, is marked as a Response, sets the correct RCODE, carries no forged successful Answer, and is returned from the original DNS server address.

"Block connections after caching" is a separate switch within the DNS fault, off by default. When a DNS cache already exists the app may not issue a query, and a DNS failure cannot change the cached result; with this switch on, TCP connections to the DNS-fault target are reset and non-DNS UDP is dropped. The corresponding logs use `dns-cache-guard-reset` and `dns-cache-guard-drop`, and are not described as DNS response failures.

## D. Network Blackout

A network blackout only handles the target's data connections — TCP, non-DNS UDP, and QUIC — and does not intercept DNS queries (DNS queries are handled separately by DNS failure). This way, uncached domains can still resolve first, so the difference between "immediate" and "silent" shows up in the connection phase.

- **Immediate mode** returns an active failure as soon as possible, without waiting for the app to time out. When the match can be decided before the connection is established (global, whole-app, or the target IP for a domain has already been learned from plaintext DNS), it returns SOCKS `CONNECTION_REFUSED (0x05)`, presenting as `-102`. When a domain is connected for the first time and the target IP has not yet been learned, the connection must first be established to read the TLS SNI or HTTP Host; at that point SOCKS SUCCESS has already been returned and refusal is no longer possible, so it degrades to RST (`-101`).
- **Silent mode** suspends after confirming the connection is established, reads and discards client data, returns nothing, until the app times out or Pakomo's idle limit is reached, presenting as `-7 ERR_TIMED_OUT`. Non-DNS UDP and QUIC are dropped outright.

A network-blackout simulation targets faults on the target traffic; it does not cause Android to broadcast a real network disconnection.

## E. Slow Response / Late Response

Slow response is a distinct fault model, different from ordinary network latency.

| | Ordinary latency (shaping A) | Slow response / Late Response (E) |
|---|---|---|
| What is simulated | Network transit time: request packets sent late, response packets received late | The request has reached the server side, the server takes a long time to return the business response, the connection may still be held, and the result is returned only at the end |
| Affected direction | Both upstream and downstream possible | The client side cannot truly control the server; it approximates "a late response" by holding downstream data |
| Composition | Layered with the special faults on the same data plane | Timed independently; not layered with the base latency by default |

The protocol behavior uses a gate model. For the downstream direction of a matched connection, the first held downstream chunk opens the gate; the gate-open moment is "the arrival time of the first held chunk plus `holdMs`." All downstream data before the gate opens is buffered and released at once when the time comes; the client therefore starts receiving the response after about `holdMs` and then receives it at full speed. Data arriving after the gate opens is released on its own schedule. This model yields a constant lateness rather than shifting the server's whole streaming process.

- The timing origin is the arrival time of the first held downstream chunk.
- Applies only to TCP downstream; QUIC and UDP downstream are not held, and are best-effort, consistent with the first three faults.
- Before the gate opens, the entire response is buffered in memory with no byte cap; a very large download incurs the corresponding memory overhead, a known trade-off.

The "release small responses" threshold (`holdBypassBytes`, default 0, meaning hold everything) means: a connection whose cumulative downstream does not exceed this many bytes is released immediately without holding, to let heartbeats and probes through. This mechanism is an approximation, not reliable heartbeat detection: real heartbeats may be encrypted, fragmented, or encapsulated, and a fixed byte threshold cannot reliably distinguish a heartbeat from the start of a business response. The threshold should be slightly larger than a heartbeat response; on the same keep-alive connection, cumulative bytes are counted across the whole connection, so if many heartbeats accumulate past the threshold before the business request, the business response may be hit by mistake.

Because TLS is not decrypted, slow response acts at the connection level and cannot delay only a single request within one HTTP/2 connection while letting heartbeats through. Per-request delay requires man-in-the-middle decryption and is out of scope; see [Principles & Boundaries](../00-overview/principles-and-boundaries_EN.md).

## Per-protocol execution priority

The same target may be selected by multiple faults; at runtime they are decided per protocol:

- **DNS queries**: DNS failure is decided first; if not matched, forward normally; a network blackout does not handle DNS queries.
- **TCP**: decide, in order, network blackout, DNS cache guard (when enabled), connection reset, ordinary weak-network rules, and finally apply slow response to the downstream.
- **Non-DNS UDP**: decide, in order, network blackout, DNS cache guard (when enabled), the QUIC-fallback assist of connection reset, and ordinary weak-network rules.

## Target-behavior matrix

The Chromium numbers below are validation targets and are not a cross-device guarantee.

| Fault | Mode | Protocol behavior | Chromium target |
|---|---|---|---|
| Connection reset | — | Send RST after establishing | `-101` |
| Network blackout | Immediate | Refuse before connecting; degrade to RST when it cannot be predicted | `-102`; degrades to `-101` |
| Network blackout | Silent | Accept then suspend, return no data | `-7` |
| DNS failure | NXDOMAIN | RCODE 3 | `-105` |
| DNS failure | SERVFAIL, REFUSED | RCODE 2, 5 | Commonly `-137` |
| DNS failure | Timeout | Drop the query | Determined by the parser |
| Slow response | — | Downstream released after holding `holdMs` | Presents as lateness rather than an error; the app side commonly sees timeouts, retries, and a late response to the original request |

## Log action names

Fault hits use the following stable action names: `pre-connect-refused`, `post-connect-reset`, `silent-park`, `udp-drop`, `quic-fallback-drop`, `dns-nxdomain`, `dns-servfail`, `dns-refused`, `dns-timeout`, `dns-cache-guard-reset`, `dns-cache-guard-drop`. Logs do not record DNS message bodies, HTTP content, cookies, credentials, or other application data; duplicate packets on the same connection are de-duplicated, and reconnect storms are summarized and rate-limited per second.

## Where it is implemented

`forwarding/FaultPolicy.kt`, `forwarding/Socks5Server.kt`, `forwarding/DnsMessage.kt`, `data/SpecialFaultCodec.kt`, `core/model/PakomoModels.kt`.
