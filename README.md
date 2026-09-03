# GBS Control for Android

A native Android app for [GBS-Control ESP8266](https://github.com/r-vd-woude/gbs-control-esp8266)
video scaler boards. It talks to the device over the local network: presets, picture geometry,
filters and output settings are all native Jetpack Compose screens, and the firmware's own web
interface remains one tap away for the things the native screens do not cover yet.

## Firmware compatibility

The app speaks two protocols and picks one per device at connect time.

| Firmware | Detected by | What the app uses |
| --- | --- | --- |
| API v1 (`v2.3.0` and later) | `GET /api/v1/device` answers `apiVersion: 1` | The JSON API for state, presets and commands |
| Legacy (`v2.2.1` and earlier) | that request fails or reports another version | `/sc`, `/uc`, `/bin/slots.bin` and `/slot/*` |

Either way the app also opens the six-byte `#...` WebSocket state frame on `/ws`, which every
firmware version serves. That frame is the live feed behind the switches, and it is the only source
for four settings API v1 does not carry (PAL 60 Hz forcing, low-res upscaling preference, ADC
calibration, external clock generator).

A few actions have no API v1 equivalent and therefore always take the legacy route, on both
firmwares: saving, renaming and removing presets, pass-through output, the frame-lock method
switch, and the four settings above. Nothing is unavailable on either firmware; the API is simply
preferred wherever it exists, because it reports state instead of blind-toggling it.

### What API v1 adds

- Toggles are idempotent. The app sends the state it wants; the device answers `noop` if it is
  already there. On legacy firmware every command is a blind toggle, so the app has to know the
  current state before it dares send anything.
- The input mode, scanline strength and the colour registers are only readable over API v1.
- `409 busy` and `503 low_memory` come back as real messages instead of a silent failure.
- The device advertises `_gbs-control._tcp` with an `api=1` TXT record, so discovery can tell a
  GBS board apart from every other HTTP responder on the network.

## Building

Requirements: JDK 17 (a full JDK, not a JRE - the Android Gradle Plugin needs `jlink`), and the
Android SDK with platform 34 and build-tools 34.0.0.

```bash
# point Gradle at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew testDebugUnitTest     # unit tests
./gradlew lintDebug             # lint
./gradlew assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

On Windows, if `JAVA_HOME` points at a JRE, override it for the build:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
.\gradlew.bat assembleDebug
```

## Tests

The protocol layer is plain Kotlin and covered by JVM unit tests - no emulator needed:

- `HostAddressTest` - hostname, IPv4 and bracketed IPv6 normalization
- `LegacySlotParserTest` - the 32-byte slot record, including truncation and corrupt names
- `LegacyStateParserTest` - the six-byte WebSocket state frame, bit by bit
- `ApiJsonParserTest` - every API v1 document, the command envelope, and the legacy fallback

The API v1 fixtures are copied from the "HTTP API v1" section of the firmware README. If the
firmware contract changes, these tests are the first thing that should fail.

## Permissions and network access

- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and `CHANGE_WIFI_MULTICAST_STATE`, the
  last one for mDNS discovery.
- Cleartext HTTP is permitted app-wide. The device serves plain HTTP and the user may point the app
  at any hostname or LAN address, so this cannot be narrowed to a fixed domain list. This matches
  the firmware's documented security scope: **a trusted local network only**. Do not expose a GBS
  device to the internet.
- A Wi-Fi network without internet access is still treated as connected, because that is exactly
  what a GBS board in AP mode looks like.
- The legacy web view is pinned to the selected device; it cannot navigate to another host, and
  file and content URLs are disabled.

## Known limits

- `targetSdk` is 34. Android 17 (SDK 37) introduces a local-network permission that discovery and
  direct LAN requests will need; that has to be handled before raising the target.
- Colour and geometry controls step by one, because the scaler exposes no absolute register writes
  over either protocol. On API v1 the resulting value is read back and displayed.
- The application ID is `com.gbscontrol.app`. Settle on the permanent one before any signed or
  store release - it cannot be changed afterwards for an existing installation.

## Licence

GPL-3.0, matching the firmware it controls. See [LICENSE](LICENSE).
