# MediaSweep — Performance

Implementation cost controls for scanning. Numbers below are code constants, not measured
on-device performance. No device measurement report is included in this repository.

## 1. Cost model: cheap first, expensive only where it pays

```text
MediaStore metadata read          O(items)        always
cheap bucketing (size + mime + …) O(items)        always, in memory
streaming SHA-256                 O(bytes of       only inside a bucket that already
                                  candidates)      looks duplicated
reduced decode + perceptual hash  O(images)        images outside exact groups, never videos
classification                    O(items)         plain arithmetic on metadata
```

Exact bucketing skips singleton buckets; hash work depends on the number of members in
non-singleton buckets, not the number of distinct sizes. Near-duplicate analysis separately
decodes uncached images outside exact groups, so a first scan may inspect most images.

## 2. Bounded memory (specification §21)

| Resource | Bound | Where |
| --- | --- | --- |
| File hashing | **8 KiB** buffer, streamed; a 2 GB video never becomes a `byte[]` | `Sha256.BUFFER_BYTES` |
| Image decode for pHash | power-of-two subsample, then fit to **128 px** longest edge | `ReducedImageDecoder.targetSize` |
| pHash transform | 32×32 centre crop → separable DCT-II → 8×8 block | `DctImageHasher` |
| Thumbnails | loaded at **128 px** target, one row at a time, cancelled when the row leaves composition | `MediaThumbnail` |
| Database writes | batches of **500** fingerprints, `IN` clauses chunked to **400** params | `FINGERPRINT_BATCH`, `MediaItemDao.IN_CHUNK` |
| Progress/found counts | small immutable maps; state is confluently reduced | `ScanProgress` |

Decode targets limit bitmap size. This is not a measured peak-memory bound for the app.

## 3. Incremental scans (specification §20)

The second scan of an unchanged library does **no** hashing work:

* fingerprints of unchanged media survive (only changed/removed media is dropped by the
  synchronizer);
* the pipeline reads them through a lean projection (`FingerprintValue`: id + hash only —
  no entity columns are copied into a large list);
* analyzers skip hashing/decoding for anything already fingerprinted and report
  `reusedCount`;
* new fingerprints are inserted only for media that did not have one.

Concretely: after the first scan, an unchanged library is re-analyzed from the database
without re-reading a single file — pinned by
`ScanPipelineTest.a second scan reuses fingerprints instead of re-reading unchanged media`.

## 4. Main thread and responsiveness (specification §18)

* The scan runs in the application-scoped `ScanController` on background dispatchers; the
  UI only observes `StateFlow`s. Nothing blocking runs on the main thread.
* Progress is published from real counters as state (confluent: only the latest is
  rendered), so a fast scan never queues up recompositions.
* `formatDate` uses an immutable `DateTimeFormatter` and resolves the zone per call —
  list rows format dates without taking a shared lock while hashing runs on another
  thread.
* Lists are `LazyColumn`s with stable `key`s, so scrolling reuses row composition and the
  review list scales to hundreds of items.

## 5. Database shape

Indexes exist on the columns the queries filter or sort by:

`media_items(sizeBytes, dateModified, mediaType, bucketId, stale)`,
`fingerprints(algorithm, mediaId)`, `candidate_groups(type, confidence)`,
`candidate_group_members(mediaId, groupId)`, `scan_sessions(startedAt)`.

These indexes support the corresponding filters; query plans and collection-scale costs
still need measurement. The pipeline also loads metadata for in-memory classification.

## 6. Deliberate trade-offs

* `media_items` rows are loaded once per scan (metadata only, not pixels) so classification
  and grouping run in memory without per-item queries. At tens of thousands of items this
  is a few megabytes of short-lived objects, far cheaper than thousands of round-trips.
* Near-duplicate comparison is BK-tree + union-find over bucket members instead of an
  all-pairs comparison, reducing all-pairs search work. The additional diameter check is quadratic within clusters
  of at most 256 members; larger clusters skip it and remain medium confidence.
* Videos are never perceptually hashed (ADR 006) — the single biggest memory/CPU saving
  available.
* One scan at a time (ADR 007) instead of parallel workers: predictable memory, no
  contention on the database, and cancellation stays trivially correct.

## 7. What is measured where

* **Host tests** verify behaviour and bounds: chunk sizes, reuse, cancellation points,
  subsample selection (`sampleSizeFor`), grouping algorithms. They are correctness tests,
  not benchmarks.
* **On-device measurements** (scan wall time on a real library, memory high-water mark,
  main-thread jank) belong to the final verification run; no such results are included here yet.
