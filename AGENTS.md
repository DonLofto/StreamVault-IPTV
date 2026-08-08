## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Before answering architecture or codebase questions, read graphify-out/GRAPH_REPORT.md for god nodes and community structure
- If graphify-out/wiki/index.md exists, navigate it instead of reading raw files
- For cross-module "how does X relate to Y" questions, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, or `graphify explain "<concept>"` over grep — these traverse the graph's EXTRACTED + INFERRED edges instead of scanning files
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost)

## StreamVault emulator orientation

When starting the emulator with StreamVault, always ensure the visible device frame
and the app orientation are aligned before playback debugging. The known-good
orientation from the debugging session is the emulator in landscape with the app
upright at Android `ROTATION_270`.

Use:

```bash
adb shell cmd window set-ignore-orientation-request true
adb shell cmd window user-rotation lock 3
```

Verify with:

```bash
adb shell dumpsys window displays | rg "cur=|mRotation=|mUserRotationMode|mUserRotation=|mCurrentRotation|mDisplayRotation|ignoreOrientationRequest"
```

Expected state:
- `cur=2340x1080 app=2340x1080`
- `mDisplayRotation=ROTATION_270`
- `mRotation=3`
- `mUserRotationMode=USER_ROTATION_LOCKED`
- `mUserRotation=ROTATION_270`
- `ignoreOrientationRequest=true`

Do not treat `cur=2340x1080 app=2340x1080` alone as sufficient; the app can
still be sideways or upside down if the emulator frame and Android rotation are
not aligned. If the phone frame is portrait while the app is upright, rotate the
emulator frame with `adb emu rotate`, then reapply the `ROTATION_270` lock above.

## Live TV playback validation

For live TV playback bugs, do not validate with a single screenshot, a short
visual check, build success, install success, or launch success. Use frequent
screenshots and log evidence from the emulator.

Use a 2-second screenshot cadence for live TV stuckness checks. Capture long
enough to pass the historical stuck window: at least 45 screenshots for roughly
90 seconds, and prefer 61 screenshots for roughly 2 minutes when validating a
fix that previously failed around the one-minute mark.

Example:

```bash
mkdir -p /private/tmp/streamvault_live_validation
for i in $(seq -w 0 60); do
  adb exec-out screencap -p > /private/tmp/streamvault_live_validation/freq_${i}.png
  stat -f "freq_${i} %z" /private/tmp/streamvault_live_validation/freq_${i}.png
  sleep 2
done
```

After capture, confirm frame progression with hashes:

```bash
shasum -a 256 /private/tmp/streamvault_live_validation/freq_*.png | awk '{print $1}' | sort | uniq | wc -l
```

Then confirm the player is still healthy:

```bash
adb shell dumpsys media_session | awk '/package=com.streamvault.app/{seen=1} seen && /metadata:/{print; getline; print; getline; print} seen && /state=PlaybackState/{print; exit}'
adb logcat -d -v time > /private/tmp/streamvault_live_validation.log
rg -n "fatal-error|live-recovery selected|live-recovery no-candidate|prepare resolvedStreamType=MPEG_TS_LIVE|source-malformed live-ts-fallback|Player stuck|state=ERROR" /private/tmp/streamvault_live_validation.log
rg -n "retry category=|first-frame-success|prepare resolvedStreamType=HLS|read-progress streamType=HLS" /private/tmp/streamvault_live_validation.log | tail -80
```

A passing validation needs:
- screenshots that keep changing through the full capture window
- media session still in `PLAYING` with `error=null`
- no fatal player error, no stuck-player timeout, and no unintended MPEG-TS
  fallback
- sanitized log evidence showing HLS prepare/read/first-frame or recovery
  behavior

Validate more than one live channel when the bug is reported as affecting live
TV generally. Record the channel names, screenshot count, interval, unique hash
count, media-session result, and log findings in the final report.


## Do NOT
- Never reference Claude, Claude Code, or AI generation in commit
  messages or PR descriptions
- Never commit secrets or .env files

## Project

Android TV IPTV player. Kotlin, Jetpack Compose, Media3 (ExoPlayer), Hilt, Room, OkHttp, Coroutines.
Multi-module Gradle: `app/` (UI/ViewModels), `player/` (playback + Media3Engine), `data/`
(provider/repository/URL resolution), `domain/` (models).

Key entry points:
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt` — central playback engine
  (player lifecycle, preparation, stall recovery, buffer-policy promotion, retries).
- `player/src/main/java/com/streamvault/player/playback/PolicyAwareLoadControl.kt` — mutable-policy
  LoadControl (live buffer promotion without player teardown).
- `player/src/main/java/com/streamvault/player/timeshift/LiveTimeshiftManager.kt` — live-rewind capture.
- `data/src/main/java/com/streamvault/data/remote/xtream/XtreamStreamUrlResolver.kt` — playback URL
  resolution (Xtream/Stalker/Jellyfin/M3U), `preferStableUrl` for long sessions.
- `app/src/main/java/com/streamvault/app/ui/screens/player/` — player ViewModel + lifecycle actions.

## Commands

Build/test (JAVA_HOME required on this machine; SDK dir in `local.properties`):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew :player:testDebugUnitTest        # player module unit tests
./gradlew :data:testDebugUnitTest          # data module unit tests
./gradlew :app:compileDebugKotlin          # app module compile check
./gradlew :app:assembleDebug               # debug APK
```

Graph upkeep:

```bash
graphify update .   # after code changes (AGENTS graph; tool optional)
```

Emulator / device helpers: see "StreamVault emulator orientation" and "Live TV playback
validation" above. Environment provisioning (JDK/SDK install, `sdk.dir`, toolchain paths) is
logged in `SESSION.md`.