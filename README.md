# Ahmed Home TV for Fire TV

Planning a lightweight native IPTV app for Fire TV, carrying the dark ink and sage visual identity of [Ahmed Home TV](https://tv.ahmedhome.uk).

**Status: planning only.** There is no Android project, application code, build workflow or APK yet. The documents describe a proposed implementation and unmeasured acceptance targets.

The primary device is the **Fire TV Stick 4K, 2nd generation**, running Fire OS 8. The priorities are reliable playback, predictable remote navigation, a clear interface on a 4K television, and low resource use.

## Read the plan

- [Product, architecture and delivery plan](docs/PLAN.md)
- [UI/UX brief, screen layouts and remote behaviour](docs/UX-BRIEF.md)
- [Performance targets and release acceptance](docs/ACCEPTANCE.md)

Proposed foundation: Kotlin, Compose for TV, AndroidX Media3/ExoPlayer, Room and a small native networking layer. Provider access is entered by the user; the app is independent of the existing website's deployment.

The UI brief applies [Impeccable's planning guidance](https://github.com/pbakaus/impeccable/blob/main/.agents/skills/impeccable/reference/shape.md). It preserves the existing brand while adapting the interactions to television.

This repository starts with fresh history and documentation only. Credentials, provider catalogues, signing material and the existing application's history are excluded. A code licence will be selected before application source is published.
