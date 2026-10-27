# Held-button input: repository adaptation

## Repository fit

- The phone and TV are separate Android application modules: `:remote` and `:app`.
- Kotlin files in `shared/` are compiled into both apps; `shared/` is not a Gradle module.
- Wi-Fi uses the existing TLS-pinned HTTP endpoint on TCP 8765. The existing Bluetooth data fallback is classic RFCOMM.
- Bluetooth HID is a separate phone-as-keyboard/media-device link and is the preferred route for real press/release reports.
- No new Android permissions are required for this feature. Existing Bluetooth permission and pairing screens remain authoritative; TV Dash accessibility remains a user-enabled system setting.

## Hold lifecycle

1. Compose creates one UUID per touch and sends one DOWN callback.
2. The ViewModel serializes UP behind the corresponding DOWN job.
3. ConnectionManager chooses one route for that hold. Bluetooth HID is preferred for all four keys. The TV Dash network endpoint is only a fallback for Fast Forward and Rewind.
4. TV Dash keeps active network holds in a concurrent map and schedules an automatic UP when its four-second safety lease expires.
5. While the touch remains down, the phone renews the lease every second; renewal refreshes the TV watchdog without sending a second DOWN.
6. A user UP, gesture cancellation, phone/service interruption, or the watchdog releases the hold. Duplicate UP requests are idempotent.
7. The phone's input gate stays reserved from before DOWN until the matching UP/release path finishes. Ordinary button commands and text payloads wait behind a real hold, so they cannot send a conflicting HID report or a competing TV Dash media tap. One-shot fallback taps release the gate before using the ordinary command path.

## Platform limitation

`AccessibilityService.performGlobalAction()` cannot represent a held Back or OK key. `dispatchGesture()` injects touch gestures, not keyboard events. Therefore TV Dash's Wi-Fi endpoint returns `unsupported` for held Back/OK rather than falsely claiming a native hold. Those keys use Bluetooth HID when it is connected. Existing one-shot accessibility actions remain available through the regular tap handlers.

The network watchdog supports native media-key DOWN/UP for Fast Forward and Rewind using `AudioManager.dispatchMediaKeyEvent(KeyEvent)`. The OS/device may still ignore media-key holds; verify on the target TV.

## Permission/onboarding adaptation

The TV app now rechecks `BLUETOOTH_CONNECT` in `onResume` and shows a D-pad-friendly full-screen rationale before requesting it. A prior-request marker distinguishes a normal denial from a likely permanent denial; the latter offers the app-details settings screen. The overlay can be dismissed to continue Wi-Fi-only. The TV server needs `BLUETOOTH_CONNECT` for its RFCOMM listener, not `BLUETOOTH_SCAN`; no new manifest permission was added.

The phone's existing Bluetooth setup still requests its required Nearby devices permissions through `RequestMultiplePermissions`. The TV's existing accessibility setup remains user-enabled in system settings. Held input does not require browser/OAuth or a local-network runtime permission.

## Manual acceptance checklist

- [ ] Tap Back and OK with Bluetooth HID connected: one action, no duplicate tap.
- [ ] Hold Back and OK: HID report stays down until finger-up; cancellation also releases.
- [ ] Hold Fast Forward and Rewind: verify the TV/player actually repeats/continues seeking.
- [ ] Hold Fast Forward/Rewind longer than 4 seconds: lease renewals keep the key down until UP.
- [ ] Drop the network after a media DOWN: TV sends UP no later than 4 seconds after the last successful lease renewal.
- [ ] Send duplicate UP: no crash and no second release.
- [ ] Send UP with a mismatched key ID: TV rejects it.
- [ ] While holding each key, press another remote button and type text: the extra input must wait until the held key is released, then continue without releasing the held report early.
- [ ] Disconnect Bluetooth while a key is held: Android releases the HID device state and the app clears its hold records.
- [ ] Test Wi-Fi and RFCOMM paths separately; the phone's PIN remains in the existing authenticated header.
- [ ] Confirm the existing accessibility setup and Nearby devices permission flow still work.
- [ ] Run the repository GitHub Actions test/build workflow. This change has not been compiled in this environment.
