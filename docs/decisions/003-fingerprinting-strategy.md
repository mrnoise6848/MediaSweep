# ADR 003 — Two-stage fingerprinting (cheap bucketing, then hashes)

**Status:** Accepted

## Context

A library of 10,000+ files cannot be hashed eagerly: streaming SHA-256 over every video and
decoding every image for a perceptual hash would make the first scan slow, hot and
memory-hungry. On the other hand, cheap metadata alone cannot prove that two files are the
same (identical size and dimensions happen by accident).

## Decision

1. **Cheap bucketing** on metadata that MediaStore already provides: byte size, MIME type,
   dimensions, duration. Buckets with a single member are never duplicates.
2. **Streaming SHA-256** (bounded buffer, no full-file `byte[]`) applied only to bucket
   members that could actually be duplicates. Byte-identical files ⇒ `EXACT_DUPLICATE` with
   HIGH confidence.
3. **Perceptual hash** on reduced-size, EXIF-normalised image decodes, applied only to image
   buckets that survived step 1 and only within a Hamming-distance threshold kept in
   `ScanThresholds`.
4. Cheap bucketing never by itself declares a duplicate — only a hash comparison does.

## Alternatives

* Hash everything — maximally simple, unacceptably expensive on large libraries.
* Metadata-only duplicates — fast, produces false positives the specification explicitly
  rejects (acceptance test: same size/MIME/dimensions but different bytes ⇒ not duplicates).
* ML embeddings / cloud vision — rejected by the specification (privacy, size, cost).

## Trade-offs

* Two passes over candidate data and intermediate group structures.
* A duplicate pair split across different buckets (e.g. after a re-encode) is only caught by
  the perceptual stage, and only for images.

## Consequences

* Exact duplicate detection is HIGH confidence and cheap in the common case.
* Memory stays bounded: streaming hashes and reduced-size decodes only.
* Thresholds live in one object, so accuracy can be tuned without touching the pipeline.
