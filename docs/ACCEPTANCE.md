# Performance and release acceptance

Proposed gates · 19 October 2026 · **No hardware gate below has been run or passed yet.** Emulator and unit-test evidence for the first release is recorded in [STATUS.md](STATUS.md).

## Measurement conditions

Primary device: Fire TV Stick 4K 2nd generation/AFTKM, 2 GB RAM and Fire OS 8. User-reported starting version: 8.1.8.2. Record OS version, app commit/build, UI surface metrics, TV output mode, network conditions, codec/bitrate and available storage with every result. Use a release/profileable build for timing and memory comparisons; debug builds help diagnosis but do not establish release performance.

Confirmed household display: 55-inch Philips Ambilight 4K. Prioritise the owner's two OWNtv failure scenarios: choosing content during library sync, and live football exiting unexpectedly to Home. They are required regression scenarios for this app, not a diagnosis of OWNtv's cause.

Separate local application timings from provider/network timings. Use repeatable synthetic catalogues and a controlled media source for comparable benchmarks, then repeat the essential playback paths with representative privately configured provider content. A source/network failure is recorded separately, while the app must still remain responsive and recover safely.

## Initial engineering budgets

| Measure | Proposed target | Measurement / qualification |
| --- | --- | --- |
| Cached cold launch | ≤2.5 s to interactive Home at p95 over 20 launches | Separate first sync and provider response times; include focus readiness. |
| D-pad feedback | ≤100 ms input-to-visible focus at p95 | Check with frame/input traces or high-frame-rate recording on hardware. |
| Browse animation | p95 frame duration ≤20 ms at 60 Hz; <1% frames over 50 ms | Release build, rapid traversal of warm and cold artwork rows. Investigate missed frames rather than averaging them away. |
| Local search | ≤200 ms at p95 after the 300 ms typing debounce | Synthetic 100,000-record catalogue; exclude keyboard entry and network sync. Include worst useful common queries and zero matches. |
| Controlled stream start | p95 ≤3 s live, ≤4 s VOD | Measure OK-to-first-frame over 20 trials on a controlled LAN source. Record real-provider timings separately. |
| App process PSS | Aim ≤180 MiB browsing; ≤300 MiB during 4K playback | Initial project budgets measured with dumpsys/profiler; include steady and peak usage. Decoder/system allocations are tracked separately, so PSS alone is not the whole-device footprint. |
| Retained growth | After warm-up, ≤10% increase in median settled PSS across the 100-switch run | Sample after equivalent idle/settle periods; no sustained upward trend in heap, native, graphics or decoder allocations. |
| Image cache | Start with 24 MiB memory, 128 MiB disk | Decode to slot size, load visible content plus at most one nearby row, reduce under memory pressure. Track graphics separately. |
| Media buffer | Start with 32 MiB byte target; evaluate up to 64 MiB for high-bitrate 4K | Configure actual byte/time loading policy and measure peak; duration alone does not bound memory. Validate that Media3 respects the intended policy in this release. |
| Catalogue cache | Soft target ≤100 MiB for 100,000 synthetic entries | Size depends on real variants and metadata; bound retained generations and prune stale details without deleting user state. Report actual size. |
| Release APK | Aim ≤30 MiB for the primary-device artifact | Measure signed release with shrinking/resource optimization. No embedded catalogue/video or large software codec engine. |

These are project hypotheses, not claims derived from a library choice. Establish the baseline in the first playable slice. If a budget is unrealistic, record the measured reason and agree a revised limit before expanding scope. Android's [TV memory guidance](https://developer.android.com/training/tv/playback/memory) uses its own allocation categories; do not treat its totals as interchangeable with app PSS.

## Required hardware scenarios

| Scenario | Pass condition |
| --- | --- |
| Start watching during refresh | Cached channels/titles remain reachable. Opening playback cancels bulk sync and discovery work; video plays independently. Returning safely restarts the interrupted catalogue without erasing favourites or progress. No crash, ANR or stuck refresh state. |
| Household discovery and subtitles | Real weekly trend data and separate new Korean/Chinese films/series resolve only to correct provider titles. Cached/offline/invalid-token states stay navigable. Foreign audio selects supplied English subtitles; manual off stays off for that stream, with honest unavailable-track feedback. |
| Four-hour live soak | No app crash, ANR or OS low-memory kill attributable to the app. UI controls remain responsive; stalls recover or become an actionable finite error. Record dropped frames, reconnects and thermal/memory observations. |
| Two-hour VOD including 4K | Correct hardware decoder, stable A/V sync, seek and resume work, subtitle/track changes do not leak resources. Use representative supported codecs and both SDR/HDR where available. |
| 100 channel switches | Sequential and rapid selections, including cancellation during prepare; only latest selection plays, at most one provider stream remains active, memory settles within the growth budget. |
| 50 browse/detail/player cycles | Return to the same title/rail/query/season without losing focus; no growing retained activity, surface, player or request count. |
| 20 Home/sleep/wake cycles | No background playback or continuing provider connections, no duplicate player after return; resume is explicit and progress is retained. |
| Network outage and provider failure | Unplug/disconnect/reconnect, slow source, 401/403, 404, invalid playlists, interrupted segments, missing EPG and malformed JSON. One 15-second recovery deadline includes pending sockets and retry delays; Leave and remote Back close playback during recovery. No freeze or infinite spinner. |
| Unsupported stream | Unsupported codec/profile/audio or invalid content produces a clear message and compatible-edition route if available; no uncontrolled software decoding or retry loop. |
| Large library and interrupted refresh | 100,000 synthetic records, many editions, long/mixed-language titles and missing images. UI stays usable; restart/cancel preserves the last complete generation and user state. |
| Process death and low storage | Restore account safely and return to a valid screen. A failed database/cache write produces recoverable feedback; no catalogue reset loop. |
| 1080p and 4K output | Readable at normal seating distance, safe edges, unclipped focus rings, stable subtitles, correct video aspect ratio. Record actual UI render size separately from HDMI mode. |
| Device after app exit | Stream/decoder resources released; Fire TV Home and other apps remain responsive. Inspect whole-device pressure if the launcher still slows down. |
| Install and upgrade | A signed APK installs on the 32-bit primary device; update over the preceding signed build retains favourites/progress/settings and does not require clearing data. |

## Functional and visual review

Complete setup → favourite channel → play → Back; search → movie edition → play → seek → resume; series → season/episode → play → next episode; and save → My List → remove, using only D-pad, OK, Back and Play/Pause. Verify the same paths with loading, empty and failure states. Optional Menu/voice shortcuts never carry a required action.

Verify every destination, filter, grid edge, options panel, track selector and error action is reachable with a visible focus state. Async results must not steal focus, panel dismissal restores the invoker, and a removed result has a predictable fallback. Test long labels, enlarged fonts, contrast, VoiceView and Arabic/English content. Compare actual native screenshots with the approved ink/sage reference at all shipped output modes.

Automated coverage should focus on grouping/variant identity, account isolation, atomic refresh, search completeness, cancellation and bounded retries, playback lifecycle, progress and focus restoration. Use emulator tests for breadth and hardware for decoding, input latency, memory, sleep and thermal behaviour. Synthetic tests must contain no real provider data. A passing emulator suite does not replace the hardware soak.

## Release evidence

Keep private device logs and provider-bearing screenshots out of the public repository. Publish only sanitized findings with the tested commit, device/OS, scenario, conditions, pass/fail, measured timings/peaks and unresolved limitations. Verify diagnostic exports redact secrets in stream URLs, query strings and image requests before offering export.

A release is ready when required scenarios pass, no known reproducible app crash/ANR/focus trap remains in the matrix, resource growth is bounded, and the signed install/update path works. This planning task creates no test results, APK, CI workflow, release or device changes.
