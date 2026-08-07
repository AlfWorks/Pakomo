# Kernel Backend (pure-Kotlin tun2socks)

English | [简体中文](kernel-backend.md)

The Kernel edition uses pure Kotlin to implement the subset of user-space tunneling and forwarding that Pakomo's current work requires — enough to carry Pakomo's existing features, but not a full Kotlin rewrite of `hev-socks5-tunnel`. If Pakomo adds low-level networking capability in the future, the Kernel needs to be extended in step. The two are different implementation paths, not a full-vs-lite relationship.

## Coverage

The Kernel (`com.alphynia.pakomo.kernel`) implements only the subset Pakomo's data path actually uses:

- IPv4. Pakomo only adds an IPv4 route for `0.0.0.0/0`; there is no IPv6 dual stack.
- TCP to SOCKS5, bridging to the local `Socks5Server` through a user-space TCP state machine.
- UDP to SOCKS5 UDP ASSOCIATE, for DNS, QUIC, etc.
- Local replies for ICMP echo.
- Connection reclamation and idle timeout.

The Kernel does not implement IPv6, non-Android platform backends, general SOCKS5 features, or all the edge-case protocol handling of the native stack.

## Components

| File | Responsibility |
|---|---|
| `Tun2SocksEngine.kt` | Orchestrator; takes over the TUN fd, starts the read/write and the various flows, and reclaims idle connections |
| `Tun2SocksConfig.kt` | Tunnel configuration, including the SOCKS5 port and credentials |
| `KernelDispatchers.kt` | Coroutine dispatchers |
| `tun/TunReader.kt`, `tun/TunWriter.kt` | Reading and writing the TUN fd, with serialized writes |
| `ip/Ipv4Packet.kt`, `ip/Checksum.kt` | IPv4 parsing and checksums |
| `tcp/TcpConnection.kt`, `tcp/TcpSegment.kt` | TCP state machine and segment handling |
| `udp/UdpSession.kt` | UDP sessions, bridging to SOCKS5 UDP ASSOCIATE |
| `icmp/IcmpResponder.kt` | Local replies for ICMP echo |
| `socks/Socks5Client.kt` | Connects to the local `Socks5Server` as a SOCKS5 client, including the attribution preamble, behaving consistently with the HEV patch |

## Key design

- The TUN side is a lossless in-memory link. There is no real packet loss or reordering among the app, kernel, and user-space stack; weak-network and faults are injected by the downstream `Socks5Server`. The hard part of the TCP stack is therefore backpressure — making the app pause naturally by shrinking the advertised window — rather than resisting loss.
- All packets written back to the TUN are serialized through a single writer to preserve ordering.
- `Socks5Client` passes through the original connection five-tuple so that Kotlin-side `getConnectionOwnerUid()` can attribute by app and domain; this behavior matches HEV's attribution-preamble patch.

## Advantages

The Kernel edition has no third-party native dependency and needs no NDK to build, so the APK is smaller and builds faster; it is all Kotlin and unit-testable, with test coverage for IPv4, TCP, UDP, and ICMP parsing and checksums.

## History

The Kernel edition passed on-device acceptance in August 2026; during the replacement of hev, a checksum defect and a DNS-port bug were fixed and throughput was optimized.
