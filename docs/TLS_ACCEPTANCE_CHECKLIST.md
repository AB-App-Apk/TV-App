# TLS (Wi-Fi encryption) acceptance checklist

**Scope:** the encrypted Wi-Fi link between TvRemote and TvDash (TLS with a pinned, self-signed certificate and a security-code check). Run on the real phone and the Grennö GR-R40SMART after the CI build passes. Unit tests prove the certificate, pinning and handshake logic on a JVM; they cannot prove the TV's own TLS stack, so these checks are required.

Do not put the PIN in a screenshot, a log or a command line while running these.

## Setup

- Install the new **TV Dash first**, then the new **TV Remote** (older and newer builds cannot talk over Wi-Fi).
- Phone and TV on the same Wi-Fi. Bluetooth paired as before.
- Record the TV Dash and TV Remote build numbers and the phone's Android version.

## Checks

### 1. The TV shows a security code
Open TV Dash. A **Security code** card shows four groups of four characters. Restart TV Dash (or reboot the TV): the code must be **identical** afterwards.
**PASS:** a code is shown and does not change on restart. **FAIL:** no code ("Unavailable"), or it changes on each start.

### 2. The TV's listener is up
`adb logcat -s RemoteService TvTlsStore` while TV Dash starts shows "HTTPS listener is up on port 8765". If it shows "listener restarting" with an SSL error, stop and report the message: the TV's TLS stack rejected the key or certificate.

### 3. First-time setup on a phone (Change TV, then Connect)
Enter the TV's IP and PIN, tap **Connect**. A "Verify your TV" dialog shows a code; it must equal the TV's Security code. Tap **It matches**.
**PASS:** the remote opens and a basic command works. **FAIL:** no dialog, the dialog code differs from the TV, or the remote does not open.

### 4. Cancel sends nothing
Repeat check 3 but tap **Cancel**. **PASS:** the remote does not open, the PIN is not rejected, and the TV's PIN cooldown is not triggered.

### 5. An already set up phone
Update an existing phone that was set up before this build. **PASS:** the dialog appears by itself while the TV is on (or the notice says to tap Change TV, then Connect), and after "It matches" the remote works. Until then, Wi-Fi control is off and Bluetooth still works if it was set up.

### 6. The link really is encrypted (from a laptop on the same Wi-Fi)
- `curl -v --max-time 5 http://<TV_IP>:8765/ping` must **not** return an HTTP answer (empty reply, reset or timeout).
- `openssl s_client -connect <TV_IP>:8765 </dev/null 2>/dev/null | openssl x509 -noout -subject -fingerprint -sha256` shows `CN = TV Dash`; the first 16 hex characters of the fingerprint must equal the security code (ignoring colons and spaces).
**PASS:** both as described. **FAIL:** a plain HTTP answer is returned.

### 7. A different certificate is refused
Reinstall TV Dash (or clear its data) so it makes a new certificate. **PASS:** the phone shows the notice about the TV's security certificate, Wi-Fi control stops, Bluetooth still works, **no PIN lockout happens**, and Change TV, then Connect shows the dialog with "not the TV you verified before". After verifying again, control returns. Note: the PIN also changes after a reinstall, so enter the new PIN.

### 8. Diagnostics
Open Connection diagnostics. **PASS:** "TV certificate verified" shows "Yes" with the same code, and the connection test reports the route as "Wi-Fi / HTTPS (TLS, pinned certificate)".

### 9. Existing checks still pass
Repeat checks 1 to 4 of `DEVICE_ACCEPTANCE_CHECKLIST.md` (backgrounding, Wi-Fi loss and recovery, PIN throttling, TV restart). The PIN throttling check now happens after the security-code check: use a verified TV, then enter a wrong PIN.

## Result record

| Check | Result (PASS / FAIL / NOT RUN) | Notes |
|---|---|---|
| 1. Security code shown and stable | NOT RUN | |
| 2. Listener up | NOT RUN | |
| 3. First-time setup | NOT RUN | |
| 4. Cancel sends nothing | NOT RUN | |
| 5. Phone set up before TLS | NOT RUN | |
| 6. Link is encrypted | NOT RUN | |
| 7. Changed certificate refused | NOT RUN | |
| 8. Diagnostics | NOT RUN | |
| 9. Existing checks | NOT RUN | |

- TV Dash / TV Remote builds: not recorded
- Phone model / Android version: not recorded
- TV: Grennö GR-R40SMART, Android TV 14

## If TLS does not work on the TV

Report the check number and the `adb logcat -s RemoteService TvTlsStore` lines. Control over the Bluetooth data channel and Bluetooth keyboard is unaffected by this change, so a TLS problem does not leave the phone without a way to control the TV when Bluetooth is set up.
