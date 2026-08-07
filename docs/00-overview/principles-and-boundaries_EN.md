# Principles & Boundaries

English | [简体中文](principles-and-boundaries.md)

This document defines the boundaries Pakomo must not cross, and the essential difference between Pakomo and the class of tools that claim to "simulate any network fault." For any entry marked `Out-of-Scope` in the [Capability Matrix](../01-capabilities/capability-matrix_EN.md), the ultimate basis is this document.

## 1. Core value

Pakomo's core value is not simulating arbitrary network faults, but providing a trustworthy local weak-network and fault injector within explicit boundaries. Its principles are:

- **Auditable source**: all logic lives in this repository; there is no closed-source black box.
- **Clear permission boundaries**: every permission used and its purpose can be explained item by item; see Section 3.
- **No remote gateway**: traffic handling happens entirely on the device, never through an external proxy or server.
- **No uploading of business traffic**: the traffic under test is never sent off the device.
- **No decryption of business content**: no TLS man-in-the-middle decryption, and no certificates are held.
- **No reading or storing of payloads**: logs and statistics do not record DNS message bodies, HTTP content, cookies, credentials, or other application data.
- **No modification or injection of application-layer content by default**: the response body is not rewritten, and successful replies are not forged.

These boundaries are part of the product's positioning. They take precedence over any feature request and will not be relaxed to satisfy presentation needs.

## 2. Connection layer vs. application layer

Pakomo works at the connection layer, and its capabilities split into two classes accordingly. The two have different prerequisites and cannot both be described as "supported."

### 2.1 Connection-layer capabilities

The following can be achieved without decrypting traffic, and are Pakomo's default capabilities:

- latency, jitter, packet loss, and rate limiting;
- connection interruption, i.e. the silent and immediate modes of a network outage;
- connection reset (TCP RST);
- DNS failure, applied to plaintext UDP 53 queries;
- holding a response and releasing it late, i.e. slow response (late response).

### 2.2 Application-layer capabilities

For example HTTP 404/500/503, malformed JSON, empty responses, truncated responses, and rewriting the response body.

For HTTPS and QUIC traffic, without man-in-the-middle decryption, without holding certificates, and without reading payloads, Pakomo cannot generically identify and rewrite HTTP responses. Such capabilities therefore cannot be described as "supported"; they hold only locally when all of the following are met:

- they apply to plaintext HTTP only;
- they require cooperation from the target app;
- they require a test build to separately provide proxy or certificate capability;
- or they are offered as a standalone, experimental extension rather than a default Pakomo capability.

Accordingly, application-layer content faults are uniformly marked `Out-of-Scope` in the capability matrix. Even if such capabilities are implemented in the future, they must be offered in a standalone, optional form with explicit prerequisites, and must not change the boundary of "no decryption and no modification of packet content by default."

## 3. Permission boundaries

| Permission | Purpose | Boundary |
|---|---|---|
| `VpnService` (BIND_VPN_SERVICE) | Establish a local TUN to take over selected traffic | Used only for on-device forwarding; no tunnel to any external endpoint |
| `QUERY_ALL_PACKAGES` | Select installed apps within the takeover scope | Used only to list apps; no other enumeration or collection |
| `SYSTEM_ALERT_WINDOW` | Floating-ball quick toggle | See the UI specification |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*` | Foreground-service notification | Satisfies Android's foreground-service requirements |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Forward outbound connections and read the underlying network | Outbound sockets are first passed through `VpnService.protect()` and do not loop back into the TUN |

## 4. Data-plane boundaries

- **Local SOCKS5**: the relay listens only on a random local port with randomly generated credentials on each start, and is not exposed externally.
- **Bypass**: traffic that does not match a rule is bypassed as-is, without shaping or fault injection.
- **Granularity**: because TLS is not decrypted, the minimum granularity of shaping and faults is the connection; a single request within one HTTP/2 connection cannot be delayed in isolation.
- **No change to system networking**: a network-outage simulation targets faults on the target traffic; it does not cause Android to broadcast a real network disconnection.

## 5. The UI obeys the boundaries

The presentation is bound by the same boundaries. Buttons and copy describe what the engine actually does, not behavior the engine does not have. For example, Pakomo does not provide a "restore network automatically" capability; the control that stops takeover performs "stop simulation" or "stop VPN," and its copy states this faithfully. For the concrete presentation, see the [UI Specification](../03-product/ui-specification_EN.md).
