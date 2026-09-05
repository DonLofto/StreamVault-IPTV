# Specification: Apple "Liquid Glass" & tvOS 27 Minimalist Design System

## 1. Executive Vision

StreamVault-IPTV is being transformed from a traditional dark-navy/solid-box interface into a high-end, ultra-minimalist **tvOS 27** experience. The core aesthetic is inspired by Apple's **Liquid Glass** design language (introduced at WWDC 2025 across tvOS, visionOS, and macOS Tahoe).

The interface is treated as an array of **floating optical glass lenses** suspended over a deep obsidian canvas. UI chrome recedes into near-invisibility ("Deference"), allowing live broadcast streams, movie poster art, and channel iconography to be the hero elements.

---

## 2. Core Design Pillars

```
┌──────────────────────────────────────────────────────────────────────────┐
│                             tvOS 27 PILLARS                              │
├──────────────────────────────────────────────────────────────────────────┤
│ 1. CONTENT-FIRST DEFERENCE                                               │
│    Background is a pure obsidian void (#050608). All chrome recedes.     │
│    Active media artwork casts a soft, blurred ambient bloom.             │
├──────────────────────────────────────────────────────────────────────────┤
│ 2. LIQUID GLASS METAMATERIALS                                            │
│    Translucent, multi-tiered frosted glass sheets with controlled        │
│    alpha layering instead of opaque blue/gray solid containers.          │
├──────────────────────────────────────────────────────────────────────────┤
│ 3. SPECULAR RIM LIGHTING (BEVEL PHYSICS)                                 │
│    No harsh, thick 3–4dp colored borders. Focused and resting elements   │
│    feature a 0.75–1.0dp luminous hairline gradient catching top light.  │
├──────────────────────────────────────────────────────────────────────────┤
│ 4. LIVING FOCUS ENGINE (SPRING DYNAMICS)                                 │
│    Items physically lift off the canvas (1.06x–1.08x) with fluid spring  │
│    physics, internal illumination bloom, and ambient soft drop shadows.  │
├──────────────────────────────────────────────────────────────────────────┤
│ 5. FLOATING CAPSULE NAVIGATION                                           │
│    Navigation bars and rails are detached, floating glass pills with     │
│    squircle geometry (continuous corner curvature).                      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Metamaterial & Color Architecture (`AppColors.kt`)

### 3.1 Obsidian Canvas & Optical Layers
Replace opaque navy solids (`0xFF07111B`, `0xFF0F1B29`, `0xFF162338`) with neutral obsidian and calibrated optical alpha tiers:

| Token | Current Value | Apple Liquid Glass Value | Description / Usage |
| :--- | :--- | :--- | :--- |
| `Canvas` | `#07111B` (Navy) | `#050608` | Deepest obsidian void; infinite black on OLED |
| `CanvasElevated` | `#0B1622` | `#090B10` | Elevated canvas beneath scrolling content |
| `GlassUltraThin` | *None* | `Color(0x0A FFFFFF)` (~4% White) | Resting card backgrounds, rail tracks |
| `GlassThin` | *None* | `Color(0x14 FFFFFF)` (~8% White) | Unfocused live channel rows, chips |
| `GlassRegular` | `#0F1B29` | `Color(0x22 1A202C)` (~65% Tint) | Floating sidebars, floating top bar, panels |
| `GlassThick` | `#162338` | `Color(0x38 181E2B)` (~75% Tint) | Modals, dialogs, player HUD pods |
| `FocusGlass` | `#F4F8FF` (Border) | `Color(0xF5 FFFFFF)` (~96% White)| Focused button/chip pills with inverted black text |
| `FocusCardSurface` | `#1D2E46` | `Color(0x2E FFFFFF)` (~18% White)| Focused channel row / card surface fill |
| `SpecularRimResting` | *None* | `Color(0x1F FFFFFF)` (~12% White)| 0.75dp resting glass bevel highlight |
| `SpecularRimFocused` | *None* | Gradient: `White 60% → White 15%` | 1dp directional light catch on focused cards |
| `AmbientBloom` | *None* | `Color(0x26 FFFFFF)` (~15% White)| Soft diffused ambient glow behind focused cards |

### 3.2 High-Contrast Vibrancy Typography
- `TextPrimary`: `Color(0xFFF7F9FC)` (Crisp frosted white, 97% luminance).
- `TextSecondary`: `Color(0x9E FFFFFF)` (62% white; clean secondary information).
- `TextTertiary`: `Color(0x61 FFFFFF)` (38% white; subtle metadata, durations).
- `TextInverted`: `Color(0xFF07080A)` (Obsidian black; used when pills become milky white on focus).

---

## 4. Geometry & Focus Motion Specs (`AppShapes.kt`, `FocusSpec.kt`, `AppMotion.kt`)

### 4.1 Squircle Geometry
tvOS utilizes continuous curvature (squircles) to avoid the jarring look of circular arc corners:
- `Small`: `14.dp` (Badges, small status pills)
- `Medium`: `18.dp` (Channel rows, cards, buttons)
- `Large`: `24.dp` (Category sidebar, floating dialogs, player HUD)
- `Pill`: `999.dp` (Top navigation capsule, action buttons, filter chips)

### 4.2 Spring Physics
Replace linear duration tweens (`tween(160)`) with physically-modeled springs:
- `SpringDamping`: `0.76f` (Slight, natural tactile bounce without wobble)
- `SpringStiffness`: `380f` (Snappy, instantaneous response on remote clicks)
- `FocusedScale`: `1.055f` (Noticeable elevation without obscuring neighboring cards)
- `PressedScale`: `0.97f` (Tactile compression on D-pad SELECT down)

### 4.3 Hairline Specular Edge Physics
Abolish `CardBorderWidth = 4.dp` and `BorderWidth = 3.dp` solid lines.
All focusable items use a directional gradient stroke:
```kotlin
val SpecularFocusBrush = Brush.linearGradient(
    0.0f to Color.White.copy(alpha = 0.65f),
    0.5f to Color.White.copy(alpha = 0.15f),
    1.0f to Color.White.copy(alpha = 0.35f),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
)
```

---

## 5. Shell & Screen Transformations

### 5.1 Floating Glass Top Navigation (`AppShell.kt`)
- Center-aligned floating pill (`RoundedCornerShape(999.dp)`).
- Floating `20.dp` down from the top edge with `10.dp` margin.
- Translucent dark glass backdrop (`GlassRegular`) with `0.75.dp` specular rim.
- Focused tab morphs into a luminous milky-white pill (`Color.White`), inverting icon and text to crisp black (`#050608`).

### 5.2 Category Sidebar & Quick Filters (`HomeScreen.kt`)
- Sidebar floats as an independent translucent pane with `24.dp` rounded corners.
- Quick filter chips are glass micro-pills (`GlassThin`) with subtle border highlights.
- Selected category item uses a smooth luminous glass indicator rather than a harsh solid accent bar.

### 5.3 Live Channel Rows (`AppMediaCards.kt` / `Cards.kt`)
- Elimination of heavy status boxes.
- Row background is `GlassThin` with `18.dp` corners.
- On focus:
  - Scale animates smoothly to `1.055x` via spring.
  - Surface fills with `Color.White.copy(alpha = 0.12f)` glass shimmer.
  - Directional `1.dp` specular rim highlight illuminates.
  - Star / Favorite badge is minimalist monochrome or amber dot.

### 5.4 Video Player Chrome (`PlayerSystemOverlays.kt`)
- Bottom controls HUD floats as a rounded glass pod (`RoundedCornerShape(28.dp)`).
- Scrub bar track is a sleek glass canal with a luminous white playhead dot.
- Quick audio/subtitle selectors float as translucent glass sheets with subtle rim highlights.

---

## 6. Android TV Performance Architecture (60fps Guarantee)

1. **Dual-Tier Composition**:
   - **Baseline (API 29–35)**: Pure hardware-accelerated Compose draw passes using semi-transparent color fills, gradient brush borders, and `graphicsLayer` elevation. Zero GPU shader recompilation overhead.
   - **Enhanced (API 31+ Android 12+)**: Optional `Modifier.graphicsLayer { renderEffect = ... }` on high-end chipsets for full modal dialog backdrops, disabled by default during 4K video playback to prevent dropped frames.
2. **Draw-Phase Hoisting**:
   - All specular edge highlight calculations use pre-allocated static brushes.
   - Recomposition is skipped; scale and elevation execute purely in `graphicsLayer` blocks.
