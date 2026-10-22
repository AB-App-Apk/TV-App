# TV Dash / TV Remote — Project State

**Last updated:** 2026-10-22  
**Repository:** [AB-App-Apk/TV-App](https://github.com/AB-App-Apk/TV-App)  
**Verified baseline:** Main Build APK #186 succeeded after Task 7 was merged. Both apps were subsequently confirmed updated successfully.

## 1. General Overview & Functionality

TV Dash and TV Remote form a two-app system for controlling an Android TV from a phone. TV Dash runs on the TV, provides a dashboard and launcher, and exposes a PIN-authenticated command service. TV Remote presents the phone UI, tracks connection state, and routes commands over the available transport.

### TV Dash — Android TV app

- **Application ID:** `com.example.tvdash`; **module:** `app/`.
- Kotlin, Jetpack Compose, and Android TV Material components.
- D-pad-friendly dashboard with time/date, network status, available storage, and installed-app grid.
- Launches installed apps and can be selected as the default Home/launcher screen.
- TV-side command service for navigation, volume, power, text entry, and app-related requests.
- Accessibility-service status detection and recovery guidance for actions that depend on it.
- Displays TV network information and the pairing PIN.

### TV Remote — Android phone app

- **Application ID:** `com.example.tvremote`; **module:** `remote/`.
- On-screen remote controls, text entry, YouTube search integration, TV app list, Bluetooth pairing/setup, connection diagnostics, and update checking.
- Connection manager selects a transport and exposes a combined connection state so the UI does not need to implement network protocols.
- Supports TV Dash data requests and Bluetooth HID keyboard/media input.

### Communication and handover

| Channel | Purpose |
|---|---|
| Wi-Fi HTTP | PIN-authenticated requests to TV Dash on TCP port `8765`. PIN is sent in the `X-TV-PIN` header. |
| Classic Bluetooth RFCOMM | TV Dash data-channel fallback when Wi-Fi cannot reach the TV; uses the same header-based PIN protocol. |
| Bluetooth HID | Keyboard/media/control input that can work without TV Dash's data service. |

**Important distinction:** the data fallback is classic Bluetooth **RFCOMM**; keyboard/media input uses **Bluetooth HID**. The current implementation does not provide a BLE data transport.

The phone connection manager tracks fully connected, Wi-Fi/data-only, Bluetooth-HID-only, and disconnected states and chooses a route per command. Wi-Fi retries use backoff; the RFCOMM client maintains a reusable connection with bounded timeouts/retries. The app can queue commands briefly during connection loss and retry links.

### Security boundary

- PIN query-string authentication has been removed from supported request paths; clients send `X-TV-PIN`.
- TV-side PIN attempt throttling and cooldowns reject repeated incorrect attempts.
- **Wi-Fi HTTP is still unencrypted.** Header authentication avoids putting the PIN in URLs and common URL logs, but does not hide the PIN or commands from someone capturing LAN traffic.
- The old signing keystore was publicly committed and must be treated as compromised. The replacement key is supplied through GitHub Actions secrets; generated key files are removed from the runner. Removing the old file from the current tree does not erase public Git history.

## 2. Implemented Tasks

### TV Dash and TV-side components

- Dashboard cards for time/date, network status, and storage, with links to relevant system settings.
- Installed-app grid with available app banners/icons, TV focus handling, D-pad navigation, and app launch.
- Default Home/launcher support.
- TV-side HTTP service on port `8765`, running as a foreground service, with health/recovery behaviour.
- Bluetooth RFCOMM server for remote data requests.
- Command handlers for navigation, volume, power, text entry, app operations, and supported YouTube actions.
- Accessibility-service status detection, recovery UI, and re-enable support when device permissions allow it.
- Bounded request/header parsing and bounded request processing.

### TV Remote and connection components

- On-screen controls and keyboard/text-entry support.
- Bluetooth pairing/setup flow.
- Bluetooth HID keyboard/media transport with key timing and supported D-pad, enter, back/home, volume, media, and wake actions.
- Bluetooth RFCOMM client with warm-link handling, heartbeat, serialized requests, timeouts, retry behaviour, and cleanup.
- Wi-Fi HTTP client and shared routing through `TvLink`.
- Connection manager and per-command transport selection.
- Brief FIFO command queue and link retry behaviour during connection loss.
- Connection diagnostics with radio/permission/configuration state, safe ping, response/route information, and retry-link actions.
- Shared update controller used by both apps to check the GitHub `latest` release and invoke Android's package installer.

### PIN authentication and abuse resistance

- Shared `X-TV-PIN` protocol constant and request helpers in `shared/TvProtocol.kt`.
- Header-based PIN on Wi-Fi and RFCOMM; current clients do not append the PIN to URLs.
- Case-insensitive header parsing, whitespace trimming, header terminator handling, and a 64-header limit.
- Per-client PIN failure windows and cooldowns, escalating cooldowns for repeated lockouts, and bounded client tracking.
- Missing/incorrect PINs are denied; correct PINs do not bypass an active cooldown.

### Signing, CI, and release workflow

- GitHub Actions signing secrets: `ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
- Hardcoded signing passwords removed from Gradle build files.
- Workflow validates the replacement key/alias, limits permissions on the temporary key file, and removes the generated keystore after the build.
- CI runs unit tests for both modules, assembles both APKs, uploads artifacts, and publishes the stable `latest` release only from `main`/ `master`.
- The shared updater reads `version.txt` and downloads the corresponding APK from the GitHub `latest` release.
- Signing migration details are in [docs/APK_SIGNING_MIGRATION.md](docs/APK_SIGNING_MIGRATION.md). The replacement-key installation/update path has been verified.

### Tests and device acceptance

Current automated test files:

- `app/src/test/java/com/example/tvdash/PinAttemptLimiterTest.kt`: failure windows, cooldowns, correct PIN during/after cooldown, per-client isolation, cooldown escalation, and bounded client tracking.
- `app/src/test/java/com/example/tvdash/TvProtocolTest.kt`: Wi-Fi URL without PIN query parameters, Bluetooth request formatting/CRLF, case-insensitive header parsing, whitespace trimming, missing/unrelated headers, header terminator, and 64-header limit.

**Build APK #185** passed on the transport regression branch, including unit tests and APK assembly. After merge, **main Build APK #186** succeeded. Both apps were confirmed updated successfully.

The manual acceptance checklist is [docs/DEVICE_ACCEPTANCE_CHECKLIST.md](docs/DEVICE_ACCEPTANCE_CHECKLIST.md). It records reported passes for phone-app backgrounding, phone Wi-Fi loss/recovery through Bluetooth, PIN throttling, and TV restart/reconnection. Additional PIN-header, incorrect-PIN, Bluetooth data, and Wi-Fi recovery checks for Task 6 were also reported passing.

## 3. Recommendations

These are future improvements, not claims of already-implemented functionality.

### Priority 0 — Encrypt Wi-Fi traffic

- Replace cleartext HTTP with an authenticated encrypted transport, such as TLS with a managed trust/pinning model or a cryptographically authenticated session established during pairing.
- Retain PIN throttling and command authorization after encryption; they address different threats.
- Do not describe the current header change as protection from LAN packet capture.

### Priority 1 — Expand transport regression tests

- Test `TvLink` handover: Wi-Fi failure to RFCOMM fallback, retry backoff, recovery to Wi-Fi, and reported route/state.
- Test connection-manager routing for all four states and command support in each state.
- Test FIFO queue order/expiry, request timeout and cleanup, malformed replies, and connection-loss races.
- Add focused tests in the `remote` module where practical and keep CI's unit-test gate required.

### Priority 1 — Close documentation and device-test gaps

- [docs/DEVICE_ACCEPTANCE_CHECKLIST.md](docs/DEVICE_ACCEPTANCE_CHECKLIST.md) still marks the diagnostics **Suggested fix** banner styling confirmation as pending. Verify it on a current APK and update the checklist.
- Record app build numbers and Android versions in future device acceptance runs.
- Repeat Wi-Fi-loss/recovery and Bluetooth fallback checks after major transport, permission, or foreground-service changes.

### Priority 1 — Signing-key incident hygiene

- Continue using only the replacement signing key stored in repository secrets; never reuse the exposed key or place signing values in source or logs.
- Consider rewriting Git history to remove the previously committed keystore, then review mirrors/forks as appropriate. History cleanup cannot remove copies already fetched.
- Review repository access and Actions permissions for signing secrets and release publishing.

### Priority 2 — Improve update and release integrity

- Publish release notes and a machine-readable manifest alongside `version.txt`.
- Publish APK SHA-256 checksums and preserve build-to-commit traceability.
- Consider immutable versioned releases with `latest` as a pointer, and document rollback/recovery.
- Test interrupted downloads, missing installer permission, unavailable releases, and signing-certificate mismatch.

### Priority 2 — Diagnostics and maintainability

- Provide a shareable, sanitized diagnostics report with build ID, transport state, last result, and error category; never include the PIN or secrets.
- Add structured, rate-limited connection state/route logs.
- Keep protocol formatting/parsing in shared unit-testable helpers and document the RFCOMM/HID distinction.
- Continue real-device tests on the Grennö GR-R40SMART and target phone; unit tests cannot fully verify Bluetooth behaviour.

## Repository Map

```text
app/       TV Dash app, TV-side services, PIN limiter, unit tests
remote/    TV Remote app, connection manager, Wi-Fi/RFCOMM/HID transports
shared/    Protocol helpers and APK update controller
docs/      Signing migration and real-device acceptance checklist
.github/   CI tests, APK build/artifacts, and release workflow
```

## Current Completion Snapshot

- **Signing/update pipeline:** implemented and verified with the replacement signing key.
- **PIN moved from URL to header:** merged; Wi-Fi and Bluetooth device checks reported passing.
- **Transport regression coverage:** merged; Build #185 passed.
- **Main build after merge:** Build #186 passed.
- **Installed apps:** both apps confirmed updated successfully.
- **Recommended next work:** encrypt Wi-Fi transport, broaden state/handover tests, review old signing-key history, and confirm the diagnostics-banner styling item still marked pending.
