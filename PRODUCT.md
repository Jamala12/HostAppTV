# Product

<!-- impeccable:product-schema 1 -->

Inferred from README.md and docs/PLAN.md, docs/UX-BRIEF.md and docs/ACCEPTANCE.md. The owner said to proceed without an interview on 19 October 2026, so nothing below was confirmed separately. Treat any item not stated in those documents as an assumption.

## Platform

android

## Stack

Kotlin, Compose for TV (tv-material), AndroidX Media3/ExoPlayer, Room with Paging, DataStore, OkHttp, Coil. These were chosen in docs/PLAN.md and implemented in the first release.

## Users

One household watching on a Fire TV Stick 4K (2nd generation, AFTKM, Fire OS 8.1.8.2). Viewers sit several metres from a television, often in a dim room, and use only the Fire TV remote's D-pad, OK, Back and Play/Pause buttons. They want to start a familiar channel, movie or episode with few button presses.

## Product Purpose

A personal Xtream Codes IPTV player that replaces an existing player reported to crash and slow down the stick. It succeeds when playback is dependable, recovers cleanly from bad streams and connection loss, and leaves the Fire TV responsive after viewing.

## Positioning

A native TV app with the Ahmed Home identity (dark ink surfaces with sage controls) that connects directly to the user's own provider. It does not depend on the tv.ahmedhome.uk website, its proxy or its login.

## Operating Context

- The user enters provider details on the TV with the system keyboard.
- Many Xtream providers serve only HTTP. Unencrypted access is used only after the user explicitly allows it.
- Catalogues can be very large, and some providers return a whole catalogue in one response.
- Users browse with the remote and play full screen. Pressing Home or letting the TV sleep must stop playback.

## Capabilities and Constraints

- First-release scope covers setup, Home, Live TV, Movies, Series, Search, My List, the player and Settings, as described in docs/PLAN.md.
- The minimum Android version is API 30. The app must run on the stick's 32-bit ARM application ABI and has no Google Play Services dependency.
- Only one player and one provider stream may be open at a time. Automatic retries are limited.
- Credentials must stay out of Git, logs, navigation state and backups.
- Out of scope for now: recording, timeshift, downloads, multi-profile, cloud sync, TMDB discovery and Appstore publication.
- Still to decide: the code licence and the final package name for a signed release.

## Brand Commitments

- Ahmed Home / TV & Cinema wordmark with the asymmetric outlined play emblem.
- The palette tokens in docs/UX-BRIEF.md: canvas #0B1013, sage #CBDCAC, focus #D3E5B1, and the rest of that table.
- An editorial serif for short headings only. The Georgia-like OFL font Gelasio is bundled.

## Evidence on Hand

- docs/UX-BRIEF.md: the colour tokens and screen topology.
- The web app's Brand component, which supplied the emblem geometry.
- Authenticated captures of the live website do not exist yet. No real provider catalogue or stream examples are stored in this repository.

## Product Principles

1. Playback dependability comes before feature breadth.
2. Every remote action is predictable: focus is visible, Back always works, and nothing plays from focus alone.
3. The app states what it knows and never invents ratings, trends or programme data.
4. The app holds resources only while it uses them and releases everything on exit.

## Accessibility & Inclusion

- Mixed Arabic and English titles are common, and search must handle accents and Arabic letter variants.
- Controls need readable labels for VoiceView.
- Large text must still fit, and state must never rely on colour alone.
