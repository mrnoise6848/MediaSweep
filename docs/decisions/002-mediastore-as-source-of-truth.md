# ADR 002 — MediaStore is the source of truth, Room is an index

**Status:** Accepted

## Context

Media can be created, renamed, moved, trashed or deleted outside the app (camera, gallery,
other cleaners, USB, MTP). If the app treated its own database as authoritative it would
eventually show files that no longer exist, or hide files that do — and could point a user
at a destructive action based on stale information.

## Decision

* MediaStore/ContentResolver answers "does this file exist and what is it".
* Room stores only derived data: mirrored metadata, fingerprints, candidate groups and scan
  sessions/state.
* Every scan and every trash hand-off reconciles the index against MediaStore; rows that
  disappeared are marked stale and dropped from candidate results (never silently reused).
* The app never claims a file exists because Room says so; it re-queries before review and
  after any system operation.

## Alternatives

* Room as source of truth — simpler queries, wrong answers after external changes.
* No local database at all — always correct, but fingerprints and candidate groups would have
  to be recomputed on every launch (too expensive for thousands of files).

## Trade-offs

* Reconciliation code and stale-marking logic must be maintained.
* Some queries must join index rows with a fresh MediaStore check.

## Consequences

* The UI can honestly label results as stale when the library changed.
* Trash operations are verified against actual MediaStore state, not against an Intent result.
* The database can be deleted and rebuilt without losing user data.
