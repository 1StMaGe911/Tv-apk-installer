# Android TV TLS pairing — manual validation

This checklist validates the wireless-debugging transport introduced by PR #152.
CI tests do not replace testing against a physical Android TV / Google TV device.

## Test setup

- Use an Android TV or Google TV device running Android 11 (API 30) or newer.
- Put the TV and the Android phone running TV APK Installer on the same trusted local Wi-Fi network.
- Disable guest-network isolation / client isolation for this test.
- On the TV, open Developer options and enable Wireless debugging.
- Keep the TV unlocked and its Wireless debugging page visible.
- Install the debug APK produced by the PR #152 CI run.

Do not publish the six-digit pairing code, private keys, or diagnostic logs containing local network details.

## Pairing flow

1. In TV APK Installer, enter or select the TV's numeric IP address.
2. Choose Find wireless pairing port.
3. On the TV, open Pair device with pairing code and note the displayed pairing port and six-digit code.
4. Select the matching pairing endpoint in the app. If discovery does not find it, enter the pairing port shown by the TV manually.
5. Enter the six-digit code and choose Pair TV over TLS.
6. Confirm the app reports pairing success.
7. Run Find TVs on local network and select the same TV endpoint marked TLS.
8. Choose Connect. Confirm the app reports a successful connection and the TLS shell probe succeeds.

Expected: pairing uses the _adb-tls-pairing._tcp. service; the subsequent ADB connection uses the distinct _adb-tls-connect._tcp. service and port.

## Persistence and reconnect

1. Force-stop TV APK Installer and reopen it.
2. Select the saved TV and connect again without repeating pairing.
3. Reboot the phone and repeat the connection test.
4. If practical, reboot the TV, re-enable Wireless debugging if required by the OS, and test again.

Expected: the app reuses its Android Keystore-backed ADB identity. It must not ask for a new pairing code unless the TV has revoked the pairing or the identity was cleared.

## Regression checks

- Connect to a second, unpaired TV using the existing legacy ADB workflow.
- Verify the second TV is not mistakenly treated as TLS-paired.
- Verify pairing discovery lists only pairing endpoints and normal discovery labels TLS-connect and legacy ADB endpoints correctly.
- Repeat discovery with IPv4. If the network and TV support it, repeat with IPv6.
- Turn Wireless debugging off and confirm the app reports a connection failure rather than a false connected state.
- Enter an incorrect pairing code and confirm pairing fails without saving a paired-host / TLS-connect-port association.

## Safety checks before maintenance

- Do not run bulk cleanup as part of transport validation.
- First verify connection and a harmless read-only operation.
- Before any cleanup test, review the package list and confirm that system-critical packages are excluded.
- Test package removal only on a spare / resettable TV, with explicit approval for each unfamiliar package.
- Confirm the app's recovery journal and rollback behavior before testing destructive operations.

## Record results

Record the TV manufacturer/model, Android version, app build commit, network type (IPv4/IPv6), and pass/fail for each section. Redact IP addresses, pairing codes, serial numbers, and other identifying details before sharing logs.

## Android 17 local-network permission upgrade gate

The current app targets Android 15 (API 35); compiling against API 37 does not by itself enable Android 17 local-network restrictions. Android 17 enforces the new local-network permission for apps that target API 37 or higher.

Before raising `targetSdk` to 37 or higher:

- Declare `android.permission.ACCESS_LOCAL_NETWORK` in the manifest.
- Request the permission at runtime before broad mDNS/NSD discovery or direct ADB connections to local-network addresses.
- Handle denial and later revocation without reporting a false connection success.
- Evaluate the Android 17 NSD system picker (`DiscoveryRequest.FLAG_SHOW_PICKER`) for user-selected discovery where it can replace broad network scanning.

Do not add or request `ACCESS_LOCAL_NETWORK` while targeting API 36 or lower; Android's current guidance says those targets retain implicit local-network access through `INTERNET`. Re-check the platform guidance when the target SDK is upgraded.
## Dadb transport-parity smoke tests

The TLS adapter implements Dadb's two transport primitives: `open(destination)` and `supportsFeature(feature)`. Dadb supplies shell, sync, push/pull, install, uninstall, and related operations on top of those primitives. Validate representative operations over TLS before treating the transport as interchangeable with legacy ADB:

1. **Read-only shell:** run a harmless `getprop ro.product.model` query and compare the result with legacy ADB.
2. **Feature negotiation:** verify `supportsFeature("cmd")` and the app's selected install path behave consistently with the device's legacy ADB result.
3. **Sync stream:** push a small temporary text file to an app-owned test path under `/data/local/tmp`, pull it back, and compare its SHA-256. Remove only that test file after verification.
4. **APK install path:** on a spare/resettable TV, install or update a harmless test APK, verify package name/version/signature, then uninstall that test package. Do not use a production app for this test.
5. **Backup path:** create and export a read-only TV backup, then verify the archive can be opened locally. Do not run cleanup or package-disable actions as part of transport validation.
6. **Disconnect handling:** turn Wireless debugging off during a read-only operation. The app must surface a connection error, close the failed stream, and must not report a successful operation.

Run the same smoke tests through legacy ADB as a baseline. Record each result separately; a successful TLS shell probe alone does not prove sync or APK installation parity.
