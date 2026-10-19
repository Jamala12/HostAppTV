# TV UI/UX brief

Draft design intent · 19 October 2026 · No interface has been implemented or rendered.

## Job, scene and visual authority

The viewer is seated several metres from a television, often in a dim room, holding a Fire TV remote. The primary task is finding and watching a channel, movie or episode with little effort. Treat browsing, setup and playback controls as Impeccable **Operate** surfaces: clear tasks and familiar TV behaviour lead; the brand supplies palette, typography and restrained detail.

Inherit the identity already present in the website's `src/App.css` and `src/components/Brand.jsx`: Ahmed Home / TV & Cinema wordmark, asymmetric outlined play emblem, dark green/ink surfaces, sage controls, warm text, occasional editorial serif headings, and poster-led content. Preserve that look while replacing mouse/hover behaviour with visible D-pad focus. Phone bottom navigation, tiny desktop filters and hover-only actions do not fit this device.

| Role | Existing source value | TV intention |
| --- | --- | --- |
| Canvas | `#0B1013` | Main background |
| Surface | `#0E1518` | Navigation rail and panels |
| Raised surface | `#17221E` | Secondary controls and bounded content regions |
| Selected surface | `#24352D` | Active navigation destination |
| Text | `#EEF1ED` | Primary labels and body |
| Muted text | `#9CACA1` | Secondary facts; verify contrast at shipping sizes |
| Sage primary | `#CBDCAC` | Main actions, selected controls |
| On primary | `#172217` | Text on sage buttons |
| Focus | `#D3E5B1` | High-contrast focus border |
| Divider | `#26302F` | Quiet structural separation |
| Live / error | `#8DE0C1` / `#FFB5BD` | Semantic status plus icon/text |

Map these to semantic theme roles rather than scattering raw values through components. Use a bundled, licensed serif only for short display headings if needed to match the site's Georgia-led character; use the platform sans serif for controls, metadata and body. Keep headings readable with Arabic/fallback glyphs. Do not assume Georgia is installed on Fire OS. No runtime font download.

## 4K television layout

Design for the viewing distance and actual Android window density. Video output can be 3840×2160 while the app UI renders at another resolution. Do not force a 4K UI buffer to obtain 4K playback; query window/display metrics, use dp/sp and vector icons, and resize artwork to each actual slot. [Amazon display guidance](https://developer.amazon.com/docs/fire-tv/display-and-layout.html) distinguishes rendered UI and output resolution. Validate the resulting sharpness on the actual television.

Use a **960×540 dp reference composition**, adapting to the reported window rather than assuming this fixed density. Keep primary controls and important text within a 5% safe inset: 48×27 reference dp, equivalent to 96×54 pixels at 1080p or 192×108 pixels at 4K. Decorative backgrounds and video may bleed to the edges. [Amazon's TV design guidance](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html) provides the overscan recommendation.

Initial proposed sizing: 112 dp navigation rail inside the safe region, 20–24 dp work-area gaps, 8 dp spacing rhythm, 28–34 sp display headings, 20–24 sp section headings, 18 sp primary controls/body and 14–16 sp secondary facts. Aim for 44–48 dp control height. Fit roughly five readable poster cards across the remaining width, with a sliver of the next item on horizontal rails. These values need real-device and font-scale validation; reduce visible density before shrinking essential text.

A quiet branded welcome region anchors Home; do not spend the whole viewport on a promotional hero. Keep Continue Watching or Favourite Channels visible immediately. The signature treatment is the existing sage play emblem and a crisp sage focus frame, with a compact facts area that updates for the focused title without playing video.

## Screen topology

Persistent left navigation: **Search, Home, Live TV, Movies, Series, My List**. Settings sits at the bottom. Show text labels with icons; the current destination remains visibly selected when focus moves into content. Keep the rail stable rather than shifting the whole layout as it expands/collapses.

Conceptual Home layout, not a pixel-perfect wireframe:

```text
5% safe inset
┌──────────────┬─────────────────────────────────────────────────┐
│ Ahmed Home   │ Home                                Search      │
│ TV & Cinema  │ Your next watch · selected title facts          │
│              │                                                 │
│ Search       │ Continue Watching                               │
│ Home         │ [Resume film] [Resume episode] [See all]        │
│ Live TV      │                                                 │
│ Movies       │ Favourite Channels                              │
│ Series       │ [Channel] [Channel] [Channel] [See all]          │
│ My List      │                                                 │
│              │ Recently Added                                  │
│ Settings     │ [Poster] [Poster] [Poster] [Poster] …            │
└──────────────┴─────────────────────────────────────────────────┘
```

| Screen | Hierarchy and primary action |
| --- | --- |
| Setup | Brand, short connection explanation, server/username/password fields, Remember choice and Connect. Use the system keyboard; password hidden by default. Show password is an accessible control. Preserve entered values after a connection error. |
| Home | Small welcome/selection context, Continue Watching, Favourite Channels, Recently Added. Omit irrelevant empty rails while keeping a clear route to browse. |
| Live TV | Category selector, vertical channel list, selected-channel programme facts. OK starts full-screen playback. EPG absence says “Programme information unavailable”; highlight alone opens no stream. |
| Movies / Series | Heading and result count, Search/Filter/Sort actions, virtualized poster grid. Filters open a focus-contained side panel; apply/cancel returns to the invoking control and resets only the affected result position. |
| Search | Query field and recent local searches, scope chips, coverage/loading status, grouped or scoped results. Input never loses focus when results arrive. No mandatory voice integration. |
| Movie details | Poster, title/year/runtime, short synopsis, selected language/quality, Play/Resume and My List. An edition selector is visible when alternatives exist. Expand synopsis on request. |
| Series details | Shared title information, Resume next to season selector, vertical episode list with number/title/runtime/progress. Fetch one season's visible content; select the provider's actual episode. |
| Player | Full-screen video; transient title/now-next at the top, transport and audio/subtitle controls at the bottom. A player error gives Retry, available alternatives and Back. |
| Settings / About | Grouped rows with clear values; refresh and cache actions give progress/feedback. Explain what each clearing action removes; preserve favourites/progress when clearing images. Include attribution when corresponding metadata/assets are used. |

Use one focus target per poster. Save, editions and related actions belong in details or a clearly discoverable options panel; do not make each card several tiny focus stops. The Menu key may open shortcuts, but every essential action also has a visible route without that key or a long press.

## Remote and focus contract proposed for approval

| Input / situation | Result |
| --- | --- |
| Cold launch | Saved account opens Home; first useful Resume/favourite action receives focus, otherwise Browse. New account focuses the server field. |
| Up / Down | Move between rails or items in a vertical list; remember each rail's last focused item. Maintain a predictable column across rows. |
| Left / Right | Move through a row/grid. At its left boundary, Left reaches the rail or owning category column. Do not wrap from the last item to the first. |
| Navigation rail | Up/Down changes focus; OK activates the destination. Right returns to its last content position. Focus alone does not change destinations or trigger a catalogue reload. |
| OK | Activate exactly once. Live channels play; movie/series posters open details; Continue Watching resumes directly. |
| Back in a panel | Close the panel, restore its invoking control and retain unapplied/committed state according to Cancel/Apply. |
| Back from details/player | Restore the previous screen, query/filter/season, scroll position and stable content ID. Removed items fall back to the nearest surviving item. |
| Back at a top-level browse screen | Move to its selected navigation item; Back again on that top-level rail leaves the app through normal Android behaviour. Do not trap exit behind a repeating confirmation. |
| Play/Pause | Control video while playing; no effect that starts hidden playback from browsing. |
| OK in full-screen playback | Reveal controls, focusing Play/Pause. With controls visible, activate the focused control. Auto-hide only while playing and without active interaction. |
| Left/Right with controls hidden | VOD uses a visible seek preview, initially ±10 seconds; commit with OK and cancel with Back. Live without a seekable window does not pretend to seek. |
| Back in playback | Close track panel/seek preview first, then hide visible transport controls; with no overlay, stop/release and return. Paused state never auto-hides its main controls. |
| Home / sleep | Let Fire OS take over; stop/release media and save position. Restore browsing/playback context on return without unexpectedly restarting live playback. |

Maintain separate **focused**, **selected**, **pressed** and **disabled** states. Focus uses a 2 dp border plus a restrained surface/scale change of at most about 1.03; reserve space so it is not clipped. Limit feedback motion to roughly 120–160 ms and respect disabled animations. Do not animate blur, glow or a full-screen background on every D-pad movement. Follow [Android TV's focus model](https://developer.android.com/design/ui/tv/guides/styles/focus-system) and [navigation guidance](https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv).

## States, content ranges and accessibility

Design loading, empty, stale, disconnected, expired-account, unsupported-format and failed-artwork states as part of each screen. Keep focus on a real action while data changes. A skeleton is never focusable; loading must not reset the rail or swallow Back. Use a textual artwork fallback with the title rather than a broken-image symbol.

Plan for zero, one, and 100,000 catalogue records in synthetic stress data; this is a test range, not a measured provider size. Keep long titles to two lines on cards, with the full title in the selected context/details. Support large episode lists and up to 100 provider editions without loading every detail/artwork item at once. Distinguish unknown language/quality rather than guessing.

Provide semantic names, roles and state for VoiceView, including progress and playback controls. Keep reading order aligned with focus order, verify colour contrast, test enlarged text and mixed Arabic/English titles, and preserve standard media directions when text is RTL. Do not rely on colour alone to distinguish the active destination, errors or LIVE state.

Before implementation, compare authenticated website captures with these extracted tokens. Before release, capture actual Fire TV/emulator screens and test the full focus/error matrix on hardware. A browser rendering of this brief would not verify native TV navigation or performance.
