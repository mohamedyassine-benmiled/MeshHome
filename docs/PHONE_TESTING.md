# Phone acceptance checklist

Use a development phone first. Native-enabled builds target arm64-v8a and require Android 10+. Limited testing has been performed on one Android 13 phone/account; it is not broad compatibility or production verification.

Follow [BATTERY.md](BATTERY.md) for screen-off/Doze tests, packet-pump instrumentation tests, and before/after power measurements. A healthy session should not disconnect merely because the screen turns off.

## Initial setup

1. Install `app/build/outputs/apk/debug/app-debug.apk` (or the copy under `dist/`). Disable other VPN apps in the same profile, especially their always-on/lockdown settings.
2. Use a generated Nord Account token **only in the phone UI**, never in chat or build files. Sign in, verify that this phone appears as a new Meshnet device, and refresh the peer list.
3. Make sure your server permits incoming connections from this new device. Its HTTPS reverse proxy must listen on an address reachable through Meshnet, not only localhost, and accept the original hostnames with valid certificates.
4. Select a browser or service app. Turn off that app's custom secure-DNS/DoH feature for this test. Set Android Private DNS to off or automatic; strict hostname mode is rejected by MeshHome.
5. Add `*.example.org` → your server. Add `example.org` separately if needed. `example.org` is a documentation example; use a domain you control for real services. Add a second exact domain to another device to exercise multi-device routing.
6. For away tests, use non-home Wi-Fi initially. Connect, approve Android VPN consent and notification permission, and check the target-peer count. This build uses relay transport only.

**Do not use `adb shell ping`, `nslookup`, or this client's own process as proof of selected-app DNS behavior.** They are not in the app allowlist and therefore do not take the same DNS/routing path. Test from the selected browser/app, with its DNS cache cleared or after restarting it.

## Functional checks

- Matching subdomains reach the intended server through its Meshnet IP, retaining the original HTTPS hostname/certificate.
- Exact-domain rules do not match subdomains. Wildcards match descendants but not the apex. Exact and longer wildcard rules take precedence.
- Multiple domains/device targets work simultaneously; disabled rules do not apply; duplicates are rejected.
- Non-matching internet domains resolve via the configured network/upstream DNS, without querying remote Pi-hole by default. This forwarding is plaintext DNS.
- An IPv4-only target returns no AAAA address. Missing/deleted target IDs fail locally, not with public/LAN fallback answers.
- Ordinary UDP DNS, large truncated replies with TCP retry, repeated TCP queries, and malformed DNS do not crash or stall the service.
- The server's inaccessible ports/services remain inaccessible; a connected peer count does not prove a service is listening.

## Policy and lifecycle checks

- Add home Wi-Fi while connected to it. Grant precise location, enable location services, and optionally allow background location for SSID identification with the app closed. Confirm home bypass both on-screen and after locking the phone.
- On home Wi-Fi, MeshHome's VPN disappears and the configured home resolver supplies the expected LAN answers through normal Android DNS. Allow for DNS expiry/app restart. No automatic cache flush is promised.
- If SSID access is denied, location services disabled, or the name redacted, confirm bypass is **not** applied and the status explains that the name is unavailable.
- In Wi-Fi-only mode, leave Wi-Fi with cellular enabled. Confirm the VPN pauses and no Meshnet transport continues on cellular. Test using controlled packet capture/data counters, not only the UI label.
- In all-networks mode, switch Wi-Fi ↔ cellular and confirm a new bound session and restored peer connectivity.
- Test captive portal, Wi-Fi with no internet, cellular fallback, an unrelated VPN becoming active, and returning from home bypass to away Wi-Fi.
- Remove all selected apps, uninstall a selected app, disable all rules, or remove a target device. The client must never create an all-app tunnel.
- Disconnect from the app and notification, revoke VPN consent, kill the process, and reboot. No false persisted "connected" label or boot auto-connect should appear.
- Expire/revoke the token and refresh. Authentication rejection must stop the session. Renew using a token with access to the same registration, or explicitly log out to change accounts.
- Test local logout (remote device entry remains) versus explicit device removal + logout. A failed remote removal must not silently erase the credentials needed to retry.

## Before public release

Complete these tests on more than one Android version, including a 16 KB page-size device/emulator. Exercise socket protection/binding failures and validate failed-socket quarantine. Review the bounded local TCP implementation under loss/reordering, assemble the complete GPL corresponding-source/license bundle, review all dependencies, and assess Google Play background-location/special-use service policies. See [NATIVE.md](NATIVE.md).
