# ADR 007 — No WorkManager for scans in MVP

**Status:** Accepted

## Context

Specification §18 says scanning must not block the main thread and that "WorkManager may be
used for long-running or deferrable scans **when justified**". Phase 9 asks for a
cancellable scan with progress, resume/restart behaviour and incremental updates.

WorkManager is the canonical Android answer for work that must survive process death and
run "in the background", but the requirement has to be matched to what the product actually
does: MediaSweep's scan is an **interactive** operation — the user starts it, watches real
progress counters, and can cancel it — and the scan screen (§27) is a first-class
destination, not a fire-and-forget job.

## Decision

Run scans through an **application-scoped `ScanController`** driving a `ScanPipeline`
coroutine, and do **not** introduce WorkManager in MVP.

* Interruption (process death, swipe-away, crash) is handled by the persisted session state:
  `startSession()` writes `SCANNING`, `recoverInterruptedSessions()` at process start resets
  it to `STALE`/`NOT_SCANNED`, and the next scan simply runs again.
* Incremental reuse (`§20` fingerprint reuse) makes a re-run cheap, so "resume where we left
  off mid-file" buys little for the MVP.
* The `ScanRunner` seam keeps the door open: a `Worker` could implement the same interface
  later without touching the UI or the state machine.

## Alternatives

* **WorkManager periodic/expedited work** — survives process death by design, but the run
  would then live outside the UI's control: cancellation becomes `WorkManager.cancelWork`
  with a delay, progress needs a `SharedPreferences`/DataStore bridge plus observers, and
  duplicate runs need `ExistingWorkPolicy`. It also adds a dependency (plus a version that
  must fit the frozen toolchain, §0.2) to solve a problem the session state already covers.
* **Foreground service** — keeps the scan alive with a visible notification, but introduces
  service lifecycle, notification-channel and Android 12+ start restrictions for a flow the
  user is already watching on screen.
* **`viewModelScope` alone (per-screen run)** — simplest, but the run dies when the user
  navigates away, which contradicts "the scan outlives the screen".

## Trade-offs

* If the process dies mid-scan, the work is not resumed automatically — the user starts a
  new scan, which is honest: the app reports *stale/not scanned* instead of pretending to
  continue from an unknown point.
* No "scan every night" or deferred scanning; MVP has no such requirement.
* Battery/thermal throttling is managed by cooperative cancellation and batched I/O rather
  than by `Constraints` (e.g. "only on unmetered network" — irrelevant, nothing is
  uploaded).

## Consequences

* One application-scoped source of truth for a run: `ScanController.state` is observed by
  Home and the scan screen, so two screens can never show conflicting progress.
* `start()` refuses a second run while one is in flight — no `ExistingWorkPolicy` needed.
* A `Worker` can be added later by implementing `ScanRunner`; the state machine, screens
  and session recovery stay as they are.
* `libs.versions.toml` keeps a pinned WorkManager entry that is deliberately **unused**, so
  adopting it later needs no version hunt (§0.2).
