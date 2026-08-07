# Project Overview

English | [简体中文](project-overview.md)

Pakomo is a non-root weak-network and fault-injection tool built on Android's `VpnService`. Through a local TUN, Pakomo takes over the traffic of selected apps or domains and injects controlled network degradation and special faults entirely on the device, to validate how clients behave under weak-network, fault, and late-response conditions.

All traffic handling happens on the device itself: it does not go through any external proxy, and business content is never decrypted. The full definition of the relevant boundaries is in [Principles & Boundaries](principles-and-boundaries.md).

## Capability scope

The authoritative list of capabilities is the [Capability Matrix](../01-capabilities/capability-matrix_EN.md). The main capabilities are, in summary:

- Connection-layer weak-network shaping — latency, jitter, packet loss, and up/down rate limiting — with a simple mode and an advanced mode that sets each direction independently.
- Four special faults: connection reset, DNS failure, network outage, and slow response (late response). Fault configuration is saved with the rule and multiple faults can be enabled at once.
- Per-app and per-domain attribution, per-connection traffic logging, and real-time diagnostics.
- Two implementation paths, Kernel and Hev. They are functionally identical and can coexist on the same device.
- An automation control protocol, driven by adb broadcasts, that lets Pakomo be used as a programmable weak-network and fault injector.

## Explicitly out of scope

Pakomo does not perform TLS man-in-the-middle (MITM) decryption and does not modify packet content. Application-layer content faults — for example HTTP 404/500, malformed JSON, empty responses, and truncated responses — are therefore explicitly out of scope (`Out-of-Scope`). See [Limitations](../01-capabilities/limitations_EN.md) for the full explanation.

## Typical scenarios

- Verify whether the original request is actually cancelled after a request times out, and whether a late response to the original request arrives during retries.
- Observe how a client behaves under high latency, jitter, packet loss, and rate limiting.
- Reproduce connection resets, DNS failures, network outages, and late responses.
- Inspect, per connection, the actual traffic passing through the device to help locate problems.

## Documentation map

- What it can do: see [Capabilities](../01-capabilities/capability-matrix_EN.md).
- How it is implemented: see [Architecture](../02-architecture/common-architecture_EN.md).
- How it is presented: see [Product](../03-product/ui-specification_EN.md).
- The boundaries it must not cross: see [Principles & Boundaries](principles-and-boundaries_EN.md) and [Security](../05-security/privacy-boundary_EN.md).
- How it is verified: see [Testing](../06-testing/acceptance-criteria_EN.md).
- The automation interface: see [Automation Control Interface](../automation-control_EN.md).
