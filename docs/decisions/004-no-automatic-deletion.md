# ADR 004 — No automatic deletion; system trash with explicit confirmation

**Status:** Accepted

## Context

The product surfaces *candidates to review*, not files that are "safe to delete". Deleting the
wrong photo is unrecoverable from the user's point of view, and any silent deletion would
break the trust the app is built on.

## Decision

* Nothing is deleted without the user selecting it and confirming a summary that shows counts
  and total size.
* Deletion is performed through the grouped MediaStore trash request, so Android shows its own
  confirmation UI and the file lands in the system trash where the OS supports it.
* `MANAGE_MEDIA` is not requested; the app works without media-management privileges.
* After the system flow returns, the app re-queries MediaStore instead of assuming success,
  then reconciles Room.
* No "select a winner" heuristic in duplicate groups: the user keeps what they want.
* Permanent deletion is not offered in MVP.
* **Android 10 (minSdk 29) has no `MediaStore.createTrashRequest` (API 30+)**, and scoped
  storage denies `ContentResolver.delete()` for media the app does not own. Rather than
  bypassing the system confirmation with an in-app one, the flow reports "System trash needs
  Android 11 or newer" and deletes nothing.

## Alternatives

* Immediate `delete(uri)` — fewer taps, loses recoverability and skips the system's own guard.
* `MANAGE_MEDIA` to skip confirmations — explicitly forbidden by the specification.
* Auto-select the "best" copy of a duplicate group — faster, but the app cannot know which
  copy the user cares about (metadata such as favourite/resolution is evidence, not proof).

## Trade-offs

* More screens and one extra system dialog in the happy path.
* The app must tolerate the user cancelling the system dialog (no local state is written
  before the actual MediaStore state is observed).
* On Android 10 the review and selection features work, but nothing can be moved to trash;
  the screen says so instead of offering a destructive action that would fail or bypass the
  system guard.

## Consequences

* Every destructive action is attributable to an explicit user decision.
* Cancelled requests cannot corrupt the index because nothing is recorded optimistically.
* Restore/undo is available through the system trash.
