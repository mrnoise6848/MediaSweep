# MediaSweep

**Find gallery cleanup candidates locally, review the evidence, and let Android confirm what goes to trash.**

A crowded gallery mixes exact copies, similar shots, screenshots and large recordings. Finding what is worth removing is tedious; automatically treating “similar” as “disposable” can remove a photo the user wanted to keep.

MediaSweep separates discovery from action. It groups media into five review categories—exact duplicates, visually similar images, screenshots, large files and old media—then lets the user compare and select items. A system trash request follows the review summary; the app re-queries MediaStore to establish what actually changed.

## Two kinds of similarity, two kinds of evidence

Exact-duplicate candidates are bucketed by metadata before streaming SHA-256 over their bytes. Image similarity uses reduced decoding and a 64-bit DCT perceptual hash, followed by Hamming-distance grouping with a BK-tree and union-find.

A near-duplicate cluster is a **review suggestion**, not proof that every member is interchangeable. Smaller clusters get an additional worst-pair distance check; clusters above 256 members skip that quadratic check and cannot receive high confidence. Videos participate in byte-level matching only.

Source: [exact analysis](app/src/main/java/com/noise/mediasweep/scanner/classification/ExactDuplicateAnalyzer.kt), [near analysis](app/src/main/java/com/noise/mediasweep/scanner/classification/NearDuplicateAnalyzer.kt). Decisions: [fingerprinting](docs/decisions/003-fingerprinting-strategy.md), [no video perceptual matching](docs/decisions/006-no-video-perceptual-matching.md).

## The scan and review pipeline

```text
MediaStore metadata → reconcile Room index
    ├─ suspicious exact buckets → streaming SHA-256
    ├─ images outside exact groups → reduced decode + perceptual hash
    └─ metadata rules → screenshots / large / old media
        → candidate groups → compare → user selection
        → Android trash confirmation → re-query actual MediaStore state
```

Unchanged fingerprints are reused on later scans. Exact hashing uses an 8 KiB buffer; perceptual decoding targets a 128 px longest edge and processes images sequentially. The first scan may decode many images: perceptual analysis is not limited to exact-duplicate buckets. These are cost controls, not measured scan-time or memory results.

MediaStore remains authoritative; Room stores the reconciled index, fingerprints and candidates. One application-scoped controller owns a scan, so navigation does not start competing pipelines. See [architecture](docs/architecture.md), [scanning pipeline](docs/scanning-pipeline.md) and [performance trade-offs](docs/performance.md).

## Completion, partial access and recovery

- Progress reports processed/total counters from real work.
- Selected-media access produces partial results and is labelled accordingly.
- Cancellation waits for cooperative cleanup before allowing a new scan.
- After process interruption, persisted state becomes stale if earlier results exist, or not-scanned for a first attempt. The app does not resume at the interrupted position.
- Trash reconciliation checks which requested IDs remain active rather than assuming a successful dialog removed every item. Cancelled or partially applied requests retain items still present.

See [trash reconciliation](app/src/main/java/com/noise/mediasweep/data/repository/MediaStoreTrashReconciler.kt). There is no automatic keeper selection, scheduled cleanup or direct deletion fallback.

## Try it with a small media set

Build with the configured Android toolchain and install the debug APK:

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Add a byte-identical copy, a few similar photos and a screenshot to a test library. Grant access, scan, inspect groups, select test items and review the system trash request. Rescan to check that the displayed inventory follows actual MediaStore state. Android 11+ is required for trash; Android 10 can review candidates but cannot use this action.

The previous README described a completed GIF/screenshot capture, but those assets are not present in the repository. A real scan result, group comparison and system-confirmation recording remain useful presentation evidence to collect.

## Verification and constraints

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Existing host tests cover hashes, grouping, index reuse, controller/session recovery, permissions, Room and Compose states using Robolectric where needed. The [CI workflow](.github/workflows/ci.yml) runs build, lint and unit checks. This documentation review did not rerun them or establish on-device performance.

Near matching can miss or over-group images; confidence labels are heuristics. Incremental reuse depends on metadata change detection. Access restrictions limit the visible library. Scans live in the app process; no background scheduler or checkpoint resume is implemented.

Analysis runs locally, with no declared `INTERNET` permission, analytics integration or account. Auto Backup is disabled for the index. Explicit trash actions use Android's system UI. See [privacy](docs/privacy.md).

MIT licensed: [LICENSE](LICENSE).
