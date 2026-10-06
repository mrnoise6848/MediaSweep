# MediaSweep

> **Your gallery is full. See what is worth reviewing before you delete anything.**

## The Problem

Phones accumulate thousands of photos and videos. Finding the duplicates, the giant screen
recordings, the year-old screenshots and the long-forgotten videos is tedious — and the
moment you start deleting by hand, the risk of removing the wrong photo goes up. The
useful work ("what could I clean up?") and the dangerous work ("delete this file") get
mixed together.

## The Solution

MediaSweep scans the media library **locally** and groups useful cleanup candidates
without uploading personal media anywhere.

One scan produces five review categories — exact duplicates, near-duplicate images,
screenshots, large files and old media — each backed by real MediaStore figures. You
review a group, select what you want to get rid of, and Android itself performs the
deletion through its system trash confirmation. MediaSweep never deletes anything on its
own.

### Demo GIF

Captured in the final on-device verification run (single post-completion run,
`MediaSweep.md` §0.1) and committed as `docs/media/demo.gif`. It shows the complete flow:
grant access → scan with live progress → open a duplicate group → compare → select →
review summary → system trash confirmation → refreshed summary.

### Screenshots

Also produced in that same on-device run (`docs/media/`), covering: the permission
screen, home with storage summary, scanning screen, completed/partial/failed scan, each
of the five categories, a candidate group detail, the review summary, the trash
confirmation result, dark theme, and an error state.

## Features

* **Contextual media permission** — full access, Android 14+ *selected* (partial) access,
  and denied, each with its own honest UI state.
* **Real scan progress** — actual processed/total counters and candidates found; no
  fabricated percentages, ever.
* **Exact duplicates** — streaming SHA-256 over file bytes, grouped with size and reason.
* **Near-duplicate images** — 64-bit perceptual hash (DCT pHash, plus dHash/aHash) with
  BK-tree + union-find clustering and confidence levels. Videos are never perceptually
  hashed (ADR 006).
* **Screenshots, large files, old media** — cheap metadata classifiers with the real
  sizes and counts.
* **Group detail** — side-by-side rows with thumbnails, size, date and per-item
  selection. No automatic "keeper" decision is ever made for you.
* **Review summary + system trash** — one confirmation summary, then the Android system
  dialog; on return the index is re-read from MediaStore instead of trusting the result.
* **Cancellable, incremental scans** — cancel at any time; the next scan reuses the
  fingerprints of unchanged media instead of re-reading files.
* **Dark and light theme** (follows the system, Material You dynamic colour on Android
  12+), scalable text, and accessibility semantics on progress and loading states.
* **Fully offline** — no `INTERNET` permission, no account, no telemetry.

## How it works

```text
MediaStore query (streamed, batched)
      ↓
metadata index in Room (reconciled every run: added / changed / disappeared)
      ↓
cheap bucketing (size + mime + dimensions)          ← never declares duplicates
      ↓
streaming SHA-256            ← only inside a suspicious bucket
reduced 128 px decode + pHash ← only for image candidates, only if not cached
      ↓
exact groups · near clusters · screenshot / large-file / old-media rows
      ↓
review → selection → MediaStore system trash request → re-read source of truth
```

Expensive work only ever runs for candidates, never for the whole library, and
fingerprints of unchanged files are reused on later scans. The pipeline is cooperative
with cancellation and records honest session states (`SCANNING → COMPLETE / PARTIAL /
FAILED`, recovered to `STALE` after an interrupted run). Full detail:
[docs/scanning-pipeline.md](docs/scanning-pipeline.md).

## Architecture

Lightweight Clean Architecture with unidirectional data flow — no multi-module setup, no
DI framework:

| Layer | Contents |
| --- | --- |
| `domain/` | models, use cases, repository interfaces (no Android types) |
| `data/` | repositories, scan controller/pipeline, MediaStore + Room implementations |
| `scanner/` | pure scanner components: bucketing, hashing, EXIF orientation, grouping, classification |
| `feature/` | Compose screens + ViewModels (`StateFlow` → immutable UI state) |
| `core/` | database, permissions, DI (`AppContainer`), formatting |

* **MediaStore is the source of truth**; Room is only an index/cache that is reconciled
  after every scan and trash operation.
* One application-scoped `ScanController` owns a run, so progress survives navigation.
* Deletion is always the system trash flow — MediaSweep has no delete path of its own.

Docs: [architecture](docs/architecture.md) ·
[scanning pipeline](docs/scanning-pipeline.md) ·
[decisions (ADR 001–007)](docs/decisions/) ·
[privacy](docs/privacy.md) · [performance](docs/performance.md)

## Privacy

**Media analysis happens locally on the device.**

**Media is not uploaded to a server by MediaSweep.**

**No account is required.**

Concretely:

* the manifest declares no `INTERNET` permission — the app cannot open a socket;
* no analytics, crash reporting or logging of media data;
* Android Auto Backup is disabled, so even the local index stays on the device;
* deleting is user-confirmed in the Android system UI and never automatic.

Full statement with verification steps: [docs/privacy.md](docs/privacy.md).

## Performance

* Cheap-first pipeline: metadata bucketing first, hashing only where it can pay off.
* 8 KiB streaming hash buffer — a 2 GB video never becomes a 2 GB array.
* Images are decoded once, subsampled to 128 px, for the perceptual hash.
* Batched database writes (500 rows / 400 SQL parameters) and indexed queries.
* Incremental scans read a lean fingerprint projection and skip re-hashing unchanged
  media, so a repeat scan of an unchanged library reads no files at all.
* Lists are keyed lazy columns; dates format with a lock-free formatter.

Details and trade-offs: [docs/performance.md](docs/performance.md).

## Testing

Everything is verified host-side — no emulator or device is required:

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

* **262 tests**, all on the JVM: pure unit tests (formatting, classification, grouping,
  hashing, state machines), Robolectric tests for Room/permissions/session recovery, and
  host-side Compose UI tests for every screen and state.
* Compose tests run with Robolectric `LEGACY` graphics and a realistic 411×891 dp
  viewport (`app/src/test/resources/robolectric.properties`) — documented in
  [docs/architecture.md](docs/architecture.md) §10.
* CI runs the same command on every push (`.github/workflows/ci.yml`).
* Instrumented tests exist for the single post-completion device run only (project
  constraint §0.1).

## Limitations

* **Android 10 (API 29) has no system trash request** — MediaSweep reports
  "cannot trash on Android 10" and deletes nothing, rather than falling back to a silent
  delete that scoped storage would deny anyway.
* **Videos are never perceptually matched** (ADR 006): exact duplicates still work for
  videos via SHA-256, but visually similar clips are not grouped.
* **Partial access means partial results** — with Android 14+ selected access the scan
  honestly reports "Showing results from available media".
* **No background scanning**: a run lives in the app process; if Android kills the app
  mid-scan, the state recovers to *stale* and you simply start it again (ADR 007).
* **No automatic cleanup, quotas or scheduling** — review-only by design (ADR 004).
* Screenshots and the demo GIF are produced in the final on-device run (§0.1).

## Roadmap

* Resume a scan where it stopped instead of restarting (richer persisted progress).
* Periodic/deferred scans via WorkManager once there is a real scheduling need.
* Perceptual matching for short videos, HEIC/RAW coverage review.
* More languages and locale-aware grouping.
* Optional: storage trend history, duplicate-aware "keep the best shot" suggestions
  (still user-confirmed).

## License

MIT — see [LICENSE](LICENSE).
