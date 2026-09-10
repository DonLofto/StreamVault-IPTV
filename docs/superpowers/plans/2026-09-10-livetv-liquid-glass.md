# Live TV Liquid Glass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Live TV tab as a full-bleed, video-forward surface where two glass rails float over the broadcast, chrome recedes when idle, and every existing feature remains reachable through a command bar and long-press menus.

**Architecture:** Three sequenced phases that never overlap. Phase A is a mechanical rename. Phase B is a behaviour-preserving extraction of the ~1,000-line `HomeScreen` composable into focused units, verified by the existing test suite with **zero** visual change. Phase C restyles those units on top of new, unit-tested primitives (motion vocabulary, focus pitch, UI tier, glass material, chrome visibility). Extraction is separated from restyling so that a regression can always be attributed to one of them, never both.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.tv.material3`, Hilt, Media3, JUnit4 + Mockito + Truth, Compose UI golden tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-10-livetv-liquid-glass-design.md`

## Global Constraints

Copied verbatim from the spec. Every task's requirements implicitly include this section.

- **Glass allocation:** navigation (rails, command bar) is glass always; controls (rows, chips, buttons) are glass **only when focused**; content (logos, artwork, video) is **never** glass.
- **Type floor:** nothing below 12sp on this screen; channel rows use `bodyMedium` (14sp); existing 20–34sp title tokens unchanged.
- **Focus scale:** `FocusSpec.FocusedScale = 1.055f`. Rail spacing must reserve `rowHeight × (scale − 1) / 2 + 4dp` (≈5.4dp at PRO's 52dp rows) **before** row heights change.
- **Motion:** `FocusSpring` = `spring(dampingRatio = 0.8f, stiffness = 380f)`; `ChromeSpring` = 200 ms fade + 4dp translate, no bounce on exit; `ContentCrossfade` for non-video content only. **No crossfade on stream swap.**
- **Chrome auto-hide:** `CHROME_HIDE_DELAY_MS = 4_000`. Never hide while a rail item or the now-playing card holds focus. Restoring returns focus to the last focused item, never index 0.
- **Rail widths:** 144dp categories, 268dp channels, floors 120dp / 240dp, validated against a 960dp-wide canvas.
- **Draw budget:** at most **two** translucent layers stacked over the video at any time.
- **Platform limit:** the video is a `SurfaceView`; its pixels cannot be read, so there is **no backdrop blur**. Ambient bloom derives from cached channel **artwork**, never from video frames.
- **Device tiers:** HIGH = API 31+ and not low-RAM → scrim + rim + bloom, full springs. MID = API 29–30 → scrim + rim, no bloom, full springs. LOW = `isLowRamDevice` → flat opaque fills, no translucency over video, no spring.
- **Reduced motion:** when `ANIMATOR_DURATION_SCALE == 0`, focus changes are instant with no spring.
- **Do not modify:** `Media3PlayerEngine`, decoder/render selection, the SurfaceView/TextureView policy, timeshift, `LivePreviewHandoffManager`, or the structure of `AppScreenScaffold` (13 consumers). `RemoteShortcutDispatch` is already extracted — leave it.
- **Commit hygiene:** conventional commits. Never reference Claude, Claude Code, or AI generation in commit messages. Never commit secrets or `.env` files.

## File Structure

**Phase A — rename (no content change):**

| From | To |
|---|---|
| `ui/screens/home/HomeScreen.kt` | `ui/screens/livetv/LiveTvScreen.kt` |
| `ui/screens/home/HomeViewModel.kt` | `ui/screens/livetv/LiveTvViewModel.kt` |
| `ui/screens/home/HomeSidebarComponents.kt` | `ui/screens/livetv/LiveTvRailComponents.kt` |
| `ui/screens/home/HomeScreenDialogs.kt` | `ui/screens/livetv/LiveTvDialogs.kt` |
| `test/.../screens/home/HomeViewModelTest.kt` | `test/.../screens/livetv/LiveTvViewModelTest.kt` |

**Phase B — extraction (new files):**

| File | Responsibility |
|---|---|
| `ui/screens/livetv/LiveTvVideoPane.kt` | Video surface, now-playing card, idle/connecting/error/locked states |
| `ui/screens/livetv/LiveTvRails.kt` | The left composition: rail widths, focus groups, arrangement |
| `ui/screens/livetv/LiveTvCommandBar.kt` | Search, quick filters, source switcher, multi-view |

**Phase C — new primitives (new files, unit-tested):**

| File | Responsibility |
|---|---|
| `ui/design/AppMotion.kt` *(modify)* | Three motion specs replacing five tokens |
| `ui/design/FocusPitch.kt` | `focusReservedSpacing()` — the overlap-prevention rule |
| `ui/design/UiTier.kt` | `UiTier` enum and `resolveUiTier()` |
| `ui/design/LiveTvGlass.kt` | `Modifier.liveTvGlass()` — tier-aware material |
| `ui/screens/livetv/ChromeVisibilityController.kt` | Auto-hide state machine + last-focused restoration |
| `ui/screens/livetv/ChannelArtworkBloom.kt` | Cached, blurred artwork-derived bloom |
| `ui/screens/livetv/LiveTvContextMenu.kt` | Long-press menu for category and channel actions |

---
## Phase A — Rename

### Task 1: Rename the Live TV package and types

Pure rename. No logic, no layout, no behaviour change. This exists because `ui/screens/home/HomeScreen.kt` is the **Live TV** tab (`Routes.LIVE_TV`) while the real Home tab lives in `ui/screens/dashboard/` — a trap that already cost one wasted design round.

**Files:**
- Move: `app/src/main/java/com/streamvault/app/ui/screens/home/HomeScreen.kt` → `.../livetv/LiveTvScreen.kt`
- Move: `.../home/HomeViewModel.kt` → `.../livetv/LiveTvViewModel.kt`
- Move: `.../home/HomeSidebarComponents.kt` → `.../livetv/LiveTvRailComponents.kt`
- Move: `.../home/HomeScreenDialogs.kt` → `.../livetv/LiveTvDialogs.kt`
- Move: `app/src/test/java/com/streamvault/app/ui/screens/home/HomeViewModelTest.kt` → `.../livetv/LiveTvViewModelTest.kt`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `com.streamvault.app.ui.screens.livetv.LiveTvScreen(onChannelClick, onNavigate, currentRoute, initialCategoryId)`, `...livetv.LiveTvViewModel`, `...livetv.LiveTvDialogsHost(...)`.

- [ ] **Step 1: Record a green baseline**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests '*HomeViewModelTest*' --tests '*LivePreviewHandoffManagerTest*' --console=plain
```
Expected: `BUILD SUCCESSFUL`. If this fails, stop — the tree is already broken and every later verification is meaningless.

- [ ] **Step 2: Move the files with git**

```bash
# Run from the repository root.
SRC=app/src/main/java/com/streamvault/app/ui/screens
mkdir -p "$SRC/livetv"
git mv "$SRC/home/HomeScreen.kt"            "$SRC/livetv/LiveTvScreen.kt"
git mv "$SRC/home/HomeViewModel.kt"         "$SRC/livetv/LiveTvViewModel.kt"
git mv "$SRC/home/HomeSidebarComponents.kt" "$SRC/livetv/LiveTvRailComponents.kt"
git mv "$SRC/home/HomeScreenDialogs.kt"     "$SRC/livetv/LiveTvDialogs.kt"
TST=app/src/test/java/com/streamvault/app/ui/screens
mkdir -p "$TST/livetv"
git mv "$TST/home/HomeViewModelTest.kt"     "$TST/livetv/LiveTvViewModelTest.kt"
```

- [ ] **Step 3: Update package declarations and type names**

In each moved file change the package line from `package com.streamvault.app.ui.screens.home` to `package com.streamvault.app.ui.screens.livetv`.

Then rename the public types, using the editor (never `sed -i` — BSD sed needs `-i ''` and litters backup files):

| Old | New | Where |
|---|---|---|
| `fun HomeScreen(` | `fun LiveTvScreen(` | `LiveTvScreen.kt` |
| `class HomeViewModel` | `class LiveTvViewModel` | `LiveTvViewModel.kt` |
| `data class HomeUiState` | `data class LiveTvUiState` | `LiveTvViewModel.kt` |
| `fun HomeDialogsHost(` | `fun LiveTvDialogsHost(` | `LiveTvDialogs.kt` |
| `class HomeViewModelTest` | `class LiveTvViewModelTest` | `LiveTvViewModelTest.kt` |

Also update the `HomeViewModel` constructor reference inside `LiveTvScreen.kt` line 155 (`viewModel: HomeViewModel = hiltViewModel()`) and every other in-file reference.

- [ ] **Step 4: Find and fix every remaining reference**

Run:
```bash
rg -n 'screens\.home|HomeScreen|HomeViewModel|HomeUiState|HomeDialogsHost' -g '*.kt' app/src
```
Expected: matches only in `AppNavigation.kt` (the import and the `LiveTvScreen(...)` call site) and in `LiveTvViewModel.kt` where `HomeUiState` was renamed. Fix each: update the import to `com.streamvault.app.ui.screens.livetv.LiveTvScreen`, and update the composable call inside `composable(Routes.LIVE_TV)`.

Re-run the command until it prints nothing.

- [ ] **Step 5: Compile and re-run the guards**

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests '*LiveTvViewModelTest*' --tests '*LivePreviewHandoffManagerTest*' --console=plain
```
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add -A app/src
git commit -m "refactor(livetv): rename home package to livetv and clarify naming"
```

---

## Phase B — Extraction (zero visual change)

Each task in this phase moves code. None of them changes a pixel, a colour, a size or a control-flow decision. If a test fails here, the move was wrong — never "fix" it by adjusting behaviour.

### Task 2: Extract `LiveTvVideoPane`

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/screens/livetv/LiveTvVideoPane.kt`
- Modify: `.../livetv/LiveTvRailComponents.kt` (remove `LivePreviewPane`)
- Modify: `.../livetv/LiveTvScreen.kt` (call site, currently ~line 1401)

**Interfaces:**
- Consumes: `PlayerEngine`, `PlayerRenderView`, `PlayerSurfaceResizeMode`, `AppColors.GlassRegular`, `SpecularRestingBrush`.
- Produces: `internal fun LiveTvVideoPane(channel: Channel?, playerEngine: PlayerEngine?, isLoading: Boolean, errorMessage: String?, modifier: Modifier = Modifier)` — signature identical to today's `LivePreviewPane`.

- [ ] **Step 1: Move the composable verbatim**

Cut `internal fun LivePreviewPane(...)` and its entire body from `LiveTvRailComponents.kt` and paste it into the new file under `package com.streamvault.app.ui.screens.livetv`, renaming it to `LiveTvVideoPane`. **Do not change a single line of its body** — same `Surface`, same `RoundedCornerShape(20.dp)`, same `GlassRegular`, same `aspectRatio(16f / 9f)`, same placeholder strings. Copy only the imports it needs.

- [ ] **Step 2: Update the call site**

In `LiveTvScreen.kt`, inside the `if (isProMode)` block (~line 1401), replace the `LivePreviewPane(` call with `LiveTvVideoPane(` and keep the argument list and `Modifier.weight(0.92f).fillMaxHeight()` unchanged. Add the import.

- [ ] **Step 3: Verify nothing moved behaviourally**

```bash
rg -n 'LivePreviewPane' -g '*.kt' app/src
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests '*LiveTvViewModelTest*' --console=plain
```
Expected: the `rg` prints nothing; both Gradle commands print `BUILD SUCCESSFUL`.

- [ ] **Step 4: Emulator smoke check**

Install and open the Live TV tab with `LiveTvChannelMode.PRO`; confirm the preview pane renders in the same position at the same size as before the move.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/screens/livetv
git commit -m "refactor(livetv): extract LiveTvVideoPane"
```

### Task 3: Extract `LiveTvCommandBar`

**Files:**
- Create: `.../livetv/LiveTvCommandBar.kt`
- Modify: `.../livetv/LiveTvScreen.kt`

**Interfaces:**
- Consumes: `ActiveLiveSourceOption` (`com.streamvault.domain.model`), `SearchInput` (`ui/components/SearchInput.kt`), `SelectionChipRow`.
- Produces: `internal fun LiveTvCommandBar(searchQuery: String, onSearchQueryChange: (String) -> Unit, sourceTitle: String, showSourceSwitcher: Boolean, sourceOptions: List<ActiveLiveSourceOption>, onSourceSelected: (ActiveLiveSourceOption) -> Unit, slotCount: Int, slotLimit: Int, onOpenMultiView: () -> Unit, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Identify the chrome to move**

In `LiveTvScreen.kt` these are currently interleaved with the rail layout: the category search field, the quick-filters drawer trigger and its `savedCategoryFilters` chips, the `SearchInput` for channels (width computed as `channelSearchWidth`, lines 182–192), the live source switcher (guarded by `shouldShowLiveSourceSwitcher`, line 169), and `CompactSplitLauncherButton` (line ~1077).

- [ ] **Step 2: Create the composable**

```kotlin
package com.streamvault.app.ui.screens.livetv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.streamvault.domain.model.ActiveLiveSourceOption

@Composable
internal fun LiveTvCommandBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    sourceTitle: String,
    showSourceSwitcher: Boolean,
    sourceOptions: List<ActiveLiveSourceOption>,
    onSourceSelected: (ActiveLiveSourceOption) -> Unit,
    slotCount: Int,
    slotLimit: Int,
    onOpenMultiView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth()) {
        // Body moved verbatim from LiveTvScreen.kt: the existing search input,
        // filter trigger, source switcher and CompactSplitLauncherButton, wired
        // to the parameters above instead of directly to uiState/viewModel.
    }
}
```

Move the existing composables into that `Row` **unchanged**, replacing direct `uiState`/`viewModel` reads with the parameters. Keep the same sizes and order.

- [ ] **Step 3: Update the call site and remove dead locals**

Replace the inlined chrome in `LiveTvScreen.kt` with a single `LiveTvCommandBar(...)` call passing `uiState.channelSearchQuery`, `viewModel::onChannelSearchQueryChange`, `uiState.activeLiveSourceTitle`, `shouldShowLiveSourceSwitcher`, `uiState.liveSourceOptions`, the existing source-selected handler, `uiState.multiviewChannelCount`, `uiState.multiviewSlotCapacity` and the existing `{ showSplitManagerDialog = true }`.

Delete `channelSearchWidth` if it is no longer referenced anywhere.

- [ ] **Step 4: Verify**

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests '*LiveTvViewModelTest*' --console=plain
```
Expected: `BUILD SUCCESSFUL`. Then on the emulator confirm the search field, filters, source label and multi-view button appear in their previous positions with unchanged sizes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/screens/livetv
git commit -m "refactor(livetv): extract LiveTvCommandBar"
```

### Task 4: Extract `LiveTvRails`

The highest-risk extraction in this plan: focus groups, reorder drag state, remote-key dispatch and focus restoration all live in the block being moved. Do this one last, and do not combine it with any restyle.

**Files:**
- Create: `.../livetv/LiveTvRails.kt`
- Modify: `.../livetv/LiveTvScreen.kt`

**Interfaces:**
- Consumes: `Category`, `Channel`, `LiveChannelRowCard`, `CategoryItem`, `ReorderSidePanel`, `FocusRestoreTarget`.
- Produces:
```kotlin
@Composable
internal fun LiveTvRails(
    categories: List<Category>,
    channels: List<Channel>,
    selectedCategory: Category?,
    previewChannelId: Long?,
    isReorderMode: Boolean,
    categorySearchQuery: String,
    canLoadMore: Boolean,
    isLoadingMore: Boolean,
    rowHeight: Dp,
    rowSpacing: Dp,
    onCategoryClick: (Category) -> Unit,
    onCategoryLongClick: (Category) -> Unit,
    onCategorySearchChange: (String) -> Unit,
    onChannelClick: (Channel) -> Unit,
    onChannelLongClick: (Channel) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- [ ] **Step 1: Move the rails Row**

In `LiveTvScreen.kt`, the `Row(modifier = Modifier.fillMaxSize())` at ~line 382 contains the categories `Column` (with `sidebarWidth`, `GlassRegular`, `RoundedCornerShape(24.dp)`, `SpecularRestingBrush` border, `FocusRestoreTarget` handling) and the channels `Column` at ~line 1024 (header, `channelRowHeight`, `channelListSpacing`, `LiveChannelRowCard` loop, load-more trigger). Move both into `LiveTvRails`.

- [ ] **Step 2: Preserve focus ownership exactly**

The `FocusRequester` instances, the `LaunchedEffect` focus-restore block at ~line 623, and `FocusRestoreHost` at ~line 650 must keep the same ownership and the same keys. If a `remember` that produced a requester moves into the child, it must move with its `LaunchedEffect` — splitting them silently breaks restoration.

- [ ] **Step 3: Verify behaviour is untouched**

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
```
Expected: `BUILD SUCCESSFUL` for the whole app test suite, not just one class.

- [ ] **Step 4: Emulator check — focus is the thing being tested**

On a TV emulator with a channel list: move down 10 rows, press Back to leave the tab, return, and confirm focus returns to the same row. Then enter reorder mode, drag a channel, exit, and confirm the order persisted. Then hold D-pad Down through the list and confirm no dropped or double-steps.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/screens/livetv
git commit -m "refactor(livetv): extract LiveTvRails"
```

---
## Phase C — Primitives (unit-tested)

### Task 5: Replace the motion vocabulary

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/design/AppMotion.kt`
- Modify: `ui/components/Cards.kt`, `ui/components/shell/AppMediaCards.kt`, `ui/components/shell/AppShell.kt`

**Interfaces:**
- Produces: `AppMotion.FocusSpring`, `AppMotion.ChromeSpring`, `AppMotion.ContentCrossfade`, `AppMotion.ChromeHideDelayMs`, `AppMotion.ChromeTranslate`.

- [ ] **Step 1: Replace the file contents**

```kotlin
package com.streamvault.app.ui.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

object AppMotion {
    /** Focus scale and specular rim. Replaces the tween-based FocusSpec. */
    val FocusSpring: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f)

    /** Rails, command bar, now-playing card: fade plus a 4dp translate, no bounce on exit. */
    val ChromeSpring: FiniteAnimationSpec<Float> =
        tween(durationMillis = 200, easing = LinearOutSlowInEasing)

    /** Non-video content only. Never used for a stream swap. */
    val ContentCrossfade: FiniteAnimationSpec<Float> =
        tween(durationMillis = 160, easing = LinearOutSlowInEasing)

    /** Idle delay before chrome auto-hides. */
    const val ChromeHideDelayMs = 4_000L

    /** Exit translate distance for chrome. */
    val ChromeTranslate = 4.dp
}
```

- [ ] **Step 2: Migrate the three call sites**

`Cards.kt`, `AppMediaCards.kt` and `AppShell.kt` reference `SpringFocusSpec`. Change each to `AppMotion.FocusSpring`. `AppMotion.FocusSpec` had **zero** callers — confirm with the next step rather than assuming.

- [ ] **Step 3: Confirm no dead tokens remain**

```bash
rg -n 'SpringFocusSpec|AppMotion\.FocusSpec|AppMotion\.Fast|AppMotion\.Standard|AppMotion\.Emphasis' -g '*.kt' app/src
```
Expected: no output. Any match is a missed call site — fix it before continuing.

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :app:compileDebugKotlin --console=plain
git add app/src/main/java/com/streamvault/app/ui
git commit -m "refactor(design): replace motion tokens with three named specs"
```

### Task 6: Focus pitch helper (TDD)

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/design/FocusPitch.kt`
- Test: `app/src/test/java/com/streamvault/app/ui/design/FocusPitchTest.kt`

**Interfaces:**
- Consumes: `FocusSpec.FocusedScale` (1.055f).
- Produces: `fun focusReservedSpacing(rowHeight: Dp, baseGap: Dp = 4.dp, scale: Float = FocusSpec.FocusedScale): Dp`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.streamvault.app.ui.design

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusPitchTest {

    @Test
    fun proRows_reserveGrowthPlusBaseGap() {
        // 52dp row at 1.055 scale grows 52 * 0.0275 = 1.43dp per side; + 4dp base gap.
        assertThat(focusReservedSpacing(rowHeight = 52.dp).value).isWithin(0.01f).of(5.43f)
    }

    @Test
    fun comfortableRows_reserveMoreBecauseTheyGrowMore() {
        // 92 * 0.0275 = 2.53dp per side; + 4dp base gap.
        assertThat(focusReservedSpacing(rowHeight = 92.dp).value).isWithin(0.01f).of(6.53f)
    }

    @Test
    fun spacingAlwaysExceedsFocusedGrowthPerSide() {
        listOf(52.dp, 54.dp, 92.dp).forEach { height ->
            val growthPerSide = height * ((FocusSpec.FocusedScale - 1f) / 2f)
            assertThat(focusReservedSpacing(height)).isGreaterThan(growthPerSide)
        }
    }

    @Test
    fun baseGapIsConfigurable() {
        assertThat(focusReservedSpacing(rowHeight = 52.dp, baseGap = 8.dp).value)
            .isWithin(0.01f).of(9.43f)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*FocusPitchTest*' --console=plain
```
Expected: compilation failure — `focusReservedSpacing` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.streamvault.app.ui.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Vertical spacing a rail must reserve so a focused row never overlaps its neighbour.
 *
 * A row scaled by [scale] grows `rowHeight * (scale - 1) / 2` on each side, so that is the
 * minimum gutter that keeps neighbours apart; [baseGap] adds a visible gutter at rest. Against
 * today's PRO metrics (52dp rows, 1.055 scale) this yields ~5.4dp where the rail currently
 * spaces rows by 2dp — apply it before row heights change, not after.
 */
fun focusReservedSpacing(
    rowHeight: Dp,
    baseGap: Dp = 4.dp,
    scale: Float = FocusSpec.FocusedScale,
): Dp = rowHeight * ((scale - 1f) / 2f) + baseGap
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*FocusPitchTest*' --console=plain
```
Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/design/FocusPitch.kt \
        app/src/test/java/com/streamvault/app/ui/design/FocusPitchTest.kt
git commit -m "feat(design): reserve rail spacing for focus growth"
```

### Task 7: UI tier resolver (TDD)

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/design/UiTier.kt`
- Test: `app/src/test/java/com/streamvault/app/ui/design/UiTierTest.kt`

**Interfaces:**
- Produces: `enum class UiTier { HIGH, MID, LOW }` and `fun resolveUiTier(apiLevel: Int, isLowRamDevice: Boolean): UiTier`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.streamvault.app.ui.design

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiTierTest {

    @Test
    fun lowRamDevice_isLowEvenOnNewestApi() {
        assertThat(resolveUiTier(apiLevel = 34, isLowRamDevice = true)).isEqualTo(UiTier.LOW)
    }

    @Test
    fun api31WithoutLowRam_isHigh() {
        assertThat(resolveUiTier(apiLevel = 31, isLowRamDevice = false)).isEqualTo(UiTier.HIGH)
    }

    @Test
    fun api29And30_areMid() {
        assertThat(resolveUiTier(apiLevel = 29, isLowRamDevice = false)).isEqualTo(UiTier.MID)
        assertThat(resolveUiTier(apiLevel = 30, isLowRamDevice = false)).isEqualTo(UiTier.MID)
    }

    @Test
    fun apiBelow29_isLow() {
        assertThat(resolveUiTier(apiLevel = 28, isLowRamDevice = false)).isEqualTo(UiTier.LOW)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*UiTierTest*' --console=plain
```
Expected: compilation failure — `resolveUiTier` and `UiTier` are unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.streamvault.app.ui.design

/**
 * Rendering capability tier, mirroring Apple shipping Liquid Glass only on Apple TV 4K
 * (2nd generation) and newer. LOW keeps the old flat appearance rather than degrading it.
 */
enum class UiTier { HIGH, MID, LOW }

fun resolveUiTier(apiLevel: Int, isLowRamDevice: Boolean): UiTier = when {
    isLowRamDevice || apiLevel < 29 -> UiTier.LOW
    apiLevel >= 31 -> UiTier.HIGH
    else -> UiTier.MID
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*UiTierTest*' --console=plain
```
Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/design/UiTier.kt \
        app/src/test/java/com/streamvault/app/ui/design/UiTierTest.kt
git commit -m "feat(design): add UI capability tier resolver"
```

### Task 8: Tier-aware glass material

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/design/LiveTvGlass.kt`
- Test: `app/src/androidTest/java/com/streamvault/app/ui/design/LiveTvGlassGoldenTest.kt`

**Interfaces:**
- Consumes: `UiTier`, `AppColors`, `SpecularFocusBrush`, `SpecularRestingBrush`, `assertAgainstGolden`.
- Produces: `fun Modifier.liveTvGlass(tier: UiTier, isFocused: Boolean, shape: Shape = RoundedCornerShape(24.dp)): Modifier`.

- [ ] **Step 1: Write the failing golden test**

```kotlin
package com.streamvault.app.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.test.assertAgainstGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveTvGlassGoldenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun lowTier_resting_matchesGolden() {
        composeRule.setContent {
            Box(Modifier.size(240.dp, 96.dp).testTag("golden")
                .liveTvGlass(tier = UiTier.LOW, isFocused = false))
        }
        composeRule.onNodeWithTag("golden").assertAgainstGolden("livetv_glass_low_resting")
    }

    @Test
    fun highTier_focused_matchesGolden() {
        composeRule.setContent {
            Box(Modifier.size(240.dp, 96.dp).testTag("golden")
                .liveTvGlass(tier = UiTier.HIGH, isFocused = true))
        }
        composeRule.onNodeWithTag("golden").assertAgainstGolden("livetv_glass_high_focused")
    }
}
```

Import `createComposeRule` the same way `ShellGoldenTest.kt` does — copy its imports verbatim rather than guessing.

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*LiveTvGlassGoldenTest*' --console=plain
```
Expected: compilation failure — `liveTvGlass` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.streamvault.app.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The Live TV material. Navigation is always glass; controls only when focused; content never.
 *
 * There is no backdrop blur: the video is a SurfaceView whose pixels cannot be read. On LOW the
 * fill is opaque, which is both cheaper and perfectly legible over moving video.
 */
fun Modifier.liveTvGlass(
    tier: UiTier,
    isFocused: Boolean,
    shape: Shape = RoundedCornerShape(24.dp),
): Modifier {
    val fill = when (tier) {
        UiTier.LOW -> AppColors.CanvasElevated
        UiTier.HIGH, UiTier.MID ->
            if (isFocused) AppColors.FocusCardSurface else AppColors.GlassRegular
    }
    val width = if (isFocused) 1.dp else 0.75.dp
    val brush = if (isFocused) SpecularFocusBrush else SpecularRestingBrush
    return clip(shape).background(fill).border(width, brush, shape)
}
```

- [ ] **Step 4: Generate the golden references, then re-run**

```bash
./gradlew :app:connectedDebugAndroidTest --tests '*LiveTvGlassGoldenTest*' \
  -PupdateGoldens=true --console=plain
./gradlew :app:connectedDebugAndroidTest --tests '*LiveTvGlassGoldenTest*' --console=plain
```
Expected: the first run writes the references; the second passes. If the repo has no `updateGoldens` flag, follow whatever mechanism `ShellGoldenTest` already uses to regenerate — check its README or gradle config rather than inventing one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/design/LiveTvGlass.kt \
        app/src/androidTest/java/com/streamvault/app/ui/design
git commit -m "feat(design): add tier-aware Live TV glass material"
```

### Task 9: Chrome visibility controller (TDD)

Owns the auto-hide rule and focus memory. Pure logic, no Compose — which is exactly why it gets a real unit test.

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/screens/livetv/ChromeVisibilityController.kt`
- Test: `app/src/test/java/com/streamvault/app/ui/screens/livetv/ChromeVisibilityControllerTest.kt`

**Interfaces:**
- Produces: `class ChromeVisibilityController(hideDelayMs: Long = 4_000L)` exposing `isVisible`, `lastFocusedItemKey`, `onInteraction(nowMs)`, `onFocusChanged(hasChromeFocus, nowMs)`, `onItemFocused(key)`, `onTick(nowMs, hasChromeFocus)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.streamvault.app.ui.screens.livetv

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChromeVisibilityControllerTest {

    @Test
    fun startsVisible() {
        assertThat(ChromeVisibilityController().isVisible).isTrue()
    }

    @Test
    fun hidesAfterTheDelayWhenIdleAndUnfocused() {
        val c = ChromeVisibilityController(hideDelayMs = 4_000L)
        c.onInteraction(nowMs = 0L)
        c.onTick(nowMs = 4_000L, hasChromeFocus = false)
        assertThat(c.isVisible).isFalse()
    }

    @Test
    fun staysVisibleBeforeTheDelayElapses() {
        val c = ChromeVisibilityController(hideDelayMs = 4_000L)
        c.onInteraction(nowMs = 0L)
        c.onTick(nowMs = 3_999L, hasChromeFocus = false)
        assertThat(c.isVisible).isTrue()
    }

    @Test
    fun neverHidesWhileAChromeItemHoldsFocus() {
        val c = ChromeVisibilityController(hideDelayMs = 4_000L)
        c.onInteraction(nowMs = 0L)
        c.onTick(nowMs = 60_000L, hasChromeFocus = true)
        assertThat(c.isVisible).isTrue()
    }

    @Test
    fun interactionAfterHidingRestoresVisibilityAndResetsTheTimer() {
        val c = ChromeVisibilityController(hideDelayMs = 4_000L)
        c.onInteraction(nowMs = 0L)
        c.onTick(nowMs = 10_000L, hasChromeFocus = false)
        assertThat(c.isVisible).isFalse()

        c.onInteraction(nowMs = 10_000L)
        assertThat(c.isVisible).isTrue()
        c.onTick(nowMs = 13_999L, hasChromeFocus = false)
        assertThat(c.isVisible).isTrue()
        c.onTick(nowMs = 14_000L, hasChromeFocus = false)
        assertThat(c.isVisible).isFalse()
    }

    @Test
    fun remembersTheLastFocusedItemRatherThanResettingToTheFirst() {
        val c = ChromeVisibilityController()
        assertThat(c.lastFocusedItemKey).isNull()
        c.onItemFocused("channel:42")
        c.onItemFocused("channel:77")
        assertThat(c.lastFocusedItemKey).isEqualTo("channel:77")
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChromeVisibilityControllerTest*' --console=plain
```
Expected: compilation failure — `ChromeVisibilityController` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.streamvault.app.ui.screens.livetv

/**
 * Owns the Live TV auto-hide decision.
 *
 * Chrome hides only when it is idle **and** nothing inside it holds focus: hiding the focused
 * element would strand a remote user with no visible cursor. [lastFocusedItemKey] lets the
 * caller restore the previously focused item rather than snapping back to the first row.
 */
class ChromeVisibilityController(
    private val hideDelayMs: Long = 4_000L,
) {
    var isVisible: Boolean = true
        private set

    var lastFocusedItemKey: String? = null
        private set

    private var lastInteractionMs: Long = 0L

    fun onInteraction(nowMs: Long) {
        lastInteractionMs = nowMs
        isVisible = true
    }

    fun onFocusChanged(hasChromeFocus: Boolean, nowMs: Long) {
        if (hasChromeFocus) {
            lastInteractionMs = nowMs
            isVisible = true
        }
    }

    fun onItemFocused(key: String) {
        lastFocusedItemKey = key
    }

    fun onTick(nowMs: Long, hasChromeFocus: Boolean) {
        if (hasChromeFocus) {
            isVisible = true
            return
        }
        if (nowMs - lastInteractionMs >= hideDelayMs) isVisible = false
    }
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChromeVisibilityControllerTest*' --console=plain
```
Expected: `BUILD SUCCESSFUL`, 6 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/screens/livetv/ChromeVisibilityController.kt \
        app/src/test/java/com/streamvault/app/ui/screens/livetv/ChromeVisibilityControllerTest.kt
git commit -m "feat(livetv): add chrome auto-hide controller with focus memory"
```

### Task 10: Channel artwork bloom cache (TDD)

Real blur is possible only for artwork — a static bitmap the app can read — never for the video.

**Files:**
- Create: `.../livetv/ChannelArtworkBloom.kt`
- Test: `app/src/test/java/com/streamvault/app/ui/screens/livetv/ChannelArtworkBloomTest.kt`

**Interfaces:**
- Produces: `class ChannelArtworkBloom(maxEntries: Int = 4)` exposing `get(channelId: Long): ImageBitmap?`, `put(channelId: Long, bitmap: ImageBitmap)`, `clear()`, `size: Int`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.streamvault.app.ui.screens.livetv

import androidx.compose.ui.graphics.ImageBitmap
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.mockito.kotlin.mock

class ChannelArtworkBloomTest {

    private fun bitmap(): ImageBitmap = mock()

    @Test
    fun returnsNullForAnUnknownChannel() {
        assertThat(ChannelArtworkBloom().get(1L)).isNull()
    }

    @Test
    fun returnsWhatWasStored() {
        val bloom = ChannelArtworkBloom()
        val image = bitmap()
        bloom.put(7L, image)
        assertThat(bloom.get(7L)).isSameInstanceAs(image)
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntryBeyondCapacity() {
        val bloom = ChannelArtworkBloom(maxEntries = 2)
        bloom.put(1L, bitmap())
        bloom.put(2L, bitmap())
        bloom.get(1L)          // 1 becomes most-recently-used
        bloom.put(3L, bitmap()) // evicts 2
        assertThat(bloom.size).isEqualTo(2)
        assertThat(bloom.get(1L)).isNotNull()
        assertThat(bloom.get(2L)).isNull()
        assertThat(bloom.get(3L)).isNotNull()
    }

    @Test
    fun clearEmptiesTheCache() {
        val bloom = ChannelArtworkBloom()
        bloom.put(1L, bitmap())
        bloom.clear()
        assertThat(bloom.size).isEqualTo(0)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChannelArtworkBloomTest*' --console=plain
```
Expected: compilation failure — `ChannelArtworkBloom` is unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.streamvault.app.ui.screens.livetv

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Small LRU of blurred channel-artwork bitmaps used as the ambient bloom behind the rails.
 *
 * Bloom comes from artwork, never from video frames: the video is a SurfaceView and its pixels
 * cannot be read. Blurring is done once per channel change, not per frame.
 */
class ChannelArtworkBloom(private val maxEntries: Int = 4) {

    private val cache = object : LinkedHashMap<Long, ImageBitmap>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ImageBitmap>?): Boolean =
            size > maxEntries
    }

    val size: Int get() = cache.size

    fun get(channelId: Long): ImageBitmap? = cache[channelId]

    fun put(channelId: Long, bitmap: ImageBitmap) {
        cache[channelId] = bitmap
    }

    fun clear() = cache.clear()
}
```

- [ ] **Step 4: Run the test and watch it pass**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChannelArtworkBloomTest*' --console=plain
```
Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/screens/livetv/ChannelArtworkBloom.kt \
        app/src/test/java/com/streamvault/app/ui/screens/livetv/ChannelArtworkBloomTest.kt
git commit -m "feat(livetv): cache channel artwork bloom for the rail backdrop"
```

---
## Phase D — Restyle

Phase D changes pixels. Every task here can be rejected or revised on its own without touching its neighbours.

### Task 11: Full-bleed video and the UI tier

**Files:**
- Modify: `.../livetv/LiveTvScreen.kt`
- Modify: `.../livetv/LiveTvVideoPane.kt`
- Create: `.../ui/design/LocalUiTier.kt`

**Interfaces:**
- Consumes: `resolveUiTier`, `UiTier`.
- Produces: `val LocalUiTier = staticCompositionLocalOf { UiTier.MID }`.

- [ ] **Step 1: Publish the tier**

```kotlin
package com.streamvault.app.ui.design

import androidx.compose.runtime.staticCompositionLocalOf

val LocalUiTier = staticCompositionLocalOf { UiTier.MID }
```

In `StreamVaultTheme` (`ui/theme/Theme.kt`), provide it from the application context:

```kotlin
val context = LocalContext.current
val activityManager = remember(context) {
    context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
}
val tier = remember { resolveUiTier(Build.VERSION.SDK_INT, activityManager.isLowRamDevice) }
CompositionLocalProvider(LocalUiTier provides tier) { /* existing content */ }
```

- [ ] **Step 2: Promote the video to the canvas**

Restructure the screen so the video is a background layer rather than a sibling of the rails:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    LiveTvVideoPane(
        channel = previewChannel,
        playerEngine = uiState.previewPlayerEngine,
        isLoading = uiState.isPreviewLoading,
        errorMessage = uiState.previewErrorMessage,
        modifier = Modifier.fillMaxSize(),
    )
    LiveTvRails(/* ... */)
    AnimatedVisibility(visible = chromeController.isVisible) { LiveTvCommandBar(/* ... */) }
}
```

Delete the `isProMode` split weights (`1.08f` / `0.92f`) — with one full-bleed canvas they no longer apply. Keep `isProMode` only if another behaviour still reads it; check with `rg -n 'isProMode' app/src/main` before deleting the local.

- [ ] **Step 3: Default the pane to FIT and expose the toggle**

In `LiveTvVideoPane`, change `resizeMode = PlayerSurfaceResizeMode.FIT` to read a parameter `resizeMode: PlayerSurfaceResizeMode = PlayerSurfaceResizeMode.FIT`. Before writing this, run `rg -n 'enum class PlayerSurfaceResizeMode' -A 6 -g '*.kt' app player` and use the enum's **actual** members — if there is no FILL member, add the toggle to the enum in the same commit rather than inventing a parallel type.

- [ ] **Step 4: Verify**

```bash
./gradlew :app:compileDebugKotlin --console=plain
```
Then on the emulator: a 16:9 channel fills the screen with no bars and no crop; a 4:3 channel shows pillarboxes with FIT.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app
git commit -m "feat(livetv): promote video to full-bleed canvas and wire UI tier"
```

### Task 12: Rail restyle — transparent rows at rest

**Files:**
- Modify: `ui/components/shell/AppMediaCards.kt` (`LiveChannelRowSurface`, line 227)
- Modify: `.../livetv/LiveTvRails.kt`
- Modify: `.../livetv/LiveTvRailComponents.kt`

**Interfaces:**
- Consumes: `Modifier.liveTvGlass`, `focusReservedSpacing`, `LocalUiTier`, `AppMotion.FocusSpring`.

- [ ] **Step 1: Make resting rows transparent**

In `LiveChannelRowSurface`, remove the resting `GlassThin` fill and the resting `SpecularRestingBrush` border. A resting row keeps only its logo and text. On focus, apply `Modifier.liveTvGlass(tier = tier, isFocused = true)` with the row's existing shape.

Do **not** change the composable's public signature — it is shared with `LiveChannelRowCard`.

- [ ] **Step 2: Apply reserved pitch to the rail**

In `LiveTvRails`, replace the channel list's `Arrangement.spacedBy(rowSpacing)` with `Arrangement.spacedBy(focusReservedSpacing(rowHeight = rowHeight))`. PRO today is `52dp` rows at `2dp` spacing; the helper returns ≈`5.43dp`, so this is a visible change — check it on the emulator rather than trusting the arithmetic. `rowSpacing` then has no callers: remove it from the `LiveTvRails` signature introduced in Task 4 and drop the argument at its call site in `LiveTvScreen.kt`.

- [ ] **Step 3: Rails become glass, controls do not**

Apply `Modifier.liveTvGlass(tier = tier, isFocused = false)` to the two rail containers, replacing the hardcoded `background(AppColors.GlassRegular, RoundedCornerShape(24.dp))` + `border(0.75.dp, SpecularRestingBrush, ...)` pair, so tier handling lives in one place.

- [ ] **Step 4: Concentric corners**

Where a shape sits inside another, derive it: `val inner = (outerRadius - padding).coerceAtLeast(0.dp)`. Start with the row shape inside the rail: a rail with 24dp corners and 10dp padding gives rows `24 - 10 = 14dp`.

- [ ] **Step 5: Bloom behind the rails (HIGH tier only)**

Blur the focused channel's artwork once per channel change into `ChannelArtworkBloom` and draw it as a soft backdrop behind the rails. Skip the work entirely for `UiTier.MID` and `UiTier.LOW` — the cache is never populated on those tiers, so nothing renders. Blur the **artwork bitmap only**, never a video frame: the video is a `SurfaceView` and its pixels cannot be read.

- [ ] **Step 6: Verify**

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:connectedDebugAndroidTest --tests '*ShellGoldenTest*' --console=plain
```
`live_channel_row_surface` **will** fail — that is the expected, intended consequence of a deliberate visual change. Regenerate it in Task 17, not here, and confirm by eye that the new reference looks right.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui
git commit -m "feat(livetv): glass rails with transparent rows at rest"
```

### Task 13: Wire auto-hide and the focus map

**Files:**
- Modify: `.../livetv/LiveTvScreen.kt`

**Interfaces:**
- Consumes: `ChromeVisibilityController`, `AppMotion.ChromeSpring`, `AppMotion.ChromeHideDelayMs`.

- [ ] **Step 1: Drive the controller**

```kotlin
val chromeController = remember { ChromeVisibilityController(AppMotion.ChromeHideDelayMs) }
val hasChromeFocus by remember { derivedStateOf { focusedItemKey != null } }

LaunchedEffect(Unit) {
    while (true) {
        withFrameMillis { }
        chromeController.onTick(SystemClock.uptimeMillis(), hasChromeFocus)
    }
}
```

Track `focusedItemKey` from each rail item's `onFocusChanged`, calling `chromeController.onItemFocused(key)` as well.

- [ ] **Step 2: Animate visibility**

Wrap rails, command bar and now-playing card in `AnimatedVisibility` driven by `chromeController.isVisible`, using `ChromeSpring` for alpha and a `4.dp` offset via `AppMotion.ChromeTranslate`.

- [ ] **Step 3: Restore the last focused item**

On reappearance, restore with `chromeController.lastFocusedItemKey`; when it is `null` (nothing focused yet) focus the first rail item. **Never** hardcode index 0 as the restore target.

- [ ] **Step 4: Verify**

```bash
./gradlew :app:testDebugUnitTest --tests '*ChromeVisibilityControllerTest*' --console=plain
```
On the emulator: leave the remote alone for 5 s with no focus in the rails and confirm everything fades; press Down and confirm it returns with focus on the previously focused row, not the top of the list. Then hold focus on a row for 10 s and confirm nothing ever fades.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/streamvault/app/ui/screens/livetv
git commit -m "feat(livetv): auto-hide chrome and restore last focus"
```

### Task 14: Command bar keys and focus map

**Files:**
- Modify: `.../livetv/LiveTvScreen.kt`, `.../livetv/LiveTvCommandBar.kt`

- [ ] **Step 1: Open and close**

Bind `Key.Search` (where the remote sends it) and D-pad Up from the top of either rail to open the command bar and move focus into it. Back or D-pad Down closes it and returns focus to the rail using the same restoration path as Task 13.

- [ ] **Step 2: Right of the rails**

D-pad Right from the channels rail focuses the now-playing card. Its primary action is **Watch fullscreen**, calling the same handler the second `Select` press already uses in `LiveChannelRowCard`'s `onClick` (the `beginPreviewHandoff` branch). Do not invent a second commit path.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :app:compileDebugKotlin --console=plain
git add app/src/main/java/com/streamvault/app/ui/screens/livetv
git commit -m "feat(livetv): command bar keys and rail focus map"
```
On the emulator confirm: Up from the top row opens the bar; Back closes it and returns focus to the same row; Right from the channels rail lands on the now-playing card; its action goes fullscreen.

### Task 15: Long-press context menu

**Files:**
- Create: `.../livetv/LiveTvContextMenu.kt`
- Modify: `.../livetv/LiveTvRails.kt`

**Interfaces:**
- Consumes: `Category`, `Channel`, the existing `LiveTvDialogsHost` state flags.
- Produces: `internal fun LiveTvContextMenu(target: LiveTvContextTarget, onDismiss: () -> Unit)` with `sealed interface LiveTvContextTarget { data class CategoryTarget(val category: Category) : LiveTvContextTarget; data class ChannelTarget(val channel: Channel) : LiveTvContextTarget }`.

- [ ] **Step 1: Define the target type**

```kotlin
package com.streamvault.app.ui.screens.livetv

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel

sealed interface LiveTvContextTarget {
    data class CategoryTarget(val category: Category) : LiveTvContextTarget
    data class ChannelTarget(val channel: Channel) : LiveTvContextTarget
}
```

- [ ] **Step 2: Build the menu on the existing pattern**

Reuse the long-press flow already wired for channels (`onLongClick` → `viewModel.onShowDialog(channel)`, with `ignoreNextClick` guarding the click that follows). Add category actions — pin, hide, lock — by routing to the existing ViewModel calls `toggleCategoryPinned`, `hideCategory`, and the `pendingLockToggleCategory` path. **No new ViewModel methods.**

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --tests '*LiveTvViewModelTest*' --console=plain
git add app/src/main/java/com/streamvault/app/ui/screens/livetv
git commit -m "feat(livetv): long-press context menu for categories and channels"
```
On the emulator: long-press a category and pin it; long-press a channel and open its existing dialog; confirm a short press still previews rather than opening the menu.

### Task 16: Reduce transparency toggle

**Files:**
- Modify: `ui/theme/Theme.kt` and the settings screen's existing preference plumbing.

- [ ] **Step 1: Add the preference**

Reuse the existing preferences mechanism used for `liveTvChannelMode`. Add `reduceTransparency: Boolean` (default `false`).

- [ ] **Step 2: Make it force LOW material without changing tier detection**

In `Theme.kt`, provide `LocalUiTier` as `if (reduceTransparency) UiTier.LOW else resolveUiTier(...)`. This is deliberate: one switch drives both the accessibility setting and the LOW-tier appearance, so there is no second code path to keep correct.

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :app:compileDebugKotlin --console=plain
git add app/src/main/java/com/streamvault/app
git commit -m "feat(design): add reduce-transparency setting"
```
On the emulator toggle it and confirm the rails become opaque and legible over video.

---

## Phase E — Validation

### Task 17: Regenerate and extend the golden suite

**Files:**
- Modify: `app/src/androidTest/java/com/streamvault/app/ui/components/shell/ShellGoldenTest.kt`
- Add: goldens `livetv_rails_at_rest`, `livetv_rails_focused`, `livetv_command_bar_open`, `livetv_low_tier`

- [ ] **Step 1: Regenerate the two intentionally-changed references**

Regenerate `live_channel_row_surface` and `browse_hero_panel` using the same mechanism Task 8 Step 4 used. Inspect each new reference image yourself before accepting it — a regenerated golden that looks wrong is worse than a failing test.

- [ ] **Step 2: Add the four new goldens** following the existing test structure in `ShellGoldenTest.kt`.

- [ ] **Step 3: Run the full Android test suite**

```bash
./gradlew :app:connectedDebugAndroidTest --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest app/src/test
git commit -m "test(livetv): update goldens and cover rail and command bar states"
```

### Task 18: Live TV validation on the emulator

No task in this plan is complete on the strength of a build. Per `AGENTS.md`, live TV behaviour is validated with sustained screenshot capture and log evidence.

- [ ] **Step 1: Align the emulator**

```bash
adb shell cmd window set-ignore-orientation-request true
adb shell cmd window user-rotation lock 3
adb shell dumpsys window displays | rg "cur=|mRotation=|mUserRotationMode|mUserRotation=|ignoreOrientationRequest"
```
Expected: `cur=2340x1080 app=2340x1080`, `mDisplayRotation=ROTATION_270`, `ignoreOrientationRequest=true`.

- [ ] **Step 2: Capture with the rails visible**

Rails visible is the worst case for compositing, so leave focus parked in the channel rail for the whole capture:

```bash
mkdir -p /private/tmp/streamvault_livetv_glass
for n in $(seq -w 0 60); do
  adb exec-out screencap -p > /private/tmp/streamvault_livetv_glass/freq_${n}.png
  sleep 2
done
shasum -a 256 /private/tmp/streamvault_livetv_glass/freq_*.png | awk '{print $1}' | sort | uniq | wc -l
```
Expected: 61 screenshots over ~2 minutes; a unique-hash count well above 1 (a static count means the player froze).

- [ ] **Step 3: Confirm the player is healthy**

```bash
adb logcat -d -v time > /private/tmp/streamvault_livetv_glass.log
rg -n "fatal-error|Player stuck|state=ERROR|live-recovery no-candidate" /private/tmp/streamvault_livetv_glass.log
adb shell dumpsys media_session | rg "state=PlaybackState|error="
```
Expected: no fatal error, no stuck-player timeout; media session `PLAYING` with `error=null`.

- [ ] **Step 4: Repeat on a second channel**

Run Steps 2–3 again on a different channel. One channel is not evidence.

- [ ] **Step 5: Measure jank**

```bash
adb shell dumpsys gfxinfo com.streamvault.app framestats > /private/tmp/gfx_after.txt
```
Compare against a baseline captured from `master` before this work, same channel and duration. Record total frames, janky frames and 95th-percentile frame time. The rails over video must not measurably regress the 95th percentile.

- [ ] **Step 6: Report**

Record in the final summary: channel names, screenshot count, interval, unique-hash count, media-session result, log findings, and the before/after jank numbers.

---

## Spec coverage

| Spec section | Task(s) |
|---|---|
| §4.1 package rename | 1 |
| §4.2 `LiveTvVideoPane` | 2 |
| §4.2 `LiveTvCommandBar` | 3, 14 |
| §4.2 `LiveTvRails` | 4, 12 |
| §4.2 `LiveTvContextMenu` | 15 |
| §4.3 untouched files | enforced by Global Constraints |
| §4.4 sequencing | Phases A → B → C → D |
| §5 glass allocation | 8, 12 |
| §5 type floor | 12 Step 1 |
| §5 concentric corners | 12 Step 4 |
| §6 full-bleed canvas, fit/fill | 11 |
| §6 rail widths | 12 |
| §6 auto-hide + focus restore | 9, 13 |
| §6 command bar keys and focus map | 14 |
| §6 pane states (idle/connecting/playing/error/locked) | 2 (preserved), 11 (full-bleed) |
| §7 motion vocabulary | 5 |
| §7 focus pitch | 6, 12 |
| §7 no crossfade on stream swap | 11 |
| §7 accessibility / reduced motion | 16 |
| §8.1 no backdrop blur, artwork bloom | 10, 12 |
| §8.2 device tiers | 7, 8, 11 |
| §8.3 draw budget | 18 Step 5 |
| §9 validation | 17, 18 |
