# Hev Backend (native hev-socks5-tunnel)

English | [简体中文](hev-backend.md)

The Hev edition uses the native `hev-socks5-tunnel` as its forwarding core, retaining a more complete and mature low-level capability range. The Hev and Kernel editions are different implementation paths; their relay, shaping, and fault logic is completely identical and lives in the shared `forwarding/`.

## Composition

- **Forwarding core**: `third_party/hev-socks5-tunnel`, brought in as a git submodule, MIT-licensed and built from source, together with its upstream submodules `hev-task-system`, `yaml`, `lwip`, and `hev-socks5-core`.
- **JNI bridge**: `hev.htproxy.TProxyService`, providing `TProxyStartService`, `TProxyStopService`, and `TProxyGetStats`.
- **Config**: `vpn/HevTunnelConfig` generates hev's YAML configuration, including the TUN address and MTU, the local SOCKS5 port and credentials, timeouts, etc.
- **Attribution-preamble patch**: `patches/hev-attribution-preamble.patch` makes the native core pass through the original connection five-tuple so the Kotlin side can attribute by app and domain. The native change is maintained as a patch and is not committed into the submodule.

## Building

- Only the Hev flavor compiles the native library: the build script triggers `ndkBuild` when "the task name contains `hev`," compiling `libhev-socks5-tunnel.so`.
- NDK 28.2 is required. Before the first build, run `git submodule update --init --recursive` and apply the attribution patch; on Windows, also run `scripts/prepare-third-party.ps1` to restore the symbolic links. See "Preparing the hev forwarding core" in the [README](../../README.md).
- The Hev edition must be invoked with separate Gradle tasks from the Kernel edition, otherwise the native code is compiled into the Kernel edition too.

## Relationship to the Kernel edition

For Pakomo's existing business features, the Hev edition has no visible difference from the Kernel edition; see the difference notes in the [Capability Matrix](../01-capabilities/capability-matrix_EN.md). The two flavors are validated for business consistency by the same automation smoke test and a dual-flavor diff (`scripts/automation-compare.sh`). Tunnel-layer implementation details — such as the maturity of the native stack and edge-case protocol handling — are not required to match Kernel one for one.
