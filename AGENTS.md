# EFFICIENT TOOL USAGE & TOKEN CONSERVATION — macOS

## 0. PLATFORM PREFLIGHT (once per session, cache the result)
- Export these once for every shell session; they prevent the top three agent hangs:
  `export GIT_PAGER=cat PAGER=cat LESS=FRX GIT_TERMINAL_PROMPT=0 CI=1 TERM=dumb`
- Assume BSD userland. GNU-only flags that MUST NOT be used: `sed -i` without an
  argument, `stat -c`, `grep -P`, `readlink -f` (pre-12.3), `xargs -r/-d`, `journalctl`,
  `timeout`, `tree`.
- The default shell is zsh: an unmatched glob is a hard ERROR (not a literal). Never pass
  bare globs to a tool — quote them and let the tool expand: `rg -g '*.ts'`, not `rg *.ts`.
- The filesystem is case-insensitive: `Foo.ts` and `foo.ts` are the same file. Never rely
  on case to disambiguate paths; use `git ls-files` for the canonical spelling.

## 1. FILE READING & BOUNDS
- Prefer the native `Read` tool with `offset`/`limit` over shell `cat`/`sed`. Shell reads
  are unbounded, untruncated, and cost 3–5× the tokens.
- Before any full read, size it first (one cheap call): `wc -l <file>` (or
  `stat -f%z <file>` for bytes — note `-f%z`, NOT `-c%s`).
- >100 lines → outline first, then read ranges. Outline options, in order of preference:
  1. `ast-grep -l <lang> -p '<construct>' --heading never <path>`
  2. `rg -n '^\s*(export |public |private )?(async )?(func|function|class|struct|enum|interface|type|def|fn|impl) ' <file>`
  3. `universal-ctags -x --_xformat='%-20N %4n %K' <file> | head -n 60`
     (only if `command -v ctags` resolves to Homebrew's universal-ctags; /usr/bin/ctags
     is BSD ctags and will fail on these flags)
- Shell fallback for a range, with CORRECT quoting (path OUTSIDE the script):
  `sed -n '50,120p' path/to/file`
- Never mutate files with `sed`/`awk`/redirection. Use the native `Edit` tool, or
  `git apply` for multi-file patches. (BSD `sed -i` needs `-i ''` and litters `file-e`.)

## 2. CONTENT SEARCH (rg)
- Escalation ladder — never skip a rung:
  1. `rg -l <pattern>`        → which files? (cheapest)
  2. `rg -c <pattern>`        → how concentrated?
  3. `rg -n <pattern> <narrow-path>` → the actual lines
- Standard bounded invocation (note the GLOBAL cap — `-m` is PER FILE):
  `rg -n --no-heading -M 150 --max-columns-preview -m 5 -g '!*.min.*' <pattern> <path> | head -n 60`
- SATURATION PROTOCOL: if output hits the `head` cap or many files show high `rg -c`
  counts, do NOT raise the caps. Narrow instead: tighter path, `-g '*.swift'`,
  `-w` (word boundary), or `--type-not test`.
- Always exclude macOS/Xcode build sinks (rg honors .gitignore, but these are often
  untracked-and-unignored): `-g '!{node_modules,.build,DerivedData,Pods,Carthage,.venv,dist,*.xcarchive}/**'`
- Never search `~/Library`, iCloud Drive, `~/Documents`, or `~/Desktop`: TCC will raise a
  GUI permission dialog that blocks the shell until a human clicks it. Stay inside the repo.
- For one-off system-wide lookups, `mdfind -onlyin <dir> '<text>'` uses the Spotlight
  index (instant) — but it misses gitignored and just-written files, so never use it to
  verify your own edits.

## 3. FILE DISCOVERY & TREES
- Tracked files: `git ls-files '<glob>'` (fastest, already filtered).
- Untracked: `fd -t f -d 3 -E node_modules -E .git -E dist -E .build -E DerivedData -E Pods`
- Trees: `eza --tree --level=2 --git-ignore` or `fd -t d -d 2`. NEVER `tree` — not
  installed on macOS. NEVER `find /` or `find ~` — Spotlight/TCC/network volumes.
- Repo shape in one call: `tokei --sort code | head -n 15`.

## 4. STRUCTURAL / AST SEARCH
- Prefer `ast-grep` over regex for code constructs — it ignores comments and strings.
- Use the `ast-grep` binary, NOT `sg` (deprecated on all platforms; also collides with
  setgroups and breaks when invoked by absolute path):
  `ast-grep -l ts -p 'await $A.$B($$$)' --heading never src/ | head -n 60`
- Rewrites: dry-run first (`ast-grep -p ... -r ...`), inspect the diff, only then `-U`.

## 5. GIT & DIFF
- Always `git --no-pager <cmd>` (or rely on the §0 `GIT_PAGER=cat`).
- Scope before content: `git --no-pager diff --stat` → `--name-only` → `-U3 -- <path>`.
- Bound large diffs: `git --no-pager diff -U2 -- <path> | head -n 200`.
- Never `git log` without `-n` and a format: `git --no-pager log -n 10 --oneline`.
- Never `git add -A` in an Xcode repo without checking for `.DS_Store`, `xcuserdata/`,
  and `*.xcuserstate` in the diff first.

## 6. BUILDS, TESTS & LOGS — log-to-file, then query
- NEVER pipe a build straight into `grep | head`: it loses the exit code, hides stderr,
  and can SIGPIPE the runner. Use this pattern instead:
    `npm test > /tmp/t.log 2>&1; echo "exit=$?"; rg -n -m 3 -i 'fail|error|✕' /tmp/t.log | head -n 40`
  The full log stays on disk for follow-up queries at zero token cost.
- Prefer a runner's own terse reporter over grepping:
  `vitest run --reporter=dot` · `jest --silent` · `pytest -q --tb=short -x`
  `tsc --noEmit --pretty false` · `eslint -f compact` · `cargo build --message-format short`
  `swift build 2>&1 | tail -n 30` · `swift test --filter <Suite>`
- Xcode (raw output is 3k–30k lines — never emit it):
  `xcodebuild -list -json` for targets/schemes;
  `set -o pipefail; xcodebuild -scheme S -destination 'platform=iOS Simulator,name=iPhone 16' build 2>&1 | xcbeautify --quieter | tail -n 40`
  Test results: `xcrun xcresulttool get test-results summary --path <X.xcresult> --compact | jq '{result,failedTests,testFailures}'`
  (Legacy `xcresulttool get --format json` is deprecated as of Xcode 16 — do not use.)
- Wrap anything that may hang: `gtimeout 300 <cmd>` (fallback:
  `perl -e 'alarm shift; exec @ARGV' 300 <cmd>`). Wrap long builds in `caffeinate -i`.
- Logs, macOS-native (there is no journalctl):
  `log show --last 10m --style compact --predicate 'process == "MyApp"' | tail -n 50`
  `log stream --level error --style compact` (only under `gtimeout`, it never exits)
  `launchctl print gui/$(id -u)/<label>` for a service's state.
- Containers: `docker logs --tail 50 --since 10m <container>`. On macOS the daemon may be
  Docker Desktop, OrbStack, or colima — check `docker context ls` before blaming the image.
  Apple's native `container` CLI (macOS 26+) uses `container logs --tail 50 <id>`.
- Plists/config: `plutil -p Info.plist | head -n 40`, `defaults read <domain> <key>`,
  `jq '.scripts' package.json` — never `cat` a binary plist.

## 7. GLOBAL OUTPUT BUDGET
- Hard cap: every command must be bounded to ≤200 lines by construction
  (`-m`, `--tail`, `| head -n N`, `--stat`, `-q`). If you cannot bound it, redirect to
  /tmp and query the file.
- One purpose per call. Do not chain exploratory `&&` pipelines that produce output you
  have no plan to read.
- After two calls that yield nothing useful, stop and change strategy (different tool,
  different rung of the ladder) rather than re-running with wider limits.

## NEVER RUN (macOS)
- `journalctl`, `tree`, bare `timeout`, `stat -c`, `grep -P`, `sed -i` w/o `''`
- `find /` · `find ~` · `mdfind` without `-onlyin` (TCC dialogs, network volumes)
- `git diff`/`log`/`show` without `--no-pager` (blocks on `less`)
- `xcodebuild` without `-quiet`/`xcbeautify` (3k–30k lines)
- `open`, `osascript`, `say`, `sudo`, `brew install` — user-visible side effects; ask first
- `cat` on `*.xcuserstate`, `*.pbxproj` (~10k lines), binary plists, `*.xcresult`
- `rm -rf ~/Library/Developer/Xcode/DerivedData` without confirming with the user


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


## Project

StreamVault is a Kotlin Android TV-first IPTV player. It uses Jetpack Compose, Media3,
Hilt, Room, OkHttp, and Coroutines. Providers: M3U, Xtream Codes, Stalker Portal, and
Jellyfin. Phone/tablet installs are supported; Android TV and D-pad UX are primary.

Modules:
- `app/`: Application, Compose UI, ViewModels, navigation, DI, TIF, diagnostics.
- `data/`: Room, provider clients, sync workers, parsers, repositories.
- `domain/`: models, repository contracts, managers, use cases.
- `player/`: `Media3PlayerEngine`, playback policy, decoder/render selection, timeshift.

Entry points:
- `app/src/main/java/com/streamvault/app/StreamVaultApp.kt`
- `app/src/main/java/com/streamvault/app/MainActivity.kt`
- `app/src/main/java/com/streamvault/app/tvinput/StreamVaultTvInputService.kt`

## Commands

- `./gradlew :player:verifyLocalFfmpegArtifact`: verifies bundled FFmpeg AAR metadata, required ABIs, classes, and MP2 support. Defined by `player/build.gradle.kts`.

## Performance Audit

- Findings: `docs/performance-audit.md` (measured on a Fire TV Stick AFTSSS; every finding carries device evidence or exact `file:line`).
- Remediation plan — per-finding change steps, files, regression tests, ordering, and validation: `docs/planFix.md`.
- Start with Phase 1 (the per-call object-construction defect class): it is the mechanism confirmed
  on-device at 135-320% sustained idle CPU. Do not start broad performance work before it lands, and
  re-baseline the acceptance thresholds after it, not before.
- The timeshift bugs B1/B2 from the previous audit are **fixed**; do not re-open them. See
  "Prior remediation status" in the audit before redoing anything.
- Findings are remediated in the PR order given in `docs/planFix.md` §1.2.
- Measuring on device: `kill -3` does not work here (the ART dump lands in `/data/anr/trace_00`,
  unreadable without root). Use JDWP + `jdb` - see `docs/planFix.md` §2.2.
- Player, decoder, renderer, timeshift, or network-admission changes require full live-TV validation above on at least two channels.

## Do NOT
- Never reference Claude, Claude Code, or AI generation in commit
  messages or PR descriptions
- Never commit secrets or .env files
