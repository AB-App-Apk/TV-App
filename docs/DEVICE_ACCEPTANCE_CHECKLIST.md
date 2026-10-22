# Real-Device Acceptance Checklist

**Scope:** Task 3 of the TvRemote/TvDash roadmap. Run against the actual phone and Grennö GR-R40SMART TV after the unit-test/CI gate passes.

This is a manual acceptance checklist, not an automated emulator test. The four core checks below have been reported as passing on the actual phone and Grennö TV. The connection-diagnostics screen has also been reported as working as intended; the latest suggested-fix color change still needs confirmation on the newly built APK.

## Test setup

- Phone has the current TvRemote APK installed.
- TV has the current TvDash APK installed and is fully booted.
- Phone is configured with the TV's IP address and correct PIN.
- Phone and TV are on the same Wi-Fi network for the initial checks.
- Bluetooth is enabled on both devices, and the phone is paired with the TV for the Bluetooth data/HID paths.
- Confirm the remote works normally before starting. If baseline control fails, record that separately and do not count later scenarios as passes.

## Acceptance checks

### 1. Phone app backgrounding

1. Open TvRemote and confirm an ordinary command works.
2. Press Home to put TvRemote in the background. Leave it there for at least two minutes; do not force-stop it or swipe it away from Recents.
3. Reopen TvRemote and send a simple command such as volume up/down or D-pad navigation.
4. Repeat once with the phone screen locked for two minutes, if that is part of normal use.

**PASS:** A command works after returning, without reopening Bluetooth settings, re-pairing, or manually restarting TvRemote.  
**FAIL:** Control is lost and only returns after manually restarting the app, toggling Bluetooth, or re-pairing.

### 2. Phone Wi-Fi loss and recovery

1. Confirm the normal remote works while phone Wi-Fi is enabled.
2. Turn **off Wi-Fi on the phone only**. Keep the TV powered on, Bluetooth enabled, and the phone paired.
3. Use TvRemote to send D-pad/OK and volume commands. Also try the Apps action if TV Dash's Bluetooth data channel is paired and available.
4. Turn phone Wi-Fi back on, reconnect to the same network, wait up to 30 seconds, then try the same commands again.

**PASS:** Bluetooth control continues while phone Wi-Fi is off, and normal control returns after Wi-Fi is restored without restarting the app or re-pairing. Record separately if HID keys work but TV Dash-only actions such as the app list do not.  
**FAIL:** All control is lost, or reconnecting Wi-Fi requires manually restarting TvRemote/TV Dash.

### 3. TV PIN throttling and header-based authentication

Run this test after the connectivity checks. Use TvRemote on the **same phone and Wi-Fi network** so requests originate from the same client IP. The PIN must be sent in the `X-TV-PIN` HTTP header, not in the URL. Do not put the correct PIN in a browser address bar, screenshots, or logs.

1. Find the TV's current IP address on the TV Dash dashboard and its correct PIN on the Phone remote card.
2. In TvRemote's **Connect to your TV** setup screen, enter the TV IP and a definitely incorrect four-digit PIN.
3. Tap **Connect** and confirm TvRemote reports that the PIN was rejected.
4. Repeat the incorrect-PIN attempt until five failed requests have been made from the same phone/IP.
5. Immediately enter the correct PIN and tap **Connect** again. The request should still be denied during the cooldown.
6. Wait at least 65 seconds after the fifth incorrect attempt, then retry with the correct PIN.

**PASS:** Incorrect attempts are denied; the correct PIN is also denied during the one-minute cooldown; the correct PIN succeeds after cooldown expires. The same client's active cooldown remains in effect.

**FAIL:** Wrong PIN requests succeed, the correct PIN bypasses the active cooldown, or access remains blocked after the initial cooldown has elapsed.

**Important:** This check intentionally blocks further requests from the phone's IP for about one minute. Do not start a different connectivity check during the cooldown. The first cooldown is 60 seconds; repeated lockouts use longer cooldowns. Requests that put the PIN in the URL are no longer supported.
 
### 4. TV restart and reconnection

1. Wait for any PIN cooldown from check 3 to expire.
2. Reboot the TV normally. Leave the phone running, with Wi-Fi and Bluetooth enabled.
3. Wait until Android TV finishes booting and TV Dash's foreground service has had time to start (allow up to two minutes).
4. Use TvRemote to send a basic command. Try again after another 15 seconds if the first attempt occurs during startup.

**PASS:** TvRemote reconnects and can control the TV without re-pairing or reinstalling either app.  
**FAIL:** Control does not return after boot and the two-minute startup allowance, or pairing/setup must be repeated.

## Result record

**Reported outcome: 4/4 core real-device checks passed.** Results below reflect the user's physical-device test report.

| Check | Result (PASS / FAIL / NOT RUN) | Notes |
|---|---|---|
| 1. Backgrounding | PASS | Reported passed on the phone. |
| 2. Wi-Fi loss and recovery | PASS | Reported Bluetooth fallback and Wi-Fi recovery passed. |
| 3. PIN throttling | PASS | Reported incorrect-PIN cooldown behavior passed. |
| 4. TV restart | PASS | Reported reconnect after TV restart passed. |

- Result reported: 2026-10-21
- Phone model / Android version: Not recorded
- TvRemote build: Not recorded
- TV: Grennö GR-R40SMART, Android TV 14
- Wi-Fi router or network notes: Not recorded

## 5. Connection diagnostics screen (Task 4)

1. Open TvRemote and scroll to **Connection diagnostics**.
2. Confirm phone Wi-Fi, Bluetooth radio, Nearby devices permission, selected TV/configuration, Bluetooth HID state, and combined remote state are displayed.
3. Tap **Run connection test** and verify a safe ping reports success/failure, response time, route, and time of the last successful response.
4. Tap **Retry links** and confirm it requests the existing data and keyboard links to reconnect.
5. Check the **Suggested fix** banner colors after installing a build containing the latest styling change.

**PASS (screen functionality):** The user reported that the diagnostics screen works as intended.  
**PENDING (latest styling):** Confirm on the newly built APK that the Suggested fix background is `Color(0xFFFFB300)` and its text is `Color(0xFF1F1300)`.

## Failure report

For a failure, record the check number, exact step, what was expected, what happened, and whether retrying once changed the outcome. Avoid sharing PINs, MAC addresses, or other secrets. Keep this report separate from the logs used for troubleshooting.
