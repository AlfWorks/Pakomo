# Privacy Boundary

English | [简体中文](privacy-boundary.md)

This document is the concrete statement of [Principles & Boundaries](../00-overview/principles-and-boundaries_EN.md) with respect to privacy and data handling.

## Data never leaves the device

- Traffic handling happens entirely on the device, never through an external proxy or server, and business traffic is never uploaded.
- The local SOCKS5 relay listens only on a random local port with randomly generated credentials on each start, and is not exposed externally.
- Outbound sockets are first passed through `VpnService.protect()` and do not loop back into the TUN.

## No decryption, no modification of content

- Pakomo does not perform TLS man-in-the-middle decryption, holds no certificates, and does not decrypt payloads.
- By default it does not modify or inject application-layer content, does not rewrite the response body, and does not forge successful replies.
- Application-layer content faults are therefore out of scope; see [Limitations](../01-capabilities/limitations_EN.md).

## No reading or storing of payloads

Logs and statistics do not record DNS message bodies, HTTP content, cookies, credentials, or other application data.

- The per-connection traffic log records only connection metadata — protocol, host, port, up/down byte counts, whether held or shaped, and status — not content.
- Fault-hit logs record only the action name and attribution, not message bodies; duplicate packets on the same connection are de-duplicated, and reconnect storms are summarized and rate-limited per second.
- The Logcat on the diagnostics page is the app's own log output and contains no payload under test.

## Permission purposes

The purpose of each permission is listed item by item in Section 3 of [Principles & Boundaries](../00-overview/principles-and-boundaries_EN.md). Among them, `QUERY_ALL_PACKAGES` is used only to select installed apps within the takeover scope, and `VpnService` is used only for on-device forwarding, not to establish a tunnel to any external endpoint.

## Authorized use

Pakomo is a network-testing tool, to be used for testing, research, and study only on networks and apps you are authorized to test, and must not be used for unauthorized traffic interception or interference.
