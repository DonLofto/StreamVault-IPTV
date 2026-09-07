# StreamVault Audit Remediation Design Specification

## Goal

Restore truthful security, lifecycle, persistence, playback, Compose, and Android TV behavior for the confirmed findings in the supplied remediation mandate, with a focused regression test and current verification evidence for every disposition.

## Scope and delivery order

The work is split into independently testable workstreams. Transport and VPN safety come first; provider lifecycle and timeshift safety come next; cancellation and platform compatibility follow; then Compose/TV behavior, dormant contracts, lint, and measurement. A playback-sensitive workstream is not considered complete until the repository's two-channel live-TV protocol has either passed or been explicitly recorded as externally blocked.

The existing audit documents remain historical references. `docs/performance-audit-results.md` is corrected only from evidence produced in this session; source inspection alone does not establish runtime, benchmark, lint, or live-TV success.

## Architectural decisions

### Strict transport and credentials

The normal Stalker API client uses the platform/OkHttp trust store and hostname verification. The provider no longer hard-codes an unsafe flag, and no redirect or fallback path upgrades a failed TLS connection. Playback request headers are constrained to the resolved origin: authorization, cookies, and provider-specific identity headers are not copied to a different host. The generic stream model remains strict by default; an unsafe compatibility path is retained only where a real, user-selected persisted setting is proven to exist, and it is never selected by Stalker resolution.

### VPN product truth

The repository has a WireGuard configuration parser but no supported WireGuard transport. The placeholder `VpnService` therefore cannot remain a protection claim. The built-in VPN service, binding, settings controls, and dead runtime state are removed together. Existing VPN preference data is retired through a one-time cleanup path that does not log private keys. The app documents that it does not provide an embedded VPN and does not establish a default-route TUN interface.

### Provider lifecycle and cancellation

A provider-scoped lifecycle coordinator owns the admission state for refresh/sync operations and deletion tombstones. Refresh operations acquire an operation lease only while the provider is live; deletion marks the provider as deleting, waits for active leases, then performs catalog/EPG/staging cleanup before releasing the tombstone. Staged EPG promotion rechecks provider existence inside the same transaction. All blocking OkHttp calls use a cancellable coroutine adapter that invokes `Call.cancel()` and preserves `CancellationException`.

### Timeshift safety and accounting

Timeshift session state, snapshot generation, and file mutations are serialized under explicit session/global locks. DASH initialization data is separate from the rolling media queue. Disk accounting is updated or invalidated immediately on every write, link, copy, prune, and delete; hard links are charged by physical inode identity rather than logical path. Snapshot output is written into a temporary generation and atomically promoted only after all referenced files and the playlist are complete. Stop/channel replacement cancels and joins snapshot work before deleting the session directory.

### UI clocks, focus, and startup

Observable clocks are created at screen/row scope and passed as immutable `nowMs` values to cards and narrow EPG current-state layers. Static geometry and labels remain above the clock dependency. Stable item keys and identity-based focus state are used for dynamic TV lists. The row bring-into-view modifier retains measured state across recomposition, informational surfaces are not focus targets, and multiview owns only its own screen-awake flag. Optional startup work runs through an injectable post-first-frame coordinator rather than arbitrary delays.

### Dormant contracts and capacity settings

A persisted setting is retained only when its consumer, lifecycle, and user-facing behavior can be demonstrated. External subtitle search is removed unless an approved provider backend is already present; local Media3 subtitle attachment remains. External playback modes are completed across route/content entry points. Maximum concurrent streams and automatic update download are either wired end-to-end with lifecycle tests or removed with their persistence keys. Download cancellation and dormant ViewModel actions receive explicit consumers or are deleted.

### Quality gates

Lint errors are fixed at their source without blanket suppressions or disabled issue IDs. Android API guards, permission contracts, receiver flags, Media3 public API use, resources, and app links are corrected narrowly. Macrobenchmark coverage is added only where the current Gradle/device setup supports it; otherwise the exact unavailable dependency or runtime is recorded. No release, benchmark, or live-TV claim is made without current command output and recorded device evidence.

## Testing strategy

Each behavior change follows red-green-refactor: add one focused regression, run it and retain the expected failure, implement the smallest fix, rerun the focused test, then run the owning module. Deterministic barriers, latches, test dispatchers, fake clocks, and injectable file/HTTP operations replace timing sleeps. Final verification repeats the fresh unit/FFmpeg suite, separate lint reports, debug/release builds where signing permits, connected tests where a device is available, `git diff --check`, and the two-channel live-TV protocol.

## Evidence and residual risk

The implementation plan maintains a matrix with finding ID, disposition, changed files, regression test, exact command/result, and runtime evidence. Unavailable provider credentials, live sources, Android TV devices, benchmark devices, or signing material are recorded as blockers rather than converted into success claims. Unrelated working-tree changes are preserved; this checkout starts clean at the audited commit.
