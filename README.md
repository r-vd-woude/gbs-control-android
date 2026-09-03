# GBS Control for Android

A native Android app for [GBS-Control ESP8266](https://github.com/r-vd-woude/gbs-control-esp8266)
video scaler boards, driving them over the device's HTTP API on the local network.

Presets, picture geometry, filters and output settings are all Jetpack Compose screens. The
firmware's own web interface stays one tap away for the few things the native screens do not cover:
Wi-Fi provisioning, backup and restore, and firmware update.

## Screens

| | |
| --- | --- |
| **Devices** | mDNS discovery of `_gbs-control._tcp`, manual address entry, remembered devices |
| **Home** | signal, input mode, output preset, active slot, quick scanlines |
| **Presets** | all 72 slots — load, save, rename, remove |
| **Picture** | move / scale / border pads, ADC gain, colour nudges |
| **Filters** | scanlines and strength, line filter, peaking, step response, deinterlace |
| **Settings** | output resolution, device toggles, device info, web interface |

State comes from `GET /api/v1/state` plus the device's WebSocket feed, so the switches show what the
board is actually doing rather than what the app last sent. Commands are confirmed against the
device's `sequence` counter before they are reported as done.

## Building

Requires **JDK 17** — a full JDK, not a JRE, because the Android Gradle Plugin runs `jlink` — and the
Android SDK with platform 34 and build-tools 34.0.0.

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew testDebugUnitTest   # unit tests
./gradlew lintDebug           # lint
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

On Windows, if `JAVA_HOME` points at a JRE, override it:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
.\gradlew.bat assembleDebug
```

`assembleDebug` produces a debuggable APK signed with the shared debug key — fine for sideloading
onto your own phone, not for distribution. Release builds need a signing config and keystore, which
are not set up yet.

## Tests

The protocol layer is plain Kotlin, covered by JVM unit tests with no emulator needed: address
normalization, the device's slot binary, its WebSocket state frame, every API document and command
envelope, and the UTF-8 and sequence-arithmetic helpers.

Fixtures are copied from the firmware README. If the firmware contract changes, these tests are the
first thing that should fail.

## Network access

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, and `CHANGE_WIFI_MULTICAST_STATE` for mDNS.

Cleartext HTTP is permitted app-wide: the board serves plain HTTP and you may point the app at any
hostname or LAN address, so this cannot be narrowed to a fixed domain list. That matches the
firmware's security scope — **a trusted local network only**. Never expose a board to the internet.

A Wi-Fi network without internet access still counts as connected, since that is exactly what a
board in AP mode looks like. The embedded web view is pinned to the selected device and cannot
navigate elsewhere; file and content URLs are disabled.

## Known limits

- `targetSdk` is 34. Android 17 (SDK 37) adds a local-network permission that discovery and LAN
  requests will need before the target can be raised.
- Colour and geometry step by one, because the scaler exposes no absolute register writes. The
  resulting value is read back and shown.
- The application ID is `com.gbscontrol.app`. Settle the permanent one before any signed release —
  it cannot be changed afterwards for an installed app.

## Licence

GPL-3.0, matching the firmware it controls. See [LICENSE](LICENSE).
