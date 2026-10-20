# Ahmed Home TV for Fire TV

A lightweight native IPTV app for Fire TV, in the dark ink and sage visual identity of [Ahmed Home TV](https://tv.ahmedhome.uk).

**Status: household update (0.2.0), with debug and signed release builds.** The household requirements are confirmed in [PRODUCT.md](PRODUCT.md). Emulator checks use a synthetic provider; the app has **not** yet been tested on the Fire TV Stick. [docs/STATUS.md](docs/STATUS.md) lists what was checked and what still needs the device.

The primary device is the **Fire TV Stick 4K, 2nd generation** (AFTKM, Fire OS 8.1.8.2, 32-bit app ABI), on the family's **55-inch Philips Ambilight 4K**. The priorities are avoiding crashes, discovering new and trending media, and smooth playback and remote navigation. One shared setup holds favourites and progress.

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
./gradlew connectedDebugAndroidTest   # device tests; start the mock provider below first
./gradlew assembleRelease         # minified; signed when release credentials are configured
```

The debug build installs as `uk.ahmedhome.tv.debug`, so it never conflicts with a future signed release (`uk.ahmedhome.tv`).

## Release signing

Keep the release keystore outside the repository. Create a local, Git-ignored `keystore.properties` in the repository root with these four properties (replace every placeholder):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=REPLACE_WITH_STORE_PASSWORD
keyAlias=REPLACE_WITH_KEY_ALIAS
keyPassword=REPLACE_WITH_KEY_PASSWORD
```

Each missing property falls back to its environment variable: `AHMEDHOME_STORE_FILE`, `AHMEDHOME_STORE_PASSWORD`, `AHMEDHOME_KEY_ALIAS` and `AHMEDHOME_KEY_PASSWORD`, respectively. Relative keystore paths resolve from the repository root. Restrict the local properties file and keystore to your user (`chmod 600`). Never commit either file or include passwords in build logs.

Run `./gradlew assembleRelease`. With all four values, the signed APK is `app/build/outputs/apk/release/app-release.apk`. With no signing values, the build succeeds unsigned as `app-release-unsigned.apk`; incomplete signing configuration fails with an error. Verify a signed APK with the Android SDK's `apksigner verify --print-certs <apk>`. Keep a recoverable backup of the keystore and both passwords: future updates must use the same signing key.

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

## Discovery and household playback

Home includes trending films, trending series, and separate new/trending Korean and Chinese rows in the existing ink/sage visual system. In **Settings → Discovery**, enter a TMDB **API Read Access Token** without `Bearer`. The app checks it, encrypts it on the TV with Android Keystore, and never includes it in the APK, Git, logs or device backups. Each installation supplies its own token; playback remains independent of the website and TMDB.

Only provider-library matches appear: a matching media type and TMDB ID, or an unambiguous exact title and year when the provider has no valid TMDB ID. English titles and original titles are checked; conflicting IDs and ambiguous remakes are rejected. Weekly trends come from TMDB's first five pages per media type. New Korean/Chinese feeds use dated releases from the past year, separately from trends and provider additions. The sampled feeds are bounded; an empty row does not prove that your provider has no other suitable titles. Chinese feeds include Mandarin and Cantonese original-language metadata.

Metadata refreshes at most every six hours, caches up to 512 KiB, keeps successful rows when a request fails, and expires cached feeds after seven days. Network errors never block provider playback. Failed automatic refreshes have a ten-minute cooldown; Settings and Home offer explicit retry. TMDB attribution is in **Settings → About**; see [its asset provenance](licenses/TMDB.md).

The default subtitle setting is **English for foreign audio**. It uses the selected audio track's declared language and enables English subtitles when supplied; unknown language is not guessed. A viewer's manual subtitle choice overrides that default for the current stream. Settings also offers always-English and off. Audio keeps the stream's default/original selection, with a player selector.

Bulk catalogue sync pauses when playback opens or the app leaves the foreground, then safely restarts the interrupted catalogue on return. Already-saved titles, favourites and progress stay usable. An empty refresh cannot erase a previously populated catalogue, and storage errors become refresh errors rather than escaping the worker.

## Test without a real provider

`tools/mock-xtream/server.py` is a synthetic Xtream provider (Python 3 + ffmpeg). It generates live MPEG-TS on demand, serves VOD with two audio tracks and subtitles, and includes channels that inject faults. It logs every stream connection with concurrent and peak counts. See [its README](tools/mock-xtream/README.md).

`HouseholdDeviceTest` additionally requires the mock provider on port **18099** (`python3 tools/mock-xtream/server.py --port 18099`). It uses a separate synthetic account namespace, seeded public metadata and a synthetic token; it restores any previous encrypted discovery token afterward. It checks actual audio/subtitle switching and native Back/arrow/OK journeys. TMDB success/empty/error responses use an isolated local server, with no external discovery requests. Synthetic screenshots are written under the emulator's `Pictures/ahmedhome-tv-review/<pixel-width>/` so they survive Gradle's test cleanup. Copy them into the ignored `.impeccable/review/` directory and remove the generated emulator images after review.

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
