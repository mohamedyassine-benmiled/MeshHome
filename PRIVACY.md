# Development privacy notes

MeshHome is an independent development client, not an official Nord app.

- The token is sent over HTTPS to `api.nordvpn.com` for authentication and Meshnet operations. Nord receives the registration/public key, device OS metadata, and the network information necessary to provide its service. Nord relays carry encrypted Meshnet traffic; using Meshnet does not make activity anonymous to all parties.
- The token, locally generated private key, registration identity, and cached peer map are stored encrypted with an Android Keystore-backed AES-GCM key. Cloud backup and device transfer are excluded. Keys/tokens are not intentionally logged or placed in source/build settings.
- Selected app package names, domain mappings, home Wi-Fi names, and network preferences stay in app-private settings. The app does not upload the app allowlist or home Wi-Fi names to Nord.
- Optional background location permission is used only to read the current Wi-Fi name for configured home-network bypass. The app does not request GPS coordinates. Android location services may still need to be enabled for SSID visibility. Denying access leaves bypass disabled when the name is unavailable.
- Matching DNS rules are answered on the phone. Non-matching queries are forwarded as **plaintext DNS** on the selected physical network to its resolver or the user-configured upstream. That resolver/network can observe those queries. The app rejects strict Android Private DNS while connecting rather than promising it works with overrides.
- No app analytics or libtelio telemetry/nurse feature is enabled in the native configuration. This is not a substitute for reviewing Nord's own service privacy terms or auditing the complete native dependency graph.
- Local logout removes local credentials and closes the VPN, but does not remove a remote Nord device unless the user chooses the explicit deregistration option. Uninstalling also does not automatically deregister the phone remotely.

These are implementation notes, not a claim of independent security certification. Do not distribute as a production privacy/security product before completing the phone tests and release review.
