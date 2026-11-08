# Battery behavior and validation — 0.3.0

## Implemented changes

| Previous development build | Current implementation |
|---|---|
| Policy evaluation every second | Default-network callbacks, settings/account listeners, and relevant system/package broadcasts; event bursts coalesced over 150 ms |
| Two packet readers each polling every 500 ms | One blocking `Os.poll(..., -1)` for TUN/native packets, with a shutdown pipe for immediate cancellation |
| Native status queried every second | JNI notifications for changed peer/relay states and fatal/protection errors; duplicate states suppressed |
| Activity status timer every second | In-memory status observers registered only while the activity is resumed |
| Peer-map HTTP request every minute | Every 5 minutes while interactive; 15 minutes screen-off or Battery Saver; deferred during Doze |
| Decrypt/parse the cached peer map on every policy tick | In-memory map invalidated on credential/map changes; unchanged maps are not rewritten to the encrypted vault |
| Full tunnel restart when raw map text changed | Stable routing/DNS fingerprint; other map changes applied to libtelio in-place |
| Sessions/keepalives for all account peers | Native map contains only devices targeted by enabled rules; full account map retained for device selection |
| Short default transport keepalives | Pinned upstream `FeaturesDefaultsBuilder.enable_battery_saving_defaults()` preset |
| Frequent error retries | Jittered exponential backoff: connection retries start around 5 seconds, capped at 5 minutes; cached-map refresh failures start around 30 seconds, capped at 15 minutes |
| Global DNS/TCP tick | Only outstanding TCP retransmission/flow-expiry deadlines are scheduled; no DNS timer when no flow exists, and retransmissions back off |

The upstream preset configures proxying/STUN keepalives at 125 seconds, DERP TCP and server keepalives at 125 seconds, and peer-state polling support. VPN/direct settings in the preset are not currently used by this relay-only client. We preserve the existing TLS, firewall, relay-only, and telemetry-disabled choices.

Upstream's separate battery guide also discusses 61-second UDP keepalives and 60-second DERP intervals as NAT/handshake tuning suggestions. They are not identical to its builder preset. This relay-only build uses the pinned builder preset as its starting point rather than applying direct-UDP/NAT tuning blindly. Receiving the server's own frequent keepalives can still wake the phone, so client-side tuning alone cannot determine total consumption. Test real networks/remote peers before changing these intervals further.

For a stable active session this changes scheduled map traffic from about 60 requests/hour to 12/hour while interactive or 4/hour screen-off. Startup, manual refresh, state changes and retries can add requests. **This is a request-count comparison, not a battery-life percentage claim.**

## Behavior that is intentionally preserved

- Locking the screen does **not** disconnect a healthy Meshnet session. Selected apps can continue using it subject to Android's network/power restrictions.
- Wi-Fi-only enforcement still uses explicit network binding on transport and upstream DNS sockets. A lost/replaced default network halts the packet pump promptly; work finishing against an old snapshot cannot create a replacement tunnel on it.
- Home bypass, waiting for Wi-Fi, missing settings, and captive-portal pauses have no periodic application policy/map/retry timer. Relevant OS/configuration events resume evaluation.
- Doze does not deliberately tear down a healthy unchanged session. Nonessential map traffic is deferred, failed/new sessions wait for Doze exit, and network/configuration restrictions still take precedence.
- Expired/revoked credentials still stop the service upon API rejection. Longer refresh intervals can delay discovering server-side changes or revocation. Manual device refresh remains available.
- No new wake locks, Wi-Fi locks, exact/wakeup alarms, Wi-Fi scans, battery-optimization exemptions, or external push/analytics service were added. WorkManager is not used to run the interactive VPN: it is meant for deferrable jobs and does not replace the foreground tunnel service.

## What remains unavoidable or unmeasured

libtelio/NepTUN still has its own protocol timers and WireGuard state polling (the pinned native default includes a roughly one-second state-poll interval). The changes remove **our application's** periodic policy/status/packet polling, not every native timer. Relay/TLS/WireGuard keepalives, encryption, active downloads, DNS queries and weak radio signals still consume power. Longer keepalives need validation on real NATs and mobile networks.

A foreground service is not a guarantee of Doze exemption or immunity from OEM process killing. This build does not fight Doze with wakeup alarms or automatically request unrestricted battery access. Its non-wakeup executor tasks can be delayed while the CPU sleeps. Wake/network callbacks catch up when Android permits execution.

**No physical-device battery measurements have been taken.** A compiled build and unit tests do not establish “negligible drain,” overnight reliability, or a specific percentage improvement.

## Validation performed / available

- Native arm64 and both Android APKs build; 46 JVM tests pass, Android lint has zero warnings/errors, and the app APK passes signing/16 KB ZIP alignment checks. The new JNI API exports and packaged native-library identity were checked. These are build/static checks, not measured battery results.
- JVM tests cover refresh cadence, independent jittered backoff/caps/reset, routing fingerprints (including swapped device addresses), status subscription/deduplication, and DNS timer deadlines/backoff/expiry.
- `PacketPumpInstrumentedTest` provides three real-Android FD tests: shutdown releases a blocking reader, packets preserve boundaries in both directions, and a matching DNS query never calls an upstream. They require no Nord account and do not modify saved credentials/settings. They have been compiled, **not run on a phone**.
- Run on a dedicated arm64 test phone, with other VPNs stopped:

```powershell
.\gradlew.bat -PrequireNative :app:connectedDebugAndroidTest
```

## Phone power-measurement plan

1. Compare `0.2.0` (baseline) and `0.3.0` on the **same device** with the same rules, apps, configured peers, brightness, Wi-Fi AP/signal, battery charge range and temperature. Keep other apps/background sync comparable. Warm up the connection before each measurement.
2. Measure at least: disconnected baseline; home bypass; Wi-Fi-only waiting on cellular; connected idle with screen off; connected idle screen on; active streaming/download; offline/rejected relay/API; and repeated Wi-Fi ↔ cellular transitions. Include both one target and several targets.
3. Take a before/after snapshot with the read-only helper, unplug the USB cable during the idle interval (for example 60 minutes), and reconnect afterward:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-power.ps1 -Tag before-idle
# Unplug, lock the phone and leave the controlled workload running.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\capture-power.ps1 -Tag after-idle
```

The helper does not reset battery history, force Doze, change Wi-Fi, or stop the app. Reports stay under ignored `dist/power/`; retain them locally because diagnostics can contain device metadata. Compare CPU-time, network and wake-lock deltas, not just the displayed percentage of battery attributed to the VPN. Android can attribute traffic across VPN and originating app UIDs.

4. Prefer Android Studio **System Trace / Power Profiler** or Perfetto for CPU wakeups, thread activity and radio correlation. Power rails are device-wide and noisy, not a clean per-app battery meter. ODPM support depends on hardware (notably supported Pixel devices); use gauge/charge-counter data on others. Avoid wireless ADB during an idle radio test because the debugger itself can keep the radio busy.
5. On a dedicated test device, explicitly test Doze. These commands intentionally change device idle state; always undo them:

```powershell
adb shell dumpsys deviceidle force-idle
# Observe: no one-second controller loop, no map-refresh loop, and no reconnect storm.
adb shell dumpsys deviceidle unforce
adb shell dumpsys battery reset
```

Here `dumpsys battery reset` restores any simulated battery state; it is **not** `dumpsys batterystats --reset`, which erases collected history. Do not reset Batterystats unless deliberately starting a new experiment.

6. Acceptance: no recurring app controller/status/DNS-idle wakeups; no application timer while paused; no cellular Meshnet traffic in Wi-Fi-only mode; stable idle peer connectivity on supported networks; prompt policy response; bounded retries; and graceful recovery after waking/Doze. Record the actual device/OS and measured results before making battery-life claims.

## Sources reviewed

- Android, callback-based network monitoring and `onAvailable` property-lookup race guidance: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- Android, radio tail energy and batching/reusing network activity (illustrative 3G timings are not assumed to be 5G timings): https://developer.android.com/develop/connectivity/network-ops/network-access-optimization
- Android, Doze/App Standby restrictions and test commands: https://developer.android.com/training/monitoring-device-state/doze-standby
- Android Studio Power Profiler, supported hardware and device-wide measurement caveats: https://developer.android.com/studio/profile/power-profiler
- Android, Batterystats; Battery Historian is no longer actively maintained, so prefer current tracing/power tools: https://developer.android.com/topic/performance/power/setup-battery-historian
- Pinned libtelio battery preset: https://github.com/NordSecurity/libtelio/blob/c15a3bd60340e9cc0da74d93fe50b3b875e9e052/crates/telio-core/src/defaults_builder.rs#L54-L74
- Pinned libtelio battery guide, including remote-peer effects and alternative NAT/keepalive suggestions: https://github.com/NordSecurity/libtelio/blob/c15a3bd60340e9cc0da74d93fe50b3b875e9e052/docs/battery.md
