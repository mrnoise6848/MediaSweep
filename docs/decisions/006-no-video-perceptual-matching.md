# ADR 006 — No video perceptual matching in MVP

**Status:** Accepted

## Context

Video near-duplicate detection normally means decoding many frames per video (scene sampling),
keeping them in memory and comparing them. On a phone with hundreds of gigabytes of video this
is the single most expensive thing the app could do, and it is the easiest place to introduce
out-of-memory crashes, thermal throttling and battery complaints.

## Decision

* Videos participate in **exact** duplicate detection only (streaming SHA-256 after cheap
  bucketing), plus the size- and age-based criteria (large files, old media).
* Images get perceptual near-duplicate detection; videos do not.
* No frame extraction pipeline is built in MVP.

## Alternatives

* Sample N frames per video and hash them — catches re-encodes and trims, but multiplies scan
  cost by the number of videos and complicates memory management.
* MediaMetadataRetriever thumbnail comparison — cheaper, still touches every video frame and
  is unreliable across codecs.
* On-device ML video embeddings — explicitly out of scope for MVP.

## Trade-offs

* A re-encoded copy of the same video is not reported as a near duplicate; only identical
  bytes are.
* Users with mostly video libraries see fewer duplicate candidates.

## Consequences

* Scan time and memory stay bounded and predictable (specification §21, §23).
* The limitation is documented in the README instead of being hidden behind a
  low-confidence result.
* A future version can add a frame-sampling engine behind the existing fingerprint interface
  without changing the pipeline.
