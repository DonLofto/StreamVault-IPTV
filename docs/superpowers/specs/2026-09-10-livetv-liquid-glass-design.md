# Specification: Live TV — Liquid Glass Redesign

**Status:** design approved in principle, awaiting spec review before implementation
**Scope:** the Live TV tab (`Routes.LIVE_TV`) of StreamVault. One surface, done to completion.
**Related:** `docs/superpowers/specs/2026-09-05-apple-liquid-glass-tvos27-design.md` (the app-wide Liquid Glass system this builds on)

---

## 1. Summary

The Live TV tab becomes a full-bleed, video-forward surface where the interface is a thin
functional layer over the broadcast. The video occupies the whole screen, uncropped. Two
glass rails — categories and channels — float on the left, and fade away entirely when the
viewer stops interacting. Everything else the screen currently does (search, filters, source
switching, multi-view, category management) recedes behind a command bar and long-press
context menus, one key press away.

The aesthetic target is Liquid Glass as Apple actually ships it on tvOS, not "dark panels":
glass is a layer for **navigation and controls**, never for content; controls adopt glass
**on focus**; and the material must have something real to sit on.

### 1.1 Why this is not the whole app

A full sweep across ~90 UI files was considered and rejected as the first step. This app's
glass system already exists (`AppColors`, `GlassModifiers`, `AppMotion`, `AppShapes`) and is
applied ad-hoc in about ten files, while roughly fifteen major screens still render stock
`androidx.tv.material3` surfaces. Adding more primitives does not fix that. One perfected
surface exposes the real gaps — video-overlay legibility, focus pitch, material cost — while
the cost of changing direction is still a single screen.

---

## 2. Research basis

Apple's design language is Liquid Glass, introduced with **tvOS 26** (WWDC June 2025), not
tvOS 27. tvOS 27 (WWDC 8 June 2026; public beta 31 August 2026) is a minor update — a
redesigned Podcasts app, smoother launches, faster AirPlay, smart downloads, a Larger Text
accessibility option, a more responsive Control Center. **There is no new design language in
tvOS 27**, so the target is Liquid Glass *as refined in 2026*.

Four findings drive the design.

**Glass on TV is focus-triggered, not a surface treatment.**
> "In tvOS, Liquid Glass appears throughout navigation elements and system experiences such
> as Top Shelf and Control Center. Certain interface elements, like image views and buttons,
> adopt Liquid Glass when they gain focus."
> — [HIG: Materials](https://developer.apple.com/design/human-interface-guidelines/materials)

> "standard buttons and controls take on a Liquid Glass appearance when focus moves to them…
> Apple TV 4K (2nd generation) and newer models support Liquid Glass effects. On older
> devices, your app maintains its current appearance."
> — [Adopting Liquid Glass](https://developer.apple.com/documentation/technologyoverviews/adopting-liquid-glass)

**Do not overuse it.**
> "Avoid overusing Liquid Glass effects… Liquid Glass seeks to bring attention to the
> underlying content, and overusing this material in multiple custom controls can provide a
> subpar user experience by distracting from that content. Limit these effects to the most
> important functional elements in your app."

**Legibility is the 2026 fix, and it is the failure mode to avoid.**
> "the first implementation was very bad. When text in a tab or button overlaid text in the
> content beneath it, both were rendered extremely hard to read."
> — [9to5Mac on iOS 27's Liquid Glass changes](https://9to5mac.com/2026/06/12/ios-27-fixes-liquid-glass-and-not-just-with-a-slider/)

**tvOS specifics** ([Layout](https://developer.apple.com/design/human-interface-guidelines/layout), [Typography](https://developer.apple.com/design/human-interface-guidelines/typography), [Color](https://developer.apple.com/design/human-interface-guidelines/color), [Motion](https://developer.apple.com/design/human-interface-guidelines/motion)):
- Safe area: 60 pt top/bottom, 80 pt sides — the app already meets or exceeds this.
- Type: default 29 pt, minimum **23 pt**. Type Title 1 is 76/96.
- "Avoid using only color to indicate focus. Subtle scaling and responsive animation are the
  primary ways to denote interactivity when an element is in focus."
- Grids must be spaced so a focused item never overlaps its neighbours.
- "Add motion purposefully… Don't add motion for the sake of adding motion."

Not reproducible: tvOS's layered parallax with gyroscope-driven specular shine. Android TV
remotes do not expose comparable motion sensors. An animated highlight sweep is an
approximation, not an equivalent, and the spec does not claim otherwise.

---

## 3. Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Hero surface is the **Live TV tab** | The app's core loop |
| D2 | **Video-forward**: video is the canvas, not a result of interaction | The defining tvOS move |
| D3 | **Select-to-swap**: first press changes the canvas channel, second press on the same row commits to the fullscreen player | Preserves the deliberate two-step commit; no accidental fullscreen |
| D4 | Video is **full-bleed**, both rails float on the left over it | A half-width pane filling a 16:9 source crops ~50% of the horizontal field; full-bleed is exactly 16:9 and uncropped |
| D5 | Rails **auto-hide** after idle | Content becomes the hero; the app disappears |
| D6 | **Everything recedes, nothing is removed**: command bar + long-press menus | Minimal screen without deleting features |
| D7 | Build by **extracting first, then restyling** | The current screen is a ~1,000-line composable |
| D8 | Video pane entry state is **idle**, not auto-connect; resume only via reverse handoff | Passing through the tab must not burn a provider connection |
| D9 | Live source switcher lives in the command bar | Setup-time concern, not permanent chrome |

**Superseded:** an earlier arrangement (categories rail + channels rail beside a bounded
right-hand video pane, cropped to fill) was chosen and then replaced by D4. It is recorded
here only so the trail is not confusing: filling a 50%-wide, full-height pane with a 16:9
source shows roughly 960 of 1920 horizontal pixels, losing about half the field of view.

---

## 4. Architecture and unit boundaries

### 4.1 Package rename

`ui/screens/home/` currently holds the **Live TV** tab — `HomeScreen.kt` is
`Routes.LIVE_TV` — while the actual Home tab lives in `ui/screens/dashboard/`. This trap costs
every future reader time, so it goes first, as a purely mechanical commit:

| From | To |
|---|---|
| `ui/screens/home/HomeScreen.kt` | `ui/screens/livetv/LiveTvScreen.kt` |
| `ui/screens/home/HomeViewModel.kt` | `ui/screens/livetv/LiveTvViewModel.kt` |
| `ui/screens/home/HomeSidebarComponents.kt` | `ui/screens/livetv/LiveTvRailComponents.kt` |
| `ui/screens/home/HomeScreenDialogs.kt` | `ui/screens/livetv/LiveTvDialogs.kt` |
| `test/.../screens/home/HomeViewModelTest.kt` | `test/.../screens/livetv/LiveTvViewModelTest.kt` |

### 4.2 Extracted units

Each unit has one job, a narrow interface, and can be previewed and tested on its own.

| Unit | Owns | Depends on |
|---|---|---|
| `LiveTvScreen` | ViewModel wiring, navigation callbacks, dialog host, snackbar. No layout. | all units below |
| `LiveTvRails` | The left composition, rail widths, rail focus groups, arrangement | category + channel rails |
| `LiveTvCategoryRail` | Category rows, long-press anchor, quick-filter access | `LiveTvContextMenu` |
| `LiveTvChannelRail` | Channel rows, pagination, reorder mode | `LiveChannelRowCard` |
| `LiveTvVideoPane` | `PlayerRenderView`, now-playing card, idle/connecting/error/locked states, fit/fill | preview `PlayerEngine` |
| `LiveTvCommandBar` | Search, quick filters, source switcher, multi-view | — |
| `LiveTvContextMenu` | One long-press menu for category *and* channel actions | — |

### 4.3 Explicitly untouched

- `LivePreviewHandoffManager` and preview `PlayerEngine` ownership
- `Media3PlayerEngine`, decoder/render selection, SurfaceView/TextureView policy, timeshift
- `RemoteShortcutDispatch` / `LiveBrowseRemoteShortcutHandler` (already extracted)
- `AppScreenScaffold` — **13 consumers**; additive changes only
- Public signatures of `LiveChannelRowCard` / `LiveChannelRowSurface` (internals only)

### 4.4 Sequencing

1. **Rename** — mechanical, no behaviour change.
2. **Extract** — no visual change at all. Verified by `LiveTvViewModelTest`,
   `LivePreviewHandoffManagerTest`, the golden suite, and an emulator pass.
3. **Restyle** — unit by unit, each with its own golden updates.

**Primary risk:** focus restore and remote-key handling span the whole screen today.
Extraction must preserve `FocusRequester` ownership and every `LaunchedEffect` restore path.
This is where a silent regression would hide, which is why step 2 changes no pixels.

---

## 5. Visual system and glass allocation

1. **Glass is a functional layer.** Rails and command bar (navigation) are glass always.
   Rows, chips and buttons (controls) are glass **only when focused**. Logos, artwork and
   video (content) are **never** glass.
2. **Rows are transparent at rest.** Today every row carries `GlassThin` plus a
   `SpecularRestingBrush` border. A resting row becomes logo and text only — no fill, no
   border. This is the largest single visual change.
3. **Focus is motion first** — spring scale, glass pill, specular rim. Colour never carries
   focus on its own.
4. **Concentric corners** — inner radius derived as outer − padding, not the current fixed
   token per nesting level.
5. **Type floor** — nothing below 12sp on this screen; channel rows use `bodyMedium` (14sp) and
   titles keep the existing 20–34sp tokens. Apple's tvOS minimum of 23 pt maps to ~11.5dp. The
   scale bottoms out at `labelSmall` = 11sp, and **21 call sites hard-code a font size below
   12sp** (33 in the 10–13sp range); these are audited during the restyle.
6. **One palette**, brand colour used sparingly; live/success/warning accents retained.

---

## 6. Layout and composition

**Canvas.** Full-bleed video, `PlayerSurfaceResizeMode.FIT` by default — for 16:9 content on a
16:9 screen that is uncropped and bar-free. Obsidian (`AppColors.Canvas`) shows only before a
stream attaches. The fit/fill toggle remains **for 4:3 SD channels**, where FIT pillarboxes and
FILL crops; it is not needed to make 16:9 content large.

**Rails.** Categories and channels float on the left, inset from the screen edges, 24dp
radius, glass over the picture. Starting widths, to be validated on device against a 960dp-wide
canvas: **144dp** categories, **268dp** channels, with floors of 120dp and 240dp. Together they
occlude roughly the left third; the right two-thirds of the broadcast is always clean.

**Auto-hide.** After `CHROME_HIDE_DELAY_MS = 4_000` with no D-pad input *and* no rail item (or
the now-playing card) holding focus, the rails, command bar and now-playing card fade out
together. Any input restores them and returns
focus to the previously focused item. **Hiding never occurs while a rail item holds focus** —
hiding the focused element would strand a remote user. Restoring focus returns to the last
focused item, never to index 0.

**Now-playing card.** Bottom-right: channel name, current programme, position bar. Appears
and fades with the rails; never permanent.

**Command bar.** Top edge, auto-hiding: search, quick filters, source switcher, multi-view.
The picture dims behind it while open. This is where "everything recedes" lands.

**Focus order.** Up/Down within a rail; Right moves categories → channels → the now-playing
card, whose primary action is **Watch fullscreen** (equivalent to the second Select, and the
only focusable element to the right of the rails). Select previews into the canvas; Select
again on the same row commits to the fullscreen player with all its overlays intact; Back
restores the rails. The command bar opens on the remote's SEARCH key where present, otherwise
D-pad Up from the top of either rail, and closes on Back or Down.

**States.** Idle (no stream: channel logo, "Select a channel"), connecting (name + indicator
over the last frame), playing, error (message + Retry), locked (PIN prompt).

---

## 7. Motion and focus

1. **One vocabulary, three specs.** `AppMotion` currently carries `Fast(120)`,
   `Standard(160)`, `Emphasis(180)`, a `tween(160) FocusSpec` with **zero callers**, and
   `SpringFocusSpec`, referenced by only three files. Replace with:
   - `FocusSpring` — scale and rim on focus (`dampingRatio = 0.8f`, `stiffness = 380f`)
   - `ChromeSpring` — rails, command bar, now-playing card (200 ms fade + 4dp translate, no
     bounce on exit)
   - `ContentCrossfade` — non-video content only
   Unused motion tokens are removed; dead tokens are how a design system drifts.
2. **Draw-phase only.** Focus scale and alpha run inside `graphicsLayer`; focus changes never
   recompose. Only three files use `graphicsLayer` today.
3. **Reserved focus pitch.** Rows scale 1.055 and the rail spaces them by
   `channelListSpacing` — 2dp in PRO (52dp rows) and COMPACT (54dp), 8dp in COMFORTABLE (92dp).
   A focused row grows `rowHeight × (scale − 1) / 2` per side: **1.43dp** in PRO, **1.49dp** in
   COMPACT. That leaves only ~0.5dp of clearance, so it does **not** overlap today — but any
   increase in row height, which this redesign introduces following Apple's guidance that lists
   and forms get larger row height and padding, would cause it. Rule: rail spacing reserves
   `rowHeight × (scale − 1) / 2 + 4dp` (≈5.4dp in PRO), applied *before* row heights change.
4. **No crossfade on stream swap.** The video cuts hard and the connecting state covers the
   gap. Crossfading two live streams means double-decoding or a black frame.
5. **Accessibility.** Honour the system animation scale: when
   `ANIMATOR_DURATION_SCALE == 0`, focus changes are instant with no spring. Add an
   app-level **Reduce transparency** toggle mirroring Apple's, which swaps glass for flat
   fills and doubles as the LOW-tier switch — one mechanism, not two.

---

## 8. Platform constraints, device tiers, performance

### 8.1 Android cannot blur the video behind the rails

`Media3PlayerEngine` defaults to `SURFACE_VIEW` (`_renderSurfaceType`, line 277) and Fire TV
live HLS is forced to it (`shouldForceSurfaceViewForFireTvLiveHls()`, line 1702). A
`SurfaceView` is composited by SurfaceFlinger in its own hardware layer: the app can draw
translucently *over* it, but **cannot read those pixels**, so it cannot blur them.
`Modifier.blur` and `RenderEffect` operate on a layer's **own content**, not its backdrop —
neither is a `backdrop-filter` equivalent. There is no blur anywhere in the codebase today;
the existing "glass" is alpha fills plus gradient borders.

The only route to true refraction is `TEXTURE_VIEW`, which the engine already treats as a
known-bad fallback and overrides on Fire TV, and which would mean touching the decoder/render
path. **Not worth it for a visual effect. Simulate the glass; do not touch the render
pipeline.**

**The bloom, however, can be real.** Derive the ambient bloom behind the rails from the
**channel's logo/poster artwork** rather than the video. Artwork is a static bitmap the app
*can* read, so it can be blurred once per channel change and cached — genuine soft,
colour-matched light behind the rails at effectively zero per-frame cost. This is what makes
the material read as glass rather than grey panels.

### 8.2 Device tiers

Mirrors Apple shipping Liquid Glass only on Apple TV 4K (2nd generation) and newer.

| Tier | Detection | Material | Motion |
|---|---|---|---|
| HIGH | API 31+, not low-RAM | scrim + gradient rim + cached artwork bloom | full springs |
| MID | API 29–30 | scrim + gradient rim, no bloom | full springs |
| LOW | `isLowRamDevice` (Fire TV Stick, 192MB) | flat opaque fills, no translucency over video | instant focus, no spring |

LOW rendering opaque is not a compromise: it is perfectly legible over video and removes the
compositing cost entirely.

### 8.3 Draw budget

No more than **two** translucent layers stacked over the video at any time (scrim + rim; rows
render inside the scrim). Alpha compositing over a `SurfaceView` layer is fill-rate work, and
the rails always sit over a 1080p video layer.

---

## 9. Validation

1. **Unit** — `LiveTvViewModelTest` plus new tests for anything extracted from the composable.
2. **Golden** — regenerate `live_channel_row_surface` and `browse_hero_panel`; add goldens for
   rails-at-rest, rails-focused, command-bar-open, and the LOW-tier flat variant.
3. **Live TV on the emulator**, per `AGENTS.md`, **with the rails visible** (worst case for
   compositing): 61 screenshots at 2 s intervals, unique-hash count, media session `PLAYING`
   with `error=null`, no fatal error, no stuck-player timeout, no unintended MPEG-TS fallback.
4. **Two channels minimum**, because this change composites over the player surface.
5. **Jank** — `adb shell dumpsys gfxinfo <pkg> framestats` before and after, same channel and
   duration, while zapping with the rails visible.

---

## 10. Out of scope

- Any other screen (Home/dashboard, Movies, Series, EPG, Settings, Provider setup, dialogs)
- `Media3PlayerEngine`, decoder/render selection, SurfaceView/TextureView policy, timeshift
- `AppScreenScaffold` restructuring (13 consumers)
- The fullscreen player's own chrome, beyond guaranteeing the handoff still works
- Gyroscope parallax (not reproducible on Android TV)

---

## 11. File reference

| Path | Lines | Role |
|---|---|---|
| `ui/screens/home/HomeScreen.kt` | 1428 | Live TV tab; `fun HomeScreen(` at line 150 |
| `ui/screens/home/HomeViewModel.kt` | 2054 | State and preview ownership |
| `ui/screens/home/HomeSidebarComponents.kt` | — | `LivePreviewPane`, `CategoryItem`, `ReorderSidePanel` |
| `ui/screens/home/HomeScreenDialogs.kt` | — | `HomeDialogsHost` |
| `ui/components/shell/AppMediaCards.kt` | — | `LiveChannelRowCard` (102), `LiveChannelRowSurface` (227) — private to this tab |
| `ui/components/shell/AppShell.kt` | 921 | `AppScreenScaffold` (13 consumers) |
| `ui/design/GlassModifiers.kt` | 84 | Specular brushes, `liquidGlassSurface`, `liquidGlassBorder` |
| `ui/design/AppColors.kt` | 54 | Canvas `#050608`, glass tiers, rims, type colours |
| `ui/design/AppMotion.kt` | 23 | Motion tokens to be replaced |
| `ui/design/FocusSpec.kt` | 10 | `FocusedScale 1.055`, `PressedScale 0.97` |
| `ui/design/AppSpacing.kt` | 24 | `cardGap 16`, `chipGap 10`, safe areas, `railWidth 124` |
| `ui/model/LiveTvChannelMode.kt` | — | `COMFORTABLE/COMPACT/PRO`, defaults to PRO |
| `app/player/LivePreviewHandoffManager.kt` | — | Forward and reverse handoff, HOME/GUIDE sources |
| `player/src/main/.../Media3PlayerEngine.kt` | — | Surface type selection; not to be modified |
