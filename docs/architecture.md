# MediaSweep — Architecture

Status: living document, updated as phases land.
Scope: single-module Android app (`app`), no multi-module split for MVP.

---

## 1. Shape of the system

```text
Compose screens (feature/*)
        │  immutable UiState (StateFlow)
        ▼
   ViewModel          ← owns no business rules, only mapping + collection
        │  use case invocation
        ▼
   UseCase (domain)   ← pure Kotlin, composes repositories, holds decisions
        │  interface defined in domain, implemented in data
        ▼
 Repository (domain interface)
        │
        ▼
 Repository impl (data)  ──►  Room (core/database)
        │                  └► MediaStore / ContentResolver (data/media, Phase 2+)
        ▼
 scanner pipeline (scanner/*)  ── grouping → hashing → classification
```

Dependency rules:

* `domain` never imports Android, Room, MediaStore, Compose or WorkManager.
* `core` holds infrastructure shared by the data layer (database, common formatting, DI).
* `data` implements the domain interfaces and owns all Android framework access.
* `feature` owns screens and ViewModels; it may depend on `domain` and `core`, never on `data` internals.
* `scanner` is pure computation over models handed to it; it has no Android imports except the
  image decoding boundary (Phase 5), which is isolated behind a small interface.

## 2. Package boundaries

```text
com.noise.mediasweep
├── MainActivity.kt
├── MediaSweepApplication.kt      app entry point, builds AppContainer
├── navigation/                   NavHost, routes
├── ui/theme/                     colour palette, typography, MediaSweepTheme
├── core/
│   ├── common/                   formatting (bytes, counts, ages) — pure functions
│   ├── database/                 Room database, entities, DAOs
│   ├── di/                       AppContainer + ViewModel factory helpers
│   └── media/                    permission model, MediaAccess, trash request factory
├── data/
│   ├── mapper/                   entity ⇄ domain mapping (the only place both meet)
│   ├── media/                    AndroidMediaStoreDataSource, content streaming, metadata mapper
│   └── repository/               repositories, scan controller/pipeline, reconcilers
├── domain/
│   ├── model/                    MediaItem, CandidateGroup, ScanStatus, thresholds
│   ├── repository/               interfaces
│   └── usecase/                  composable business rules
├── feature/
│   ├── candidates/               category list + one category screen (Phase 7)
│   ├── groupdetail/              duplicate group detail + selection (Phase 7)
│   ├── home/                     summary + status states
│   ├── review/                   selection model, review summary, trash hand-off (Phase 7/8)
│   ├── scan/                     scan progress + cancellation (Phase 9)
│   └── common/                   thumbnails, confidence badge, shared labels
└── scanner/
    ├── grouping/                 cheap bucketing
    ├── hashing/                  streaming SHA-256
    ├── image/                    grayscale, perceptual hashes, EXIF orientation, decode boundary
    └── classification/           candidate classification + confidence
```

There is no `worker/` package: scans run in the application-scoped `ScanController`
(`docs/decisions/007-no-workmanager-in-mvp.md`).

## 3. State management

* One immutable `data class` / `sealed interface` UI state per screen.
* `ViewModel` exposes `StateFlow<UiState>`; Compose collects with
  `collectAsStateWithLifecycle()`.
* Unidirectional flow: user action → ViewModel → use case → repository → new state → composition.
* No business logic inside `@Composable` functions, no long-running work started from a
  composition.
* Home states mirror the specification: `NO_PERMISSION`, `NO_MEDIA`, `NOT_SCANNED`,
  `SCANNING`, `SCAN_COMPLETE`, `SCAN_PARTIAL`, `SCAN_FAILED`, `STALE_RESULTS`.
* Progress numbers only ever come from real counters. There is no timer-driven progress bar.

## 4. Scan pipeline

```text
MediaStore query (streamed, batched)
      ↓
basic metadata → local index (Room, batched upserts)
      ↓
cheap bucketing  (size + mime + dimensions)     ← never declares duplicates by itself
      ↓
candidate groups
      ↓
streaming SHA-256 (exact duplicates, candidates only)
perceptual hash  (image near-duplicates, candidates only)
      ↓
classification + confidence
      ↓
persist candidate groups, fingerprints, session/state rows
```

* Expensive work happens only for candidate groups, never for the whole library.
* Cancellation is cooperative: every loop checks the coroutine `Job`.
* Interrupted scans are stored as unfinished sessions and never recorded as complete.
* The run lives in an application-scoped `ScanController` (`ScanRunner` seam), so it
  outlives the screen it was started from. Session states are `SCANNING → COMPLETE /
  PARTIAL / FAILED`, recovered to `STALE`/`NOT_SCANNED` at process start.
* Full stage-by-stage behaviour, progress rules and incremental reuse:
  **`docs/scanning-pipeline.md`**.

## 5. Database vs MediaStore responsibilities

| Concern | Owner |
| --- | --- |
| Does this file exist? | MediaStore (authoritative) |
| What does it look like (size, date, bucket)? | MediaStore, mirrored into Room |
| Fingerprints, candidate groups, scan state | Room (derived data only) |
| Deleting media | MediaStore via the system trash request |

Room is an index/cache. Every scan reconciles the index against MediaStore: rows that
disappeared are marked stale and removed from candidate results; they are never silently
treated as existing.

## 6. Fingerprint strategy

* Exact duplicates: cheap bucketing → streaming SHA-256 over a bounded buffer
  (a video is never loaded into memory as one `byte[]`).
* Near duplicates (images only): reduced-size decode → grayscale → 64-bit perceptual hash →
  Hamming distance with thresholds kept in `ScanThresholds` (never hardcoded across files).
* Videos: exact hash, size and age only. No video perceptual similarity in MVP.
* Undecodable/corrupt files are skipped with a recorded reason; they never abort a scan.

## 7. Background processing

* Scans run on background dispatchers inside the application-scoped `ScanController`, never
  on the main thread; the scan screen only observes `StateFlow`s.
* WorkManager is **not** used in MVP: the run is interactive (start, watch, cancel), and
  interruption is handled by persisted session state plus incremental reuse. The decision,
  alternatives and consequences are recorded in
  `docs/decisions/007-no-workmanager-in-mvp.md`.
* Live progress is published in memory from real processed/total counters; only the durable
  state transitions (`SCANNING`/`COMPLETE`/`PARTIAL`/`FAILED`) are written to the session
  row.

## 8. Permission strategy

* Contextual request: explain first, then request.
* Supports full access, selected/partial access, denied and revoked access.
* Partial access ⇒ results are labelled `SCAN_PARTIAL` / "Scanning selected media".
* The app never assumes permission is permanent; every screen re-derives state from the
  current grant.

## 9. Deletion / trash strategy

* Selection is always explicit; no winner is auto-selected for duplicate groups.
* A summary screen states counts and total size before anything destructive happens.
* Deletion uses the grouped MediaStore trash request so the system confirmation UI is shown;
  `MANAGE_MEDIA` is not requested.

The flow is split across small, individually testable pieces:

| Piece | Role |
| --- | --- |
| `AndroidTrashRequestFactory` (`core/media/TrashRequest.kt`) | Builds `MediaStore.createTrashRequest` → `SystemConfirmation(intentSender)`, or `Unsupported`/`Failed`. Never deletes anything. |
| `TrashViewModel` (`feature/review`) | State machine: `Idle → LaunchConfirmation → AwaitingConfirmation → Verifying → Completed/Cancelled`, plus honest `Unsupported`, `RequestFailed` and `VerifyFailed` states. |
| `ReviewSummaryRoute` | Owns the only Android glue: launches the `IntentSender` exactly once and forwards the activity result. |
| `MediaStoreTrashReconciler` (`data/repository`) | Re-queries MediaStore (`MediaStoreDataSource.activeIds`) and updates Room to match reality: `isTrashed` on rows that really disappeared, group membership evicted with recount, groups that no longer prove anything dropped. |

Rules the implementation guarantees:

* The intent result is never trusted: after `RESULT_OK` the requested ids are re-queried
  against MediaStore; only rows it no longer reports as active are updated locally.
* On cancellation nothing is written — no local deletion state is recorded for media that
  may still exist.
* Trashed rows leave active queries, storage totals and candidate groups immediately;
  fingerprints are kept so restoring from the system trash does not force a re-hash.
* A row seen again by a later scan clears its `stale`/`isTrashed` flags, so media restored
  from the system trash returns to the active library without special-case code.
* **Android 10 (minSdk 29) has no system trash request** (`createTrashRequest` is API 30):
  the flow reports `Unsupported` and explains that nothing will be deleted — MediaSweep
  never falls back to a silent `ContentResolver.delete`, which scoped storage would deny
  anyway. Related: the scan projection drops `IS_FAVORITE`/`IS_TRASHED` below API 30 so
  Android 10 queries stay valid.

## 10. Testing strategy

All tests run on the host JVM:

* pure JVM unit tests for formatting, classification, grouping, hashing inputs, state mapping;
* Robolectric for Room, permissions/state machine and Compose UI;
* `androidx.test` instrumented tests exist only for the single post-completion verification
  run (project constraint, `MediaSweep.md` §0.1).

Android framework access is isolated behind interfaces (`MediaStoreDataSource`, repositories)
so most behaviour is testable with fakes.

Host-side Compose test environment (both files under `app/src/test`):

* `robolectric.properties` sets `sdk=35` and `qualifiers=+w411dp-h891dp`, i.e. a realistic
  phone viewport. Robolectric's default 320×470dp window is smaller than any supported
  device and pushes legitimate content below the fold.
* Compose tests use `@GraphicsMode(LEGACY)`. Robolectric 4.17's **NATIVE** graphics runtime
  fails intermittently in this project: the fonts zip filesystem registered during the
  `sdk=29` sandbox's load is still open when the `sdk=35` sandbox loads
  (`FileSystemAlreadyExistsException` in `DefaultNativeRuntimeLoader.maybeCopyFonts`),
  which aborts native loading and cascades into `UnsatisfiedLinkError` for every later
  test. LEGACY mode never loads the native runtime, so the suite is deterministic.
  Assertions here are semantics-based (text, click, enabled, displayed), not pixel-based,
  so no rendering fidelity is lost; screenshots are produced on a real device (§0.1).
* Tests that reach content below the fold scroll first (`performScrollToNode`), the same
  way a user does.

## 11. Major trade-offs

* **Single module** — faster builds and simpler navigation at MVP scale; costs compile-time
  isolation between layers (mitigated by package discipline + tests).
* **Manual DI (`AppContainer`)** — no compiler plugin, no reflection, explicit graph; costs
  the automatic scoping Hilt would give (accepted for one application-scoped object).
* **Room as cache, MediaStore as truth** — extra reconciliation code; avoids ever telling the
  user a file exists when it does not.
* **Metadata-first scanning** — cheaper and safer, but duplicates are only found for files
  that pass cheap bucketing (by design, specification §6).
* **No video near-duplicates in MVP** — keeps scan time and memory bounded; a known gap that
  is called out in the README instead of hidden.
