# Ahmed Home TV for Fire TV

A lightweight native IPTV app for Fire TV, in the dark ink and sage visual identity of [Ahmed Home TV](https://tv.ahmedhome.uk).

**Status: first release (0.1.0), debug build.** It has been tested on the Android TV emulator against a synthetic provider. It has **not** yet been tested on the Fire TV Stick. [docs/STATUS.md](docs/STATUS.md) lists exactly what was checked and what still needs the device.

The primary device is the **Fire TV Stick 4K, 2nd generation** (AFTKM, Fire OS 8.1.8.2, 32-bit app ABI). The priorities are reliable playback, predictable remote navigation, a clear interface on a 4K television, and low resource use.

## Documents

- [Product, architecture and delivery plan](docs/PLAN.md)
- [UI/UX brief, screen layouts and remote behaviour](docs/UX-BRIEF.md)
- [Performance targets and release acceptance](docs/ACCEPTANCE.md)
- [Implementation status, evidence and outstanding hardware tests](docs/STATUS.md)
- [Product context (Impeccable)](PRODUCT.md)

## Build

You need JDK 17 and the Android SDK (platform 37 and build-tools). Put the SDK path in `local.properties` as `sdk.dir=…`. That file is ignored by Git.

```sh
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest       # JVM tests
./gradlew connectedDebugAndroidTest   # Room tests on a connected emulator/device
./gradlew assembleRelease         # minified, unsigned (needs your own signing key)
```

The debug build installs as `uk.ahmedhome.tv.debug`, so it never conflicts with a future signed release (`uk.ahmedhome.tv`).

## Install on the Fire TV Stick

1. On the Fire TV, open **Settings → My Fire TV → About** and select the device name seven times to enable **Developer options**.
2. In **Settings → My Fire TV → Developer options**, turn on **ADB debugging** and allow **Apps from Unknown Sources** (or **Install unknown apps**).
3. Find the stick's IP address under **Settings → My Fire TV → About → Network**.
4. From a computer on the same network, run:

   ```sh
   adb connect <fire-tv-ip>:5555        # accept the prompt on the TV
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

5. Open **Ahmed Home TV** from **Your Apps & Channels**. Enter your provider's server address, username and password. Choose **Allow unencrypted HTTP** only if your provider has no `https://` address.

To update, run `adb install -r` with the new APK. Favourites, progress and settings are kept.

## Test without a real provider

`tools/mock-xtream/server.py` is a synthetic Xtream provider (Python 3 + ffmpeg). It generates live MPEG-TS on demand, serves VOD with two audio tracks and subtitles, and includes channels that inject faults. It logs every stream connection with concurrent and peak counts. See [its README](tools/mock-xtream/README.md).

## Layout

`app/src/main/java/uk/ahmedhome/tv/`

- `data/xtream`: streaming Xtream API client
- `data/db`: Room catalogue and user state
- `data/sync`: generation-safe library refresh
- `data/account`: Keystore-encrypted credentials and settings
- `playback`: single-player controller, retry policy, connection gate and player UI
- `ui`: Compose for TV screens and theme (`ui/theme` holds the ink/sage tokens)

## Privacy and secrets

Provider access is entered on the TV and stored only on the device, encrypted. No credentials, provider catalogues, signing keys or device captures belong in this repository. `.gitignore` excludes `local.properties`, keystores, `.env*`, captures and the mock provider's generated media. Logs remove usernames, passwords and stream paths.

## Licence

Code: MIT ([LICENSE](LICENSE)). Bundled font: Gelasio, under the SIL Open Font License 1.1 ([licenses/Gelasio-OFL.txt](licenses/Gelasio-OFL.txt)).
