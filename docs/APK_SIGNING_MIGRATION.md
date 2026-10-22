# APK signing secret migration

## Why this is required

The former `keystore.b64` file and the hardcoded Gradle passwords were committed to a public repository. Treat that signing key as compromised. Removing the file from the current branch does not erase it from Git history or make the old key trustworthy.

This change moves future build inputs to GitHub Actions repository secrets and makes CI fail clearly if required values are missing. It is containment, not the final key-rotation step.

## Required GitHub Actions repository secrets

Before merging this change, open the repository's **Settings → Secrets and variables → Actions** and add these repository secrets:

- `ANDROID_SIGNING_KEY_BASE64`: base64 encoding of the keystore file (single-line value).
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: alias inside the keystore.
- `ANDROID_KEY_PASSWORD`: password for that alias.

Never put any of these values in source files, issues, pull-request comments, or workflow logs. Do not paste secret values into chat.

**Do not merge until the secret values have been configured and the signing transition has been tested.** The updater installs APKs over existing installs, so a mismatched signing certificate can make updates fail.

## Key rotation and compatibility gate

The exposed key must not be treated as safe just because it is now stored as a secret. A replacement signing key and Android proof-of-rotation lineage must be planned and verified. Do not simply generate a new keystore and publish APKs signed only by that key.

Before publishing rotated APKs:

1. Identify the certificate fingerprint of the currently installed/published TvDash and TvRemote APKs.
2. Prepare the new signing key and a valid signing lineage using Android's `apksigner` tooling.
3. Verify signatures and lineage for both packages.
4. Install each rotated APK over the currently installed app on the target Android 14 phone and TV without uninstalling first.
5. Confirm the app data remains, the app launches, and the in-app updater successfully installs a later build.
6. Decide and document behavior for API 26–27 devices. The project currently declares `minSdk = 26`; rotation defaults and legacy-device behavior must not be assumed.
7. Only after these checks pass, publish the rotated key and retire the old signing material from active build configuration.

Git history cleanup alone does not revoke a previously exposed private key. Consider repository-history cleanup after the operational transition, but do not treat it as a substitute for key rotation.

## Workflow behavior

The workflow expects the four secrets above, validates the keystore and alias, exposes passwords only as step environment variables, and removes the generated `release.keystore` from the runner after the build step. The same signing identity is used by both applications to preserve current update compatibility during the transition.
