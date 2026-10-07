# MediaSweep

**A crowded gallery needs a review queue.**

Duplicate downloads, near-identical shots, old screenshots and large recordings accumulate for different reasons. A single size-sorted list leaves most of the cleanup decision to manual browsing.

MediaSweep organizes an Android media library into groups worth reviewing. Analysis happens locally; you compare candidates and choose what to remove, then Android asks for confirmation before moving the selected items to trash.

## Five ways into the cleanup

| Review category | What brings the items together |
|---|---|
| Exact duplicates | Matching file hashes after metadata bucketing |
| Similar images | Nearby perceptual hashes, with a confidence label |
| Screenshots | Screenshot-related metadata |
| Large files | File size above the configured threshold |
| Old media | Age relative to the configured threshold |

Open a group to compare thumbnails, dates and sizes. Select individual items, review the summary, and continue to the system trash dialog. MediaSweep leaves the keeper decision to you.

## Exact copies and similar photos need different algorithms

**Byte-level matching** starts with cheap metadata buckets, then streams SHA-256 for candidate files. The hash buffer is 8 KiB, so hashing a large video does not require loading it into one large array.

**Visual similarity** decodes images at a reduced size and computes a 64-bit DCT perceptual hash. A BK-tree finds nearby hashes; union-find forms clusters. For clusters up to 256 members, a worst-pair distance check distinguishes stronger matches from looser similarity. Larger clusters skip that quadratic check and remain medium confidence.

The distinction reaches the UI: similar images are suggestions to compare, not interchangeable copies. Videos receive exact matching only. [Exact analyzer](app/src/main/java/com/noise/mediasweep/scanner/classification/ExactDuplicateAnalyzer.kt) · [Near-image analyzer](app/src/main/java/com/noise/mediasweep/scanner/classification/NearDuplicateAnalyzer.kt)

## Make the next scan cheaper—and the result current

MediaStore is the source of truth. Room holds a reconciled index, fingerprints and review groups. Unchanged fingerprints are reused; new or changed media is analyzed again. Perceptual work runs on images outside exact groups, so a first scan can still decode much of the library.

One application-scoped controller owns the scan across screen changes. Actual processed/total counters drive progress. After interruption, earlier results become stale rather than appearing complete; selected-media access is labelled partial.

After the trash dialog returns, the app queries MediaStore again and updates groups from the observed result. A cancelled or partially applied operation does not simply count every requested item as removed. [Trash reconciliation](app/src/main/java/com/noise/mediasweep/data/repository/MediaStoreTrashReconciler.kt) · [Scan lifecycle](docs/scanning-pipeline.md)

## Try a review session

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Populate a test library with an exact copy, similar photos and a screenshot. Grant media access, scan, compare a group and move selected test items to trash. Scan again to see the inventory reconcile. Android 11+ supports the system trash action; Android 10 supports review only.

## Engineering checks and scope

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

The host suite covers hashing/grouping, incremental reuse, controller recovery, Room, permissions and Compose states, using Robolectric where needed. [CI](.github/workflows/ci.yml) runs the same build/lint/unit checks. Device-scale timing and memory measurements remain to be collected.

Similarity is approximate, fingerprint reuse depends on metadata change detection, and restricted access limits what can be scanned. Scans run in the app process; background scheduling and checkpoint resume are not implemented.

The app declares no `INTERNET` permission and disables Auto Backup for its local index. [Privacy](docs/privacy.md) · [Architecture](docs/architecture.md) · [Performance trade-offs](docs/performance.md) · [MIT license](LICENSE)
