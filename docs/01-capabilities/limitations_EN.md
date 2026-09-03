# Limitations

English | [简体中文](limitations.md)

This document records the boundaries and known limitations of Pakomo's current capabilities. The hard boundaries tied to the product's positioning (no decryption, no man-in-the-middle decryption, no modification of packet content, etc.) are in [Principles & Boundaries](../00-overview/principles-and-boundaries_EN.md).

## Granularity

- The minimum granularity of shaping and faults is the connection. Because TLS is not decrypted, Pakomo cannot delay or alter only a single request within one HTTP/2 connection, nor delay only the business request while letting heartbeats through.
- The slow-response "release small responses" mechanism is based on a cumulative downstream byte threshold and is an approximation; it cannot reliably identify heartbeats that are encrypted, fragmented, or encapsulated. A poorly chosen threshold will hit the start of a business response by mistake, or fail to let heartbeats through.

## Application-layer content

Pakomo does not support application-layer content faults. HTTP 404/500/503, malformed JSON, empty responses, truncated responses, and rewriting the response body are all out of scope for HTTPS and QUIC, because these capabilities require man-in-the-middle decryption, plaintext traffic, or certificates, which conflict with the no-decryption boundary.

## DNS

- Pakomo only handles the plaintext UDP 53 queries visible in the tunnel; TCP-53 DNS, DoH, DoT, and an app's own encrypted resolution are not guaranteed for now.
- If an app bypasses system plaintext DNS entirely, a domain-level DNS fault may not be hit.
- The content of DoH and DoT cannot be modified by query domain, and their target IPs cannot be learned from plaintext DNS, so a domain-level connection fault may not hit them.
- The DNS query results in the connection details parse A records only (IPv4); AAAA (IPv6) is not parsed, so a domain resolved solely via AAAA shows its name with no result IPs.
- The capture-scope domain/address input accepts a raw IPv4, but a TLS connection carrying an SNI is still matched by its SNI domain; a raw IP only matches connections with no observable domain (QUIC, no-SNI TCP, direct IP) by destination IP, and IPv6 entry is not accepted.
- When a DNS cache already exists the app may not issue a query, and a DNS failure cannot change the cached result; the "block connections after caching" switch is needed.
- DNS timeouts are governed by the resolver's retry policy and can be noticeably slower than the TCP silent timeout.

## QUIC and UDP

- QUIC has no TCP RST; dropping UDP 443 does not guarantee the app falls back to TCP.
- The per-connection traffic log (FlowLog) records TCP and UDP/QUIC/DNS, but not ICMP.

## Application error codes

- Application-side error codes are determined jointly by the Android, Chromium, and parser versions, and cannot be strictly guaranteed across versions. The documentation and UI describe only the protocol semantics and do not promise a fixed Chromium number.
- In the immediate mode of a network blackout, when a domain is connected for the first time and the target IP has not yet been learned, the hit cannot be predicted, so it can only degrade to RST (`-101`) rather than refuse (`-102`).

## System layer

- A network-blackout simulation targets faults on the target traffic; it does not trigger Android's real network-disconnect broadcast.
- Modern Android (targetSdk 34 and 36) restricts starting a foreground service from the background. When automation cold-starts while the app is not open, the app must be brought to the foreground once via `am start` during the preparation phase; see [Automation Control Interface](../automation-control_EN.md).

## Runtime statistics

- Runtime statistics (`RuntimeStats`) provide metrics such as rate, active connection count, cumulative drops and holds, and uptime, but do not provide stable derived values like "current loss rate" or "current RTT." There is therefore no condition state such as "high latency," "packet loss," or "disconnected" derived from real-time statistics; the mascot has only the five lifecycle states, see [State Mapping](../03-product/state-mapping_EN.md).

## Measurement methodology

The weak-network parameters Pakomo sets do not necessarily map one-to-one to the numbers shown by third-party speed tests, for reasons including the difference between per-direction and combined measurement, the difference in how timeouts and late packets versus truly dropped packets are classified, and the effect of application-layer retries. See [Measurement Methodology](../06-testing/measurement-methodology_EN.md).
