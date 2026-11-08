# MeshHome project status

## Goal

An independent Android client for selected-app access to self-hosted services over Nord Meshnet. MeshHome is not an official Nord Security app.

## Implemented

- Android `VpnService`, explicit connect/disconnect actions, foreground notification, and a selected-app allowlist that fails closed when empty or invalid.
- Wi-Fi-only and all-network policies, physical-network selection/socket binding, and optional home-Wi-Fi bypass.
- Nord token-based account access, distinct local X25519 device identity, encrypted local secrets/map cache, and device discovery/refresh.
- Exact and wildcard domain-to-device rules with deterministic precedence and local A/AAAA DNS answers.
- Kotlin DNS/policy core, Rust JNI bridge to pinned libtelio, native Android socketpair adapter patch, and reproducible native build helper.
- Client-only TCP/UDP connection filter, blocking packet pump, callback-driven network monitoring, retry backoff and reduced polling.

## Deliberate scope and limitations

- Native transport is relay-only; direct peer discovery is disabled because its internal socket routing does not fit the current Android VPN design.
- This is selective peer access, not an internet exit VPN or general LAN gateway. File sharing and services hosted on the phone are out of scope.
- The replacement client firewall is deliberately narrower than upstream's separate firewall component. It blocks unsolicited inbound traffic and supports only a limited set of outbound TCP/UDP flows. ICMP, fragments, IPv6 extension headers and broad routing are unsupported. Review `docs/0.3.2.md` before testing.
- Browser/app DNS-over-HTTPS can bypass local rules. Unmatched DNS is sent in plaintext to the chosen network resolver or configured upstream.
- SSID matching is a convenience signal, not authentication. Android permissions, location settings, Doze and OEM behavior affect background identification/lifecycle.
- Battery consumption has not been measured. A peer status is not proof that DNS, a service port, TLS, or an application is reachable.

## Validation status

The implementation has been exercised on one Android 13 phone with a live Meshnet account. The VPN-owner default-network loop was fixed; basic peer connectivity and local DNS replies were observed. A service connection later recovered after restarting the remote daemon and app, so autonomous recovery is not established. Disconnect was verified on-device. The latest client firewall changes and repeated-restart/network-transition cases still need broader device testing. No battery-life measurements or independent security audit have been completed.

Run JVM tests, Android lint and APK assembly using the commands in `README.md`. The packet pump has Android instrumentation tests; run them on a connected test device. See `docs/PHONE_TESTING.md` and `docs/BATTERY.md` for acceptance and measurement plans.

## Public project/release gates

- Review this repository for personal domains, hostnames, device identifiers, LAN details, credentials and local paths before publishing. Use `example.org` and documentation-only IP ranges in examples.
- Review root licensing and GPL-3.0-only libtelio corresponding-source obligations. `THIRD_PARTY_NOTICES.md` is an inventory, not a complete distribution license bundle.
- Complete security review of JNI/socket protection, native configuration, the client-only firewall, DNS and service shutdown behavior.
- Test multiple Android versions and devices, Doze, captive portals, Wi-Fi/cellular changes, server daemon restarts, DNS-over-TCP, and actual services.
- Measure battery use against a disconnected baseline before making efficiency claims.
- Review privacy, permission disclosures, app identity/signing and any app-store policy requirements before public release.
