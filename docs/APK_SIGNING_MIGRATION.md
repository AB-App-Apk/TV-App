# APK signing migration

## Why this is required

The former `keystore.b64` file and hardcoded Gradle passwords were committed to a public repository. Treat the old signing key as compromised. Removing the file from the current branch does not erase it from Git history or make it trustworthy.

The replacement key is stored in GitHub Actions repository secrets. This prevents the workflow from reading signing material from source files, but the release transition is not complete until the new-key builds are verified.

## Required GitHub Actions repository secrets

The build workflow requires these repository secrets:

- `ANDROID_SIGNING_KEY_BASE64`: single-line Base64 encoding of the **new keystore file**.
- `ANDROID_KEYSTORE_PASSWORD`: password used to open the new keystore.
- `ANDROID_KEY_ALIAS`: exact alias of the new key.
- `ANDROID_KEY_PASSWORD`: password for that alias.

The old `keystore.b64` must not be copied into the new secret. Never put signing values in source files, issues, pull-request comments, or workflow logs. Do not paste secret values into chat.

## Compatibility decision: clean reinstall required

The new key is unrelated to the publicly exposed key, and no proof-of-rotation lineage is configured. Android will reject a new-key APK as an in-place update of an installed old-key APK.

For this migration, plan a controlled clean reinstall rather than an in-place rotation:

1. Build both APKs from the draft branch and confirm that the Actions workflow passes.
2. Keep the existing applications installed while the APKs are being built and checked. Do not publish the draft-branch artifacts as the live `latest` release.
3. Save any settings or information that must be restored. Uninstalling an app normally removes its local app data and permissions.
4. When the new-key APKs are ready, uninstall the old TvDash from the TV and old TvRemote from the phone, then install the matching new APKs.
5. Reconfigure permissions and settings, test remote connectivity, and verify that a later release signed with the same new key updates in place.
6. The project still declares `minSdk = 26`; test the installation flow on the actual supported devices before considering the migration complete.

Do not uninstall the existing apps until both new-key APKs have been built and the installation plan is ready.

## Workflow behavior

The workflow validates that the new keystore, keystore password and alias are usable, passes passwords to Gradle through environment variables, and removes the generated `release.keystore` from the runner after tests/building. Both applications use the same new key so future releases can update each app in place.

This protects future workflow inputs, but it does not erase the old signing material from public Git history. History cleanup may be considered separately; it is not a substitute for replacing the key.
