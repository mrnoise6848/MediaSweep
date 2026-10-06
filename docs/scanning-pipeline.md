# MediaSweep — Scanning Pipeline

Authoritative behaviour notes for the scan run itself. Component-level boundaries live in
`docs/architecture.md`; the design decisions live in `docs/decisions/`.

## 1. Who owns a scan

```text
ScanViewModel ─┐                     (application-scoped, one instance)
HomeViewModel ─┼── observes ──► ScanController.state : StateFlow<ScanRunState>
               └── starts ───► ScanController.start()  /  .cancel()
                                     │
                                     │ single coroutine per run
                                     ▼
                               ScanRunner (interface)
                                     │
                                     ▼
                               ScanPipeline (implementation)
```

* `ScanController` is built once in `AppContainer` (application scope), so a scan
  **outlives the screen it was started from**: the user can leave the scan screen, open
  the candidates list, and come back to the same live run (specification §18, §27).
* Only one run may be in flight. `start()` returns `false` while a previous run — or its
  cooperative cleanup — is still in flight, so two pipelines can never write concurrently.
* The controller publishes state, never callbacks:

  | State | Meaning |
  | --- | --- |
  | `Idle` | no run has started yet |
  | `Running(progress)` | a run is live; `progress` is `null` until the first real counter arrives |
  | `Cancelling` | cancellation requested, pipeline winding down |
  | `Finished(outcome)` | the run ended on its own: `COMPLETE`, `PARTIAL` or `FAILED` |
  | `Cancelled` | the run was cancelled; nothing is claimed as complete |

* `ScanRunner` is the seam: production uses `ScanPipeline`, tests inject a fake runner, so
  the controller's state machine is verified without a media library.

## 2. Stages

One `ScanPipeline.run(partialAccess, onProgress)` call executes, in order:

```text
1. INDEXING       MediaStore is streamed in batches and the local Room index is
                  reconciled against it (added / changed / disappeared rows).
2. FINGERPRINTING cheap bucketing → streaming SHA-256 for exact-duplicate candidates →
                  reduced decode + perceptual hash for image near-duplicate candidates.
                  Both analyzers reuse fingerprints of unchanged media (see §3).
3. CLASSIFYING    exact groups, near groups, then the metadata classifiers
                  (screenshots / large files / old media) are persisted.
4. Outcome        totals and per-type group counts are read back from Room — the number
                  the UI shows is what the database actually contains.
```

Each stage reports **actual work only** (specification §18): counters come from processed
items, never from a clock or a fabricated percentage. `ScanProgress` carries
`processedCount`, `totalCount`, `foundCounts` and `phase`; the UI renders
`processed / total` and `candidates found` from it. Before the first trustworthy counter
exists the scan screen shows no percentage at all.

`foundCounts` is republished after the exact stage and after the near stage, so
"Candidates found: …" grows while the scan is still running.

## 3. Incremental updates (specification §20)

A scan after the first one does materially less work:

* `MediaIndexSynchronizer` deletes fingerprint rows only for media that **changed or
  disappeared**; fingerprints of unchanged media survive.
* The pipeline loads those surviving fingerprints (restricted to currently active ids) and
  passes them to the analyzers as `existingHashes`.
* `ExactDuplicateAnalyzer` hashes only bucket members without a stored SHA-256;
  `NearDuplicateAnalyzer` decodes and hashes only images without a stored perceptual hash.
  Both report `reusedCount`.
* New fingerprints are inserted with `filterNot { it.mediaId in existing… }`, so
  `calculatedAt` of reused fingerprints is left untouched.

Result: an unchanged library is re-analyzed **from the stored fingerprints without
re-reading a single file**, while new/changed media is hashed exactly once. This is
covered by `ScanPipelineTest.a second scan reuses fingerprints instead of re-reading
unchanged media` and by the per-analyzer reuse tests.

## 4. Session state (what is persisted)

`ScanSessionStore` writes state transitions to the `scan_state` row:

```text
startSession()  → scan_state = SCANNING     (last-success fields are preserved,
                |                            so a running scan never erases history)
complete()      → COMPLETE or PARTIAL       (sets lastSuccessfulScanAt, totals, groups)
fail()          → FAILED                    (records the error message)
recoverInterruptedSessions()
                → STALE if a previous success exists
                | NOT_SCANNED if it was the first ever attempt
```

`recoverInterruptedSessions()` runs in `AppContainer.init`, i.e. at process start. A scan
that was killed mid-run therefore never keeps claiming "scanning", and a screen that opens
after the process died shows *stale results* (or *not scanned*) instead of a progress bar
that will never move. Interrupted sessions are never recorded as complete.

The per-event progress counters are **not** written to disk: they are published in memory
by the controller. Only the durable transitions above are persisted, which is what makes
recovery trivial after a process death.

## 5. Cancellation (specification §45)

```text
user taps Cancel
  → ScanController.state = Cancelling, job.cancel()
  → ScanPipeline observes the cancellation at its next loop checkpoint,
    runs session recovery under NonCancellable, rethrows CancellationException
  → ScanController.invokeOnCompletion sets ScanRunState.Cancelled
```

Rules the tests pin down:

* `Cancelled` is published only after the pipeline has left the database consistent —
  never `Finished(COMPLETE)`.
* Cancellation writes no completion: no `lastSuccessfulScanAt`, no `COMPLETE` status.
* `Cancel` cannot be requested twice (`Cancelling` shows no button).
* While the run is winding down, `start()` still refuses a new run.

## 6. Failures and partial access

* Any exception inside the pipeline becomes a `FAILED` `ScanOutcome` with the real message;
  the session row records `FAILED` and the scan screen offers *Retry*. A failed scan never
  claims completion and never silently keeps old results fresh.
* A run with `MediaAccess.PARTIAL` completes as `PARTIAL` and is labelled "Scanning
  selected media" / "Showing results from available media." — the app never implies the
  whole library was seen.
* Undecodable or corrupt files are skipped with a reason and never abort a run.

## 7. Threading

* The controller owns one coroutine over an application-scoped `CoroutineScope`; the heavy
  work runs on background dispatchers, never on the main thread (specification §18).
* Room and MediaStore access is suspension-based; all database writes are batched.
* Observers (`HomeViewModel`, `ScanViewModel`) only read `StateFlow`s — no Composable ever
  starts long-running work directly (specification §15).

## 8. Tests

| Area | Test |
| --- | --- |
| Run state machine, single-run guard, cancellation, failure | `ScanControllerTest` |
| Pipeline staging, cancellation, incremental reuse | `ScanPipelineTest` |
| Session lifecycle and interrupted-run recovery | `ScanSessionStoreTest` |
| Fingerprint reuse per analyzer | `ExactDuplicateAnalyzerTest`, `NearDuplicateAnalyzerTest` |
| Auto-start, partial access, cancel from the screen | `ScanViewModelTest` |
| Real counters, cancel/cancelled/complete/failed states | `ScanScreenTest` |
| Home shows the live run's counters | `HomeViewModelTest` |

All of these run on the host JVM (Robolectric where Android classes are needed); no device
or emulator is used before the project is finished (constraint §0.1).
