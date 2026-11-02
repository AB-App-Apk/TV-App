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
3. ConnectionManager chooses one route for that hold. Bluetooth HID is preferred for Back, OK, UP, DOWN, Fast Forward and Rewind. The TV Dash network endpoint handles held UP/DOWN, Fast Forward/Rewind, Back and OK when HID is unavailable.
4. TV Dash keeps active network holds in a concurrent map and schedules an automatic release when its four-second safety lease expires.
5. While the touch remains down, the phone renews the lease every second; renewal refreshes the TV watchdog without sending a second DOWN.
6. For network-held Fast Forward/Rewind, TV Dash uses AccessibilityService D-pad global actions: FORWARD maps to DPAD RIGHT and REWIND maps to DPAD LEFT. It sends one action immediately, then repeats after 500 ms and every 250 ms while held. This follows Android TV's standard playback-control mapping and targets the foreground app's navigation path instead of sending media-session KeyEvents. Network-held UP/DOWN continue to use their matching D-pad global actions on the same cadence. Lease renewal does not reset either repeat cadence.
7. For network-held Back/OK, TV Dash waits 500 ms before classifying the gesture as a long press. A normal UP before that threshold produces exactly one tap. A long Back performs one Back action; a long OK attempts the focused UI node's accessibility long-click and falls back to one OK action if unavailable. Neither key repeats. A distinct CANCEL release suppresses taps after pointer cancellation or disposal. Bluetooth HID keeps its native press/release behavior.
8. A user UP, gesture cancellation, phone/service interruption, or the watchdog releases the hold and cancels timers. Duplicate release requests are idempotent.
9. The phone's input gate stays reserved from before DOWN until the matching release path finishes. Ordinary button commands and text payloads wait behind a real hold, so they cannot send a conflicting HID report or a competing TV Dash action.

## Platform limitation

`AccessibilityService.performGlobalAction()` cannot represent a native held Back or OK key. On Wi-Fi, TV Dash therefore implements deliberate gesture semantics instead of pretending to send native key-down events: taps are sent once on release, long Back sends one Back action, and long OK tries `AccessibilityNodeInfo.ACTION_LONG_CLICK` on the focused node before falling back to one center action. No Back/OK auto-repeat is generated. This focused-node long-click depends on the target app exposing a focused, long-clickable accessibility node; Bluetooth HID remains the native press/release route.

For network-held Fast Forward/Rewind, the watchdog uses AccessibilityService DPAD RIGHT/LEFT global actions (500 ms initial delay, 250 ms interval). Each action is a discrete foreground-navigation event; phone UP cancels the repeat job and does not send a synthetic media-key UP. Bluetooth HID remains the native Consumer Control media-key path. For network-held UP/DOWN, TV Dash performs the corresponding AccessibilityService global action once immediately and repeats that action on the same cadence. The TV-side repeats are independent of Wi-Fi/RFCOMM lease renewals. Verify seek preview, scrubbing, and OK confirmation on the target TV, as player and firmware behavior can vary.

## Permission/onboarding adaptation

The TV app now rechecks `BLUETOOTH_CONNECT` in `onResume` and shows a D-pad-friendly full-screen rationale before requesting it. A prior-request marker distinguishes a normal denial from a likely permanent denial; the latter offers the app-details settings screen. The overlay can be dismissed to continue Wi-Fi-only. The TV server needs `BLUETOOTH_CONNECT` for its RFCOMM listener, not `BLUETOOTH_SCAN`; no new manifest permission was added.

The phone's existing Bluetooth setup still requests its required Nearby devices permissions through `RequestMultiplePermissions`. The TV's existing accessibility setup remains user-enabled in system settings. Held input does not require browser/OAuth or a local-network runtime permission.

## Manual acceptance checklist

- [ ] Tap Back and OK with Bluetooth HID connected: one action, no duplicate tap.
- [ ] Hold Back and OK with Bluetooth HID: native HID report stays down until finger-up; cancellation also releases.
- [ ] Tap Back and OK over Wi-Fi: exactly one action per tap.
- [ ] Hold Back over Wi-Fi: exactly one Back action, no repeat.
- [ ] Hold OK over Wi-Fi: attempt focused-node long-click; if unsupported, exactly one OK action.
- [ ] Cancel a Back/OK touch (e.g. pointer cancellation): no tap or long-press action is emitted.
- [ ] Hold UP and DOWN over Wi-Fi: vertical navigation repeats after the initial delay and stops promptly on release.
- [ ] Hold UP and DOWN over Bluetooth HID: native keyboard reports stay down until release; taps still move exactly one step.
- [ ] Hold Fast Forward/Rewind over Wi-Fi: verify D-pad right/left scrubbing and that Short OK confirms the selected seek position.
- [ ] Hold Fast Forward/Rewind over Bluetooth HID: verify native media-key seeking still works.
- [ ] Hold Fast Forward/Rewind longer than 4 seconds: lease renewals keep the key down until UP.
- [ ] Drop the network after a media DOWN: TV sends UP no later than 4 seconds after the last successful lease renewal.
- [ ] Send duplicate UP: no crash and no second release.
- [ ] Send UP with a mismatched key ID: TV rejects it.
- [ ] While holding each key, press another remote button and type text: the extra input must wait until the held key is released, then continue without releasing the held report early.
- [ ] Disconnect Bluetooth while a key is held: Android releases the HID device state and the app clears its hold records.
- [ ] Test Wi-Fi and RFCOMM paths separately; the phone's PIN remains in the existing authenticated header.
- [ ] Confirm the existing accessibility setup and Nearby devices permission flow still work.
- [ ] Run the repository GitHub Actions test/build workflow. This change has not been compiled in this environment.
