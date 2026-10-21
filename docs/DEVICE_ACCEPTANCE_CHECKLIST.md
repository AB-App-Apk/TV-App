# Real-Device Acceptance Checklist

**Scope:** Task 3 of the TvRemote/TvDash roadmap. Run against the actual phone and Grennö GR-R40SMART TV after the unit-test/CI gate passes.

This is a manual acceptance checklist, not an automated emulator test. Record the result on the same hardware and network setup used for normal remote operation. Do not proceed to the diagnostics feature until these checks have been recorded.

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

### 3. TV PIN throttling

Run this test after the connectivity checks. Use a browser on the **same phone and Wi-Fi network** so the test requests originate from the same client IP as TvRemote. Keep the correct PIN private; do not post it in screenshots or logs.

1. Find the TV's current IP address on the TV Dash dashboard and its correct PIN on the Phone remote card.
2. Choose a different four-digit PIN that is definitely incorrect.
3. In the phone browser, open the following URL, substituting the TV IP and chosen wrong PIN:
   `http://TV_IP:8765/apps?pin=WRONG_PIN&check=1`
4. Repeat five times, changing `check=1` to `check=2`, `check=3`, `check=4`, and `check=5` to avoid browser caching.
5. Immediately request `http://TV_IP:8765/apps?pin=CORRECT_PIN&check=6`, substituting the real PIN locally. The request should still be denied during the cooldown.
6. Wait at least 65 seconds after the fifth incorrect attempt and retry with a new `check` value. Do not share any URL containing the correct PIN.

**PASS:** Incorrect attempts are denied; the correct PIN is also denied during the one-minute cooldown; the correct PIN succeeds after cooldown expires. The same client's active cooldown remains in effect.  
**FAIL:** Wrong PIN requests succeed, the correct PIN bypasses the active cooldown, or access remains blocked after the initial cooldown has elapsed.

**Important:** This check intentionally blocks further requests from the phone's IP for about one minute. Do not start a different connectivity check during the cooldown. The first cooldown is 60 seconds; repeated lockouts use longer cooldowns.

### 4. TV restart and reconnection

1. Wait for any PIN cooldown from check 3 to expire.
2. Reboot the TV normally. Leave the phone running, with Wi-Fi and Bluetooth enabled.
3. Wait until Android TV finishes booting and TV Dash's foreground service has had time to start (allow up to two minutes).
4. Use TvRemote to send a basic command. Try again after another 15 seconds if the first attempt occurs during startup.

**PASS:** TvRemote reconnects and can control the TV without re-pairing or reinstalling either app.  
**FAIL:** Control does not return after boot and the two-minute startup allowance, or pairing/setup must be repeated.

## Result record

Fill this in after running the checks on the physical devices.

| Check | Result (PASS / FAIL / NOT RUN) | Notes |
|---|---|---|
| 1. Backgrounding | NOT RUN | |
| 2. Wi-Fi loss and recovery | NOT RUN | |
| 3. PIN throttling | NOT RUN | |
| 4. TV restart | NOT RUN | |

- Test date:
- Phone model / Android version:
- TvRemote build:
- TV build / Android version:
- Wi-Fi router or network notes:

## Failure report

For a failure, record the check number, exact step, what was expected, what happened, and whether retrying once changed the outcome. Avoid sharing PINs, MAC addresses, or other secrets. Keep this report separate from the logs used for troubleshooting.
