# First release: implementation status and evidence

Updated 20 October 2026. Version 0.2.0 (release `uk.ahmedhome.tv`, debug `uk.ahmedhome.tv.debug`).

This document separates checks that were run from tests that still need the Fire TV Stick. None of the performance or soak gates in [ACCEPTANCE.md](ACCEPTANCE.md) has been run on hardware. Do not read any statement here as a claim about crash stability or performance on the stick.

## Owner-confirmed household update (0.2.0)

The owner reviewed and approved PRODUCT.md after eight interview questions. The target is one shared setup on a 55-inch Philips Ambilight 4K with the existing Fire TV Stick. The priorities are avoiding crashes, discovering new/trending media, and smooth playback/navigation. Live football and Korean/Chinese films and shows are important household journeys.

- **Discovery:** six Home rows: general weekly films/series, and separate new/trending Korean and Chinese film/series feeds. A private TMDB token is configured in Settings, validated before saving, encrypted with Android Keystore and excluded from the APK. Cached metadata is bounded to 512 KiB and matched against Room without loading the full provider catalogue. IDs take precedence; title/year fallback must be exact and unambiguous. New releases and weekly trends are never conflated with provider additions.
- **Remote navigation:** Home scrolls only when focused content needs room for its heading, title and caption. Settings retains the current section heading while scrolling. The native journey test asserts focused feed identity and unclipped bounds within the actual scrolling viewport.
- **Playback preferences:** English subtitles automatically follow declared foreign audio when supplied. English audio keeps subtitles off by default; unknown audio language is not guessed. Manual subtitle selection overrides automatic selection for that stream. A missing English track is explained.
- **Watching during refresh:** bulk imports and discovery stop when playback opens or the main activity leaves the foreground. The interrupted catalogue restarts safely on return. Network cancellation closes an already-streaming catalogue body. The previously completed count is preserved before refresh, so the empty-library guard works across repeated failures. Storage exceptions and bookkeeping failures cannot escape the refresh worker.
- **Stale playback requests:** an older resume/settings lookup cannot reopen a superseded channel or stopped playback activity.
- **Signing:** the release keystore is outside Git, privately backed up and recovered to verify its checksum. Local properties and environment credentials both sign correctly; no credentials still produces an unsigned release. APK signatures were verified with apksigner, using the same RSA-4096 certificate recorded in the private secrets entry.

Completed new checks: **51 JVM tests**, including bounded authenticated TMDB requests, offline cache retention, media identity/title-year matching, actual-audio subtitle policy, Bearer redaction, empty refresh protection, storage failure containment and foreground transfer cancellation/resumption. On the API 31 Android TV emulator, **8 instrumented tests** pass: the existing six Room tests, actual two-audio-track MKV subtitle switching (English → French enables English subtitles; manual off remains off), and D-pad discovery/Settings journeys using real Room matches and synthetic metadata. No real provider or TMDB credentials are used by these tests. The complete native UI journey also passes at real Android **130% text size**, including full row/message bounds, persistent Settings headings and the TMDB disclosure/logo. These remain API 31 emulator checks, not Fire OS accessibility or television sign-off.

Fresh Impeccable finish review: **ship** for all 26 native emulator captures at normal and 130% text, with no material fixes. The documentation handoff compared the finished extension with the existing theme and shared components and confirmed that the incumbent visual system is preserved; no new design-system artifacts are required. This verdict covers the reviewed UI and recovery states; physical Fire TV behaviour and 4K output remain outside the evidence.

The earlier 0.1.0 evidence below is retained as historical evidence, not a fresh measurement of 0.2.0. Real-provider discovery coverage, live football on the stick, all 32-bit decoding/performance/soak gates and the household television remain unverified.

## What was built

| Area | Implementation |
| --- | --- |
| Setup | Server, username and password fields with Show/Hide. **Remember on this TV** stores credentials encrypted with an Android Keystore AES-GCM key. A separate, explicit **Allow unencrypted HTTP** choice is required for plain-HTTP providers. The connection is checked before saving, and errors appear inline. |
| Library sync | Live TV, Movies and Series are refreshed one after another. JSON is streamed and written to Room in batches of 500. Each refresh is a new generation: older rows are pruned only after a catalogue completes. A refresh that returns empty, after the catalogue previously had items, is rejected. HTML or other non-JSON responses are rejected rather than treated as an empty catalogue. |
| Editions | Titles are grouped only when the media type and a valid TMDB ID match. Language, quality and year are parsed from provider names. Unknown values are shown as unknown. |
| Home | Continue Watching, Favourite Channels, six matched discovery feeds and Recently Added. The first useful item takes focus on launch. A facts line shows details for the focused item. Empty and loading states are handled. |
| Live TV | Categories, a Favourites filter, a paged channel list and now/next guide data. Guide data is fetched 450 ms after focus settles. OK plays the channel, and focus alone never opens a stream. |
| Movies / Series | Paged poster grids with a category panel (Back closes it without changes) and a sort that cycles through Recently added, A–Z and Rating. |
| Details | Movies have editions, Resume / Start over and My List. Series have seasons, episodes with progress, a Resume or Next button and editions. |
| Search | Local search across all catalogues with accent and Arabic normalisation. Matches are ranked exact, then prefix, then word prefix, then contains. Results show per-scope counts, a coverage warning while a sync is incomplete, and recent searches. |
| My List | Saved channels, movies and series for the current account. Items the provider has removed are labelled, and pressing OK removes them. |
| Player | Media3 with a SurfaceView. One player per playback screen and one provider connection at a time, enforced by a counting data source. Channel up/down is debounced. Left/Right shows a seek preview that OK commits. Audio and subtitle panels are available. Up next / Play next episode appears at the end of an episode. Automatic retries are limited to 3, with backoff, inside a 30-second window, plus prepare and stall watchdogs. 401/403/404/410 responses fail immediately. 4K video that would need a software decoder is refused. Progress is saved every 15 s and on pause or exit. The player is released in `onStop`, and returning never restarts live TV silently. |
| Settings | Library status and refresh, live format (MPEG-TS or HLS), preferred edition language, account status, sign-out confirmation, clearing the artwork cache, and a list of this device's hardware decoders (useful for checking HEVC and 4K on the stick). |
| Security | Credentials are removed from logs. Backup and device transfer are disabled. Stream URLs are built at play time and never stored. The app enforces the HTTP opt-in, and certificate checks are never disabled. |

## Initial 0.1.0 checks (Mac + Android TV emulator)

The environment was macOS on Apple silicon, with the **Android TV emulator API 31, arm64-v8a, 1920×1080 at 320 dpi** (a 960×540 dp UI, matching the stick's layout density). The provider was the synthetic mock in `tools/mock-xtream/`.

| Check | Result |
| --- | --- |
| Debug build (`assembleDebug`) | Passes. |
| Minified release build (`assembleRelease`, R8) | Passes. The unsigned APK is 4.8 MB (4,829,540 bytes). A local debug-key-signed copy launched on the emulator without crashing; playback was not tested with it. |
| 32-bit packaging | The APK contains `lib/armeabi-v7a/*.so`, verified as `ELF 32-bit ARM EABI5`. No app code is native. |
| JVM unit tests | **43 passing**. They cover redaction, normalisation, edition parsing and grouping, the search query builder, retry policy, failure classification, credential codec and account ID, next-episode choice, and Xtream contract tests against MockWebServer (malformed JSON, HTML error page, mixed field types, both series episode shapes, base64 EPG, URL building, the cleartext refusal). |
| Large catalogue parse | 100,000 synthetic channels were streamed in 200 batches of at most 500 in about 100 ms on the **Mac's JVM**. This says nothing about the stick. |
| Instrumented Room tests (emulator) | **6 passing**. They cover TMDB-only grouping, an interrupted refresh keeping the library while a completed one prunes, regrouping after pruning, search ranking (including accents), account isolation, and Continue Watching (one entry per title, finished items excluded). |
| Live playback | MPEG-TS played from the mock provider (hardware H.264 path on the emulator). |
| Channel switching | 12 rapid presses opened only the final channel. Six slow switches each closed the old connection before the next opened. The mock provider's log showed **at most 1 concurrent connection**. An earlier build peaked at 3; that was fixed and retested. |
| VOD | 1080p MP4: details, play, seek preview and commit, controls, second audio track detected, and resume position shown on the details page. |
| Series | Played to the end, then Up next and Play next episode. The episode shows as Watched and the button changes to Next: S1 E2. |
| Fault handling | A 404 failed in about 40 ms with "Not available from the provider". A 401 failed immediately with "The provider refused this stream". A silent stream tripped the watchdog, reconnected, then stopped with an actionable error. A stream that drops after 8 s retried with backoff, then showed Try again / Back with no loop. |
| Lifecycle | Pressing Home during live playback closed the provider connection immediately. Returning to the app shows the same Live TV channel with nothing streaming. |
| Focus and Back | Launch focus is deterministic. Left from content lands on the selected rail item. Back from details restores the focused poster or channel. The filter panel returns focus to its button. Back on the rail exits the app. |
| Keyboard | The Gboard TV keyboard on the emulator could stay bound after hiding and turn D-pad presses into typed characters. This was fixed by detaching the input method; the setup sequence then passed 5 times out of 5. |
| Emulator memory (debug build, **not representative**) | Total PSS, sampled once at each point: Home 112 MB, Movies grid after scrolling 118 MB, 1080p VOD playing 127 MB, after leaving playback 119 MB. These are single samples from a debug build on a 64-bit emulator, not AFTKM release figures. |

Screens were checked through emulator screenshots: setup, Home, Live TV, Movies, the category panel, movie details, series details, Search, My List, Settings, player controls, the seek preview, and the error and Up next panels.

## Outstanding: needs the Fire TV Stick (AFTKM, Fire OS 8.1.8.2)

Nothing in this section has been run.

- **Install and run as a 32-bit app.** The emulator image is 64-bit only, so the armeabi-v7a runtime path has not run anywhere.
- **The real provider.** Run login, a full catalogue sync (size and duration), the live format that works (MPEG-TS or HLS), VOD containers (MKV/MP4) and EPG coverage.
- **Hardware decoding.** Check which decoder is chosen for H.264, HEVC and 4K, the HDR display path and the audio route (Dolby/AC-3 passthrough). The emulator's codecs differ from the stick's.
- **Fire OS keyboard and remote.** The input-method fix was verified only with Gboard TV. Repeat setup and search with the Amazon keyboard, the voice button and Menu.
- **All ACCEPTANCE.md performance budgets:** cold launch, D-pad latency, frame pacing, search latency on a 100k catalogue, stream start times, PSS during browsing and 4K playback, and retained growth.
- **All ACCEPTANCE.md hardware scenarios:** the 4-hour live soak, 2-hour VOD including 4K, 100 channel switches with memory sampling, 50 browse/detail/player cycles, 20 Home/sleep/wake cycles, network unplug, low storage and process death, 1080p and 4K output, the device after exit, and install/upgrade.
- **VoiceView** reading order and labels, Fire OS text scaling, and visual sign-off at 4K on the TV.

## Known limitations and deviations

- The navigation rail is 168 dp wide, not the brief's proposed 112 dp, so text labels fit at 16 sp. This needs checking on the TV.
- At 130% text, the pre-existing compact navigation brand truncates “Ahmed Home” to “Ahmed”. The documentation handoff recorded this inherited limitation; the shared brand component was preserved.
- Release signing is now configured and verified; the private key and properties are excluded from Git. Signed release install/update still needs the actual Fire TV Stick.
- The code is MIT licensed.
- HLS live and real 4K/HEVC streams were not tested; the mock provider serves MPEG-TS live and H.264 VOD only.
- Full XMLTV guides and cross-device sync remain out of scope. TMDB discovery was brought into scope by the owner interview and is implemented in 0.2.0; it requires a private token entered on each installation.
- Returning from the Fire TV Home screen goes to the browse screen rather than the player. The launcher brings the main activity's task to the front, and live TV is deliberately not restarted.
