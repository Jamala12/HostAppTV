# Product

<!-- impeccable:product-schema 1 -->

Owner interview completed on 19 October 2026. This record combines the owner's confirmed requirements with the existing implementation and engineering constraints. Anything still unconfirmed is explicitly labelled as an assumption. Where older planning documents defer trending discovery, the owner's requirements below take precedence.

## Platform

android

## Stack

Kotlin, Compose for TV (tv-material), AndroidX Media3/ExoPlayer, Room with Paging, DataStore, OkHttp, Coil. These were chosen in docs/PLAN.md and implemented in the first release.

## Users

One household sharing a single Fire TV Stick 4K (2nd generation, AFTKM, Fire OS 8.1.8.2), connected to a 55-inch Philips Ambilight 4K television. No other television is currently a target.

- The owner, his dad and his brother watch live football.
- His sister watches Korean dramas, Chinese dramas and other series.
- The family watches movies together; household members also watch movies and series individually.
- Everyone shares one setup, favourites and viewing progress. Separate profiles are a possible later addition if the family asks for them.

Assumption: viewers sit at normal television viewing distance and primarily use the Fire TV remote's D-pad, OK, Back and Play/Pause buttons. Viewing distance and room lighting have not been confirmed.

## Product Purpose

A personal Xtream Codes IPTV player replacing OWNtv. The owner reports failures when trying to watch while OWNtv syncs, and live football crashing back to the home screen. The stick can become so unusable that the family switches to the television's own software. The cause of those failures has not been diagnosed, and which home screen appears has not been confirmed.

The app must do three things best: avoid crashes, make new and trending media easy to discover, and provide smooth playback and navigation. Success includes usable viewing during library refresh, dependable live football, and leaving the Fire TV responsive after use. Stability must be demonstrated through testing, not promised as a guarantee.

## Positioning

A native TV app with a clean, Netflix-like browsing experience and the same Ahmed Home colour palette as the owner's other websites and apps. It brings live football, family films and series, and Korean/Chinese discovery into one shared household app.

The existing implementation connects directly to the user's Xtream provider and does not require the tv.ahmedhome.uk website, its proxy or its login for provider playback. Assumption: preserve that independence when adding discovery.

## Operating Context

- The user enters provider details on the TV with the system keyboard.
- Many Xtream providers serve only HTTP. Unencrypted access is used only after the user explicitly allows it.
- Catalogues can be very large, and some providers return a whole catalogue in one response.
- Users browse with the remote and play full screen. Pressing Home or letting the TV sleep must stop playback.
- English-language media should use English audio. Foreign-language media should use English subtitles when supplied. Assumption: keep the original audio for foreign-language media unless the viewer chooses another track.
- Library refresh must not block watching or make playback and navigation unstable. Keep already-synced content usable while refresh proceeds.

## Capabilities and Constraints

- First-release scope covers setup, Home, Live TV, Movies, Series, Search, My List, the player and Settings, as described in docs/PLAN.md.
- Discovery is a core requirement for this release: general trending films and series, plus Korean and Chinese sections covering both new and trending shows and movies. It is no longer deferred.
- Trending labels must come from a real trend source; recently added provider content is a separate concept. Assumption: use TMDB for trend metadata, match discovery to the provider's playable titles, and clearly explain missing metadata or subtitle availability instead of inventing it.
- The minimum Android version is API 30. The app must run on the stick's 32-bit ARM application ABI and has no Google Play Services dependency.
- Only one player and one provider stream may be open at a time. Automatic retries are limited.
- Provider and metadata credentials must stay out of Git, logs, navigation state and Android device backups.
- Confirmed out of scope for now: separate profiles, recording, downloads and cloud sync. Everyone shares favourites and progress; revisit profiles only if requested.
- Assumption: timeshift and Appstore publication remain outside this release.
- Existing code licence: MIT. Release package: `uk.ahmedhome.tv`, as specified by the owner.

## Brand Commitments

- Ahmed Home / TV & Cinema wordmark with the asymmetric outlined play emblem.
- The palette tokens in docs/UX-BRIEF.md: canvas #0B1013, sage #CBDCAC, focus #D3E5B1, and the rest of that table.
- An editorial serif for short headings only. The Georgia-like OFL font Gelasio is bundled.
- Confirmed direction: clean Netflix-like UI/UX using the existing colour palette. Assumption: interpret this as poster-led browsing, clear content rows, readable selected-title information and predictable remote focus; no autoplay previews or heavy decorative effects.

## Evidence on Hand

- docs/UX-BRIEF.md: the colour tokens and screen topology.
- The web app's Brand component, which supplied the emblem geometry.
- Authenticated captures of the live website do not exist yet. No real provider catalogue or stream examples are stored in this repository.
- The owner interview above establishes the household, television, viewing interests, OWNtv failure scenarios, priorities, language preferences and shared-profile decision. Existing emulator evidence does not establish performance or stability on the household's Fire TV Stick.

## Product Principles

1. Avoid crashes and keep the whole Fire TV usable, including while syncing and watching live football.
2. Make discovery useful: real trending films and series, plus new and trending Korean and Chinese shows and movies.
3. Keep playback and navigation smooth. Focus is visible, Back works predictably, and browsing does not start hidden playback.
4. State what the app knows, handle unavailable streams or subtitles honestly, and release resources when they are no longer needed.

## Accessibility & Inclusion

- Confirmed household need: English subtitles for foreign-language media, with a discoverable subtitle selector and an honest unavailable state.
- Assumption: retain multilingual search and robust Unicode handling for English, Korean, Chinese and other provider titles; Arabic is not a confirmed household language requirement.
- Assumption: keep VoiceView labels, readable television-sized text, support for enlarged text, and states that do not rely on colour alone. No additional individual accessibility needs were raised in the interview.
