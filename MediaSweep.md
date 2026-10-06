# MediaSweep — Product Specification & Agent Instructions

---

# 0. Non-Negotiable Constraints (Project Owner)

These rules override any other instruction in this document, including acceptance criteria, demo steps, and test strategy sections below.

## 0.1 No device or emulator work until the project is finished

Until **every** phase (Phase 1 → Phase 10) is implemented and the Final Definition of Done is reached:

* Do NOT run the app on a physical device.
* Do NOT start an emulator.
* Do NOT run `adb` commands.
* Do NOT run `connectedAndroidTest`, `connectedCheck`, `instrumented`, or any `androidTest` execution.
* Do NOT install APKs.

All verification during development must be host-side only:

```text
./gradlew assembleDebug / assembleRelease   (build)
./gradlew lint / lintDebug                  (static analysis)
./gradlew test / testDebugUnitTest          (JVM unit tests)
Robolectric-based tests                     (host JVM, no device)
Compose host-side tests (createComposeRule on the JVM/Robolectric)
```

Consequently:

* Compose UI tests required by this specification must be written as **host-side** tests (Robolectric + `createComposeRule`), not as instrumentation tests on `androidTest`.
* Tests in `app/src/androidTest` may be written/kept, but must not be executed until the project is complete.
* Android framework APIs required by tests must be isolated behind testable interfaces so they can be exercised on the JVM (fake `MediaStore`, fake `ContentResolver`, in-memory Room or a fake DAO).
* Phase acceptance criteria phrased as "a real device …" are interpreted as **host-verifiable equivalents** (fake data source + Robolectric test) until the project is complete.

After all phases are complete, a single end-to-end verification on a device/emulator may be performed, and only then.

## 0.2 The toolchain is frozen

Work exactly on the current project configuration. Do NOT change:

* Gradle wrapper version (`gradle-9.8.0`) or any `gradle/wrapper/*` file
* Android Gradle Plugin version (`9.4.1`)
* Kotlin version (`2.4.20`) and the compose compiler plugin version
* `compileSdk` (`37`), `targetSdk` (`37`), `minSdk` (`29`)
* Java compatibility level (`VERSION_11`)
* The `namespace` / `applicationId` (`com.noise.mediasweep`)
* Existing declared dependency versions (`core-ktx 1.19.1`, `junit 4.13.2`, `androidx.test.ext:junit 1.3.0`, `espresso 3.7.0`, `lifecycle 2.11.0`, `activity-compose 1.13.0`, `compose BOM 2026.09.00`)
* Gradle settings such as `org.gradle.configuration-cache`
* Repository configuration in `settings.gradle.kts`

Adding **new** dependencies is allowed when a phase genuinely requires them (Room, coroutines, Hilt, WorkManager, Robolectric, etc.), subject to the dependency rules in section 55, but they must be pinned to a version that is compatible with the frozen toolchain above. Never "fix" a compatibility problem by upgrading Gradle, AGP, Kotlin, or the SDK.

## 0.3 Scope discipline

Everything else in this document stands: privacy-first, no silent deletion, MediaStore as source of truth, no fake data, no unfinished phases.

---

## 1. Product Definition

MediaSweep is a privacy-first Android utility that helps users find media they may want to review or remove before their device storage becomes a problem.

### Core value proposition

> **Your gallery is full. MediaSweep shows you what is taking the space and what you may want to clean up.**

MediaSweep is NOT:

* a generic gallery app
* a cloud backup service
* an automatic deletion tool
* an AI photo organizer
* a replacement for Google Photos

The product is a focused storage-cleanup utility.

---

# 2. Primary User Problem

Users accumulate thousands of photos and videos.

The problem is not simply:

> "I have too many files."

The real problem is:

> **"I don't know which files are safe or useful to review, and I don't want to spend an hour manually finding them."**

MediaSweep should surface useful cleanup candidates while keeping the user in control.

The app must never imply that a file is objectively safe to delete.

It should use language such as:

```text
Candidates to review
Potential duplicates
Large files
Old media
Screenshots
```

Not:

```text
Safe to delete
Useless files
Delete these
```

---

# 3. Product Principles

## Principle 1 — Privacy First

All scanning and analysis should happen locally on the device.

No media should be uploaded to a server in MVP.

No account is required.

No cloud backend is required.

## Principle 2 — Never silently delete

The user must explicitly review and confirm destructive actions.

Prefer moving selected media to the system trash where supported rather than immediate permanent deletion.

## Principle 3 — Explain why something was surfaced

Each candidate should have a reason.

Example:

```text
3 identical photos

Same file content
Same size
Same visual hash
```

or:

```text
Large video

1.84 GB
Captured 8 months ago
```

## Principle 4 — Accuracy over feature count

A smaller scanner with reliable detection is preferable to a huge "AI cleaner" full of false positives.

---

# 4. MVP Scope

## Must Have

### A. Media Access

Support photos and videos available through Android shared media storage.

Use Android's media APIs correctly for the target SDK.

Do not access arbitrary filesystem paths.

Use `MediaStore` / `ContentResolver`.

Handle Android version differences appropriately.

The permission flow must clearly explain why access is required.

Support:

* full access where granted
* selected/partial media access where the OS provides it
* denied access
* revoked access

The application must remain stable when access is partial or denied.

Do not assume permanent media access.

---

# 5. Media Index

Scan available media and collect lightweight metadata.

For each item, store or derive:

```text
contentUri
mediaType
displayName
mimeType
sizeBytes
width
height
durationMs
dateAdded
dateModified
relativePath when available
bucket/album when available
isFavorite when available
```

Do not copy all original media into the application's private storage.

Do not duplicate large files.

Store only metadata and derived fingerprints.

---

# 6. Scan Strategy

Scanning must be incremental and staged.

Do not immediately calculate expensive fingerprints for every media item.

Use a pipeline similar to:

```text
MediaStore Query
      ↓
Basic Metadata
      ↓
Cheap Bucketing
      ↓
Candidate Groups
      ↓
Expensive Fingerprint
      ↓
Classification
      ↓
Persist Results
```

Example:

```text
10,000 photos
    ↓
group by size / dimensions / mime
    ↓
candidate groups
    ↓
exact hash only for candidates
    ↓
perceptual hash for image near-duplicates
```

The scanner must avoid unnecessary work.

---

# 7. Candidate Categories

MVP supports these categories.

## 7.1 Exact Duplicates

Files with identical content.

Preferred algorithm:

```text
cheap grouping
→ cryptographic hash
```

Use a streaming approach.

Never load a huge video fully into memory.

Recommended conceptual process:

```text
size
+
mime
+
optional metadata
    ↓
candidate group
    ↓
streaming SHA-256
    ↓
exact duplicate group
```

Exact duplicates should have:

```text
HIGH CONFIDENCE
```

---

## 7.2 Near-Duplicate Images

Find visually similar images that are not byte-for-byte identical.

MVP should support image perceptual hashing.

A practical approach is acceptable:

```text
decode reduced image
    ↓
normalize size
    ↓
grayscale
    ↓
perceptual hash
    ↓
Hamming distance
```

Do not build a custom ML model.

Do not use cloud vision APIs.

Do not use a huge neural network.

Allow configurable similarity thresholds internally.

Example:

```text
Exact duplicate
Near duplicate
Probably similar
```

Only surface strong matches by default.

---

## 7.3 Screenshots

Detect likely screenshots using deterministic signals.

Possible signals:

* media path contains screenshot-related folder name
* filename contains screenshot-related patterns
* MIME type
* known Android screenshot naming patterns
* image dimensions / aspect ratio as supporting evidence

Do not depend on a single signal.

Return:

```text
SCREENSHOT
```

with a confidence level.

Do not claim every matching image is definitely a screenshot.

---

## 7.4 Large Files

Identify unusually large media.

Default thresholds may be:

```text
Large image:
20 MB+

Large video:
500 MB+
```

These thresholds must be configurable later.

The exact threshold should not affect architecture.

Show:

```text
1.84 GB
```

using human-readable units.

---

## 7.5 Old Media

Surface media older than a configurable age.

Default:

```text
180 days
```

This is only a review criterion.

Never describe an old file as unused unless actual usage data exists.

Correct:

```text
Older than 180 days
```

Incorrect:

```text
Unused for 180 days
```

---

# 8. Storage Summary

Home screen should provide useful summary information.

Example:

```text
Storage Review

Your media:
128.4 GB

Candidates:
18.7 GB

Exact duplicates:
4.2 GB

Large videos:
8.1 GB

Screenshots:
3.7 GB

Old media:
2.7 GB
```

These are derived from the scan.

Do not invent fake statistics.

If the scan is incomplete, clearly state:

```text
Scan incomplete
```

or:

```text
Showing results from available media
```

---

# 9. Review Model

The central UX is review, not automatic deletion.

User flow:

```text
Category
   ↓
Candidate Group
   ↓
Compare media
   ↓
Select items
   ↓
Review total size
   ↓
Move selected items to Trash
   ↓
System confirmation
```

Example:

```text
3 identical photos
1.8 MB each

[Select]

Keep one
Delete 2
```

For near duplicates:

```text
Similar photos

[Photo A] [Photo B] [Photo C]

Similarity:
94%

Select for review
```

Do not automatically select a "winner" in MVP.

---

# 10. Trash / Delete Behavior

MVP must prefer system-managed trash where available.

For Android versions supporting grouped media operations:

Use the appropriate MediaStore request APIs.

Expected conceptual flow:

```text
User selects media
       ↓
App creates trash request
       ↓
System confirmation UI
       ↓
User confirms
       ↓
Media moved to trash
       ↓
Local index refreshed
```

Do not permanently delete media without explicit user action.

Do not bypass system confirmation.

Do not request `MANAGE_MEDIA` merely to avoid confirmation.

The app should work without special media-management privileges.

---

# 11. Undo / Refresh

After a successful trash operation:

* refresh MediaStore
* update local index
* remove trashed items from active candidate lists
* update storage summaries

Do not assume the operation succeeded merely because an Intent was launched.

Re-query actual state after returning from the system confirmation flow.

---

# 12. Local Database

Use Room for the local index and persisted scan state.

Use the Room version compatible with the project's frozen Android/Gradle/Kotlin configuration (see section 0.2).

Do not upgrade Gradle, AGP, Kotlin, or the SDK in order to use a newer Room release.

Do not hardcode an outdated dependency version merely because it appears in this specification.

Suggested conceptual entities:

```text
MediaItemEntity
FingerprintEntity
DuplicateGroupEntity
ScanStateEntity
ScanSessionEntity
```

The database must never become the source of truth for whether a media file still exists.

MediaStore remains the authoritative source.

---

# 13. Suggested Domain Model

```kotlin
MediaItem(
    id,
    contentUri,
    mediaType,
    displayName,
    mimeType,
    sizeBytes,
    width,
    height,
    durationMs,
    dateAdded,
    dateModified,
    relativePath,
    favorite,
)
```

```kotlin
MediaFingerprint(
    mediaId,
    algorithm,
    value,
    calculatedAt,
)
```

```kotlin
CandidateGroup(
    id,
    type,
    confidence,
    totalSizeBytes,
    itemCount,
)
```

Candidate types:

```text
EXACT_DUPLICATE
NEAR_DUPLICATE
SCREENSHOT
LARGE_FILE
OLD_MEDIA
```

Confidence:

```text
HIGH
MEDIUM
LOW
```

Only HIGH and MEDIUM should normally be surfaced.

---

# 14. Architecture Decision Rule

## Architecture First

Before implementation:

1. inspect repository
2. inspect Android configuration
3. inspect existing dependencies
4. inspect existing architecture
5. document architecture
6. evaluate alternatives
7. only then implement

Prefer the simplest production-grade architecture.

Use Clean Architecture principles lightly.

Recommended:

```text
Presentation
      ↓
Domain
      ↓
Data
```

Dependency direction:

```text
Compose
   ↓
ViewModel
   ↓
UseCase
   ↓
Repository Interface
   ↓
Repository Implementation
   ↓
MediaStore / Room / Android APIs
```

The Domain layer must not know about:

* Android Context
* ContentResolver
* Uri implementations
* MediaStore
* Room
* Compose
* ViewModel
* WorkManager

Android-specific concerns belong to infrastructure/data layers.

---

# 15. UI State

Use:

```text
ViewModel
+
StateFlow
+
Unidirectional Data Flow
```

Conceptual flow:

```text
User Action
    ↓
ViewModel
    ↓
UseCase
    ↓
Repository
    ↓
Result
    ↓
UI State
    ↓
Compose
```

Do not put business logic inside Composables.

Do not trigger long-running scan logic directly from Composable bodies.

---

# 16. Dependency Injection

Hilt is acceptable if it simplifies dependency management and testing.

Do not create unnecessary interfaces.

Good:

```text
MediaRepository
ScanRepository
FingerprintEngine
```

Bad:

```text
IMediaRepository
IMediaRepositoryFactory
IMediaRepositoryProvider
IMediaRepositoryResolver
```

without actual need.

Use abstractions where they provide:

* testability
* replaceable infrastructure
* clear ownership

---

# 17. Media Scanner Architecture

Create a dedicated scanner pipeline.

Suggested components:

```text
MediaStoreDataSource
MediaMetadataMapper
CandidateGrouper
ExactHashEngine
PerceptualHashEngine
CandidateClassifier
ScanRepository
```

Conceptual flow:

```text
MediaStoreDataSource
        ↓
MediaMetadataMapper
        ↓
CandidateGrouper
        ↓
┌───────┴────────┐
↓                ↓
Exact Hash     Image PHash
↓                ↓
Duplicate       Near Duplicate
Group            Group
       └──────┬──────┘
              ↓
     CandidateClassifier
              ↓
          Database
```

---

# 18. Background Scanning

Scanning can be expensive.

Do not block the main thread.

Use appropriate coroutine dispatchers and background execution.

WorkManager may be used for long-running or deferrable scans when justified.

Use a WorkManager version compatible with the frozen project configuration (see section 0.2). Do not change toolchain versions to obtain a newer WorkManager.

The UI should be able to show:

```text
Scanning...

4,832 / 12,240

Candidates found:
...
```

Progress must be based on actual work.

Do not fake progress percentages.

---

# 19. Scan Resumability

A scan should be resilient to interruption.

Requirements:

* app moving to background
* process recreation
* temporary failure
* permission changes

Persist enough state to safely resume or restart.

Do not corrupt the database if a scan is interrupted.

An interrupted scan should never mark the library as fully scanned.

---

# 20. Incremental Scanning

Avoid a full expensive scan every time.

Use cheap metadata comparison to identify:

```text
new media
modified media
removed media
unchanged media
```

Use MediaStore's available generation/change mechanisms where appropriate.

If a full rescan is necessary, clearly explain why.

---

# 21. Memory Constraints

This app may scan thousands of images and videos.

Never:

```text
load all images into memory
decode all full-size thumbnails simultaneously
hash huge files using a byte[] containing the entire file
```

Use:

* streams
* bounded buffers
* reduced-size bitmap decoding
* batch processing
* cancellation

Large scans must avoid OOM conditions.

---

# 22. Image Processing

For perceptual hashing:

* decode only the required reduced dimensions
* handle EXIF orientation
* handle corrupt images
* handle unsupported formats gracefully
* close resources correctly

If a file cannot be decoded:

```text
SKIP_WITH_REASON
```

Do not crash the entire scan.

---

# 23. Video Handling

For MVP:

* exact duplicate videos are supported through streaming content hashing
* large video detection is supported
* old video detection is supported

Do NOT implement video perceptual similarity in MVP.

Do NOT extract dozens of frames from every video.

The goal is reliability and manageable performance.

---

# 24. Corrupt / Missing Media

Media can disappear or become inaccessible between scan and review.

Every operation must gracefully handle:

```text
file deleted externally
permission revoked
URI inaccessible
provider error
corrupt content
```

The app should:

1. mark item unavailable
2. remove or refresh stale candidate data
3. continue processing other items
4. never crash globally

---

# 25. Permissions UX

Permission requests must be contextual.

Do not request all permissions immediately at first launch without explanation.

Recommended flow:

```text
First launch
   ↓
Explain value
   ↓
Request appropriate media access
   ↓
Scan
```

If permission is denied:

```text
Media access is required to scan your library.
```

Provide a clear action to retry/open system settings where appropriate.

If Android provides selected-media access, treat the library as partial.

Show:

```text
Scanning selected media
```

rather than:

```text
Scanning entire library
```

---

# 26. Main Screens

## Home

Show:

```text
MediaSweep

Your media
128.4 GB

Review candidates
18.7 GB

Exact duplicates       4.2 GB
Near duplicates        1.3 GB
Large videos            8.1 GB
Screenshots             3.7 GB
Old media               2.7 GB
```

Primary action:

```text
Review candidates
```

Secondary:

```text
Scan now
```

---

# 27. Scan Screen

Show:

```text
Scanning your media

4,832 / 12,240

Found:
Exact duplicates: 48
Near duplicates: 21
Screenshots: 183
Large files: 17
```

Allow:

```text
Cancel
```

Cancellation must be cooperative.

The scanner should stop safely.

---

# 28. Category Screen

Example:

```text
Exact duplicates

48 groups
4.2 GB potentially reviewable
```

Each group shows:

```text
thumbnail
count
total size
reason
confidence
```

Example:

```text
3 identical files
5.6 MB total
HIGH CONFIDENCE
```

---

# 29. Candidate Detail

For duplicate images:

```text
[Photo A]
[Photo B]
[Photo C]

Same content

Size:
1.8 MB each

Created:
May 12, 2026
```

Allow individual selection.

Provide:

```text
Select all duplicates
```

but do not automatically decide which one is the keeper.

---

# 30. Large Files Screen

Sort by size descending.

Example:

```text
1.84 GB
VID_20260112.mp4

842 MB
VID_20251118.mp4

620 MB
ScreenRecording_01.mp4
```

Allow sorting:

```text
Largest
Newest
Oldest
```

---

# 31. Screenshots Screen

Show likely screenshots.

Allow:

```text
Select
Select all
```

Show why the item was classified.

Example:

```text
Likely screenshot

Detected from media path + filename pattern
```

---

# 32. Old Media Screen

Show:

```text
Older than 180 days
```

Never call these unused.

Allow filtering by:

```text
Oldest
Largest
Newest
```

---

# 33. Review Summary

Before destructive action:

```text
You're about to review:

27 photos
3 videos

Total:
2.8 GB
```

Primary action:

```text
Move to Trash
```

Secondary:

```text
Cancel
```

Before the system operation, clearly explain that the selected media will be moved to the system trash.

---

# 34. Home Status States

Support:

```text
NO_PERMISSION
NO_MEDIA
SCANNING
SCAN_COMPLETE
SCAN_PARTIAL
SCAN_FAILED
STALE_RESULTS
```

Every state should have an understandable UI.

---

# 35. Empty States

### No media

```text
No photos or videos were found.
```

### No candidates

```text
Your library looks clean.

No strong cleanup candidates were found.
```

Do not invent a "You saved 0 GB" marketing statistic.

---

# 36. Privacy

The app must be local-first.

Do not send:

* photos
* video
* filenames
* media metadata
* hashes

to a remote server in MVP.

No analytics SDK is required.

No user account.

README must include a clear privacy section.

---

# 37. Security

Do not expose:

* file paths unnecessarily
* content data in logs
* media URIs in verbose production logs unless justified

Never log entire media lists.

Avoid leaking sensitive filenames through debug logs.

Remove or disable verbose diagnostic logging for release builds.

---

# 38. Explicit Non-Goals

Do NOT implement in MVP:

* cloud backup
* account/login
* social sharing
* AI image understanding
* face recognition
* object recognition
* OCR
* automatic permanent deletion
* video near-duplicate detection
* cloud scanning
* ML model training
* subscription
* ads
* analytics dashboard
* desktop client
* iOS client
* remote device management

Do not expand scope because these features seem interesting.

---

# 39. Testing Strategy

Testing is part of the implementation.

All tests must be runnable on the host JVM (no device, no emulator — see section 0.1).

Required:

```text
Domain unit tests
Scanner unit tests
Fingerprint tests
Repository tests
Room tests
MediaStore abstraction tests
Permission/state tests
Compose UI tests (host-side, Robolectric)
Deletion/trash flow tests
Performance-oriented tests
```

Where Android framework APIs are involved, isolate them behind testable interfaces.

Do not make the majority of the code directly depend on ContentResolver.

Tests that require the Android framework (Room instrumentation-style DAO tests, Compose UI tests) must use Robolectric on the JVM so `./gradlew test` covers them. Files under `app/src/androidTest` must not be executed until every phase is complete.

---

# 40. Acceptance Tests — Fingerprinting

## Exact hash

Given two different URIs pointing to identical byte content:

Expected:

```text
same SHA-256
```

Given different byte content:

Expected:

```text
different SHA-256
```

Hashing must use streaming I/O.

---

## Same file metadata but different content

Given:

```text
same size
same MIME
same dimensions
different bytes
```

Expected:

```text
NOT exact duplicate
```

Cheap bucketing must never itself declare duplicates.

---

# 41. Acceptance Tests — Perceptual Hash

Given identical images resized/re-encoded with minor changes:

Expected:

```text
very small Hamming distance
```

Given completely unrelated images:

Expected:

```text
large Hamming distance
```

Threshold must be configurable internally.

Do not hardcode thresholds throughout the codebase.

---

# 42. Acceptance Tests — Candidate Classification

### Exact duplicate

Input:

```text
3 identical files
```

Expected:

```text
EXACT_DUPLICATE
HIGH
```

### Near duplicate

Input:

```text
2 strongly similar images
```

Expected:

```text
NEAR_DUPLICATE
MEDIUM or HIGH
```

### Large media

Input:

```text
video size = 1.8 GB
```

Expected:

```text
LARGE_FILE
```

### Old media

Input:

```text
dateModified < now - 180 days
```

Expected:

```text
OLD_MEDIA
```

### Screenshot

Input with multiple screenshot signals.

Expected:

```text
SCREENSHOT
```

Do not classify based on a single weak signal.

---

# 43. Acceptance Tests — Scan Behavior

## Empty library

Expected:

```text
scan completes successfully
0 media
0 candidates
```

## Permission denied

Expected:

```text
NO_PERMISSION
```

No crash.

## Partial media access

Expected:

```text
SCAN_PARTIAL
```

and UI must not claim that the complete library was scanned.

## Media disappears during scan

Expected:

```text
scan continues
missing item skipped
no global failure
```

## Corrupt file

Expected:

```text
file skipped
reason recorded
scan continues
```

---

# 44. Acceptance Tests — Performance

The scanner must be capable of processing a large synthetic dataset without loading the entire library into memory.

Test with:

```text
10,000+ media metadata records
```

Verify:

* no main-thread blocking
* no obvious unbounded memory growth
* cancellation works
* scanner progress is monotonic
* database writes are batched appropriately

Do not use a fake progress timer.

---

# 45. Acceptance Tests — Cancellation

Start a scan.

Cancel halfway.

Expected:

```text
scan stops cooperatively
database remains consistent
UI returns to safe state
partial results are not marked as complete
```

Restart scan.

Expected:

```text
application remains stable
```

---

# 46. Acceptance Tests — Trash Flow

Given selected media:

```text
3 items
```

Expected:

```text
user confirmation flow is invoked
```

After successful confirmation:

```text
items no longer appear as active media
candidate results refresh
storage summary refreshes
```

If the user cancels the system confirmation:

```text
no local deletion state is falsely recorded
```

The app must rely on actual MediaStore state after returning.

---

# 47. Acceptance Tests — Stale Index

Given:

```text
MediaItem exists in Room
```

but the URI no longer exists in MediaStore.

Expected:

```text
item detected as stale
removed from active candidate results
no crash
```

---

# 48. Room Tests

Test:

* insert media
* update media
* delete stale media
* persist candidate groups
* persist fingerprints
* restore scan state
* migration behavior

Use Room's testing facilities.

---

# 49. Compose UI Tests

Run these host-side with Robolectric (`createComposeRule` on the JVM). Do not require a device or emulator (section 0.1).

Test:

1. permission-required screen
2. empty library
3. scanning screen
4. scan progress
5. completed scan
6. partial scan
7. exact duplicate category
8. near duplicate category
9. large file category
10. screenshot category
11. old media category
12. candidate detail
13. review summary
14. trash confirmation result
15. dark theme
16. error states

---

# 50. Architecture Documentation

Create:

```text
docs/
  architecture.md
  scanning-pipeline.md
  privacy.md
  performance.md
  decisions/
    001-lightweight-clean-architecture.md
    002-mediastore-as-source-of-truth.md
    003-fingerprinting-strategy.md
    004-no-automatic-deletion.md
    005-local-first-design.md
    006-no-video-perceptual-matching.md
```

Each decision should document:

```text
Context
Decision
Alternatives
Trade-offs
Consequences
```

Example:

```text
Decision:
Use MediaStore as the source of truth instead of the Room database.

Reason:
Media can be changed or deleted outside the app.

Consequence:
Room acts as an index/cache rather than authoritative storage state.
```

---

# 51. Recommended Project Structure

Do not introduce unnecessary Gradle multi-module architecture for MVP.

Recommended:

```text
app/
  src/main/java/com/mediasweep/

    core/
      common/
      database/
      media/
      ui/

    data/
      local/
      media/
      repository/

    domain/
      model/
      repository/
      usecase/

    feature/
      home/
      scan/
      candidates/
      review/
      settings/

    scanner/
      grouping/
      hashing/
      classification/

    worker/

    MainActivity.kt
```

The exact package names may be adjusted after inspection.

---

# 52. Architecture Quality Rules

The implementation should demonstrate:

* Clean Architecture principles
* SOLID applied pragmatically
* dependency inversion
* unidirectional data flow
* immutable UI state
* testable scanner components
* isolated Android framework dependencies
* explicit error handling
* cancellation support

Do not turn the project into an abstraction showcase.

---

# 53. Agent Workflow

You are the implementation agent.

Required workflow:

```text
Inspect
→ Architecture Review
→ Plan
→ Implement
→ Test
→ Review
→ Fix
→ Verify
```

## Step 0 — Inspect

Before modifying anything:

1. inspect repository
2. inspect Gradle setup
3. inspect Android configuration
4. inspect target/min SDK
5. inspect existing dependencies
6. inspect package structure
7. inspect existing tests
8. inspect current architecture
9. determine compatible Android media APIs
10. identify reusable code

Do not rewrite working code blindly.

---

# 54. Architecture Review Before Code

Before implementation create:

```text
docs/architecture.md
```

The document must explain:

* architecture
* package boundaries
* state management
* scan pipeline
* database responsibilities
* MediaStore responsibilities
* fingerprint strategy
* background processing
* permission strategy
* deletion/trash strategy
* testing strategy
* major trade-offs

Do not begin feature implementation until this architectural review is complete.

If a materially different architecture is better, propose it before implementation.

---

# 55. Dependency Rules

Before adding any dependency:

1. inspect existing dependencies
2. determine whether AndroidX/Kotlin can solve the problem
3. check whether the library is actively maintained
4. check compatibility with the project's Android configuration
5. justify the dependency

Avoid:

* dependency bloat
* obsolete libraries
* duplicate libraries solving the same problem
* unnecessary image frameworks
* unnecessary ML libraries

Use dependencies compatible with the frozen project configuration (section 0.2). Never resolve a compatibility issue by upgrading Gradle, AGP, Kotlin, or the SDK.

---

# 56. No Fake Implementation

Do not create production placeholders such as:

```text
TODO
FIXME
fake scan results
hardcoded storage statistics
fake duplicate groups
fake progress
```

Mocks/fakes are allowed only in tests.

The application must use real MediaStore data in the final MVP.

---

# 57. Implementation Phases

## Phase 1 — Foundation

Implement:

* Android project
* Kotlin
* Jetpack Compose
* Material 3
* navigation
* lightweight Clean Architecture
* ViewModel + StateFlow + UDF
* Room
* dependency injection if justified
* CI
* architecture docs

Acceptance:

```text
build passes
lint passes
unit tests pass
debug APK assembles
architecture.md exists
```

App launch on a device/emulator is deferred until all phases are complete (section 0.1).

---

## Phase 2 — Permission + MediaStore

Implement:

* contextual permission flow
* Android version handling
* MediaStore query
* photo/video metadata
* empty/error states
* partial access state

Acceptance:

Host-side verification (no device, no emulator — section 0.1): Robolectric/fake data-source tests prove that when media access is granted the app queries MediaStore and lists real media metadata, and that full / partial / denied / revoked access states each map to the correct UI state. Real-device grant happens only in the final post-completion verification.

---

## Phase 3 — Media Index

Implement:

* Room entities
* repository
* incremental metadata synchronization
* stale item handling
* scan session state

Acceptance:

Media index survives process recreation and remains consistent with MediaStore.

---

## Phase 4 — Exact Duplicate Engine

Implement:

* cheap grouping
* streaming SHA-256
* duplicate grouping
* persistence

Acceptance:

Exact duplicate tests pass.

---

## Phase 5 — Image Near-Duplicate Engine

Implement:

* reduced image decoding
* EXIF orientation handling
* perceptual hash
* configurable threshold
* near-duplicate grouping

Acceptance:

Near-duplicate tests pass without excessive memory usage.

---

## Phase 6 — Candidate Classifiers

Implement:

* screenshot detection
* large-file detection
* old-media detection
* confidence levels
* candidate aggregation

Acceptance:

All classification tests pass.

---

## Phase 7 — Review UX

Implement:

* category screens
* group detail
* image comparison
* selection
* review summary
* total size calculation

Acceptance:

User can discover and select real cleanup candidates.

---

## Phase 8 — Trash Flow

Implement:

* system trash request
* user confirmation
* result handling
* MediaStore refresh
* database reconciliation

Acceptance:

Selected media can be moved to trash only after user confirmation.

---

## Phase 9 — Background / Incremental Scan

Implement:

* cancellable scan
* progress
* WorkManager if justified
* resume/restart behavior
* incremental updates

Acceptance:

Long scan remains stable and can recover from interruption.

---

## Phase 10 — Polish

Implement:

* dark/light theme
* accessibility
* error handling
* loading states
* performance refinements
* privacy documentation
* README
* screenshots
* demo GIF
* license notices

Screenshots and the demo GIF are the only items that need a device/emulator; produce them in the single post-completion run (section 0.1). Do not change any toolchain version while polishing (section 0.2).

---

# 58. README Requirements

README must lead with the user problem.

Do NOT begin with:

```text
Kotlin
Compose
Room
Coroutines
MediaStore
SHA-256
```

Start with:

# MediaSweep

> **Your gallery is full. See what is worth reviewing before you delete anything.**

Then:

## The Problem

Phones accumulate thousands of photos and videos, but finding duplicates, giant videos, screenshots, and old media manually is tedious.

## The Solution

MediaSweep scans the media library locally and groups useful cleanup candidates without uploading personal media anywhere.

Then show:

```text
Demo GIF
Screenshots
```

Then:

```text
Features
How it works
Architecture
Privacy
Performance
Testing
Limitations
Roadmap
License
```

---

# 59. README Privacy Statement

Clearly state:

```text
Media analysis happens locally on the device.

Media is not uploaded to a server by MediaSweep.

No account is required.
```

Only claim what the implementation actually guarantees.

---

# 60. Demo Scenario

This demo must NOT be executed until every phase (1 → 10) is complete (section 0.1). During implementation it is validated host-side: each numbered step maps to a JVM/Robolectric test, and the full on-device run happens once at the end.

The final MVP must support this complete demo:

```text
1. Launch MediaSweep
2. Grant media access
3. Start scan
4. Show real scan progress
5. Show storage summary
6. Open Exact Duplicates
7. Open one duplicate group
8. Compare items
9. Select items
10. Open Review Summary
11. Move selected items to Trash
12. Confirm through the Android system UI
13. Return to MediaSweep
14. Verify selected items disappeared from active candidates
15. Return to Home
16. Verify storage summary refreshed
```

Also demonstrate:

```text
partial media access
permission denied
scan cancellation
offline operation
```

The core scanner must work without internet access.

---

# 61. Final Definition of Done

The project is complete only when:

[ ] Build succeeds.

[ ] Unit tests pass.

[ ] Repository tests pass.

[ ] Room tests pass.

[ ] Compose UI tests pass (host-side / Robolectric).

[ ] Real MediaStore data is scanned.

[ ] Full/partial/denied permission states are handled.

[ ] Exact duplicate detection works.

[ ] Image near-duplicate detection works.

[ ] Screenshot detection works.

[ ] Large-file detection works.

[ ] Old-media detection works.

[ ] Scan can be cancelled safely.

[ ] Large media does not cause obvious memory problems.

[ ] Scan does not block the main thread.

[ ] MediaStore is treated as the source of truth.

[ ] Stale database entries are reconciled.

[ ] Selected media is never silently deleted.

[ ] Trash operations use the appropriate system confirmation flow.

[ ] Cancelled trash requests do not corrupt local state.

[ ] No media is uploaded to a backend.

[ ] No secrets exist in the repository.

[ ] Privacy documentation exists.

[ ] Architecture documentation exists.

[ ] ADRs exist for major architectural choices.

[ ] README explains the problem before the technology.

[ ] Demo flow works end-to-end.

[ ] No MVP non-goal was implemented unnecessarily.

[ ] Repository is clean enough for public GitHub.

Note: every checkbox above is verified host-side during implementation. The single device/emulator run (demo flow, screenshots, demo GIF) happens only after all phases are complete, per section 0.1.

---

# 62. Final Agent Reporting Format

At the end of every phase report:

```text
## Implemented
- ...

## Tests
- ...

## Failures
- ...

## Known Limitations
- ...

## Architecture Notes
- ...

## Performance Notes
- ...

## Next Phase
- ...
```

At final completion:

```text
## Final Verification

Build:
Lint:
Unit Tests:
Repository Tests:
Room Tests:
UI Tests:
MediaStore Validation:
Performance Validation:
Trash Flow:
APK:

Known Limitations:
```

The quality bar is:

```text
Correctness
+
Privacy
+
Performance
+
Reliability
+
Testability
+
Maintainability
+
Excellent UX
```

Prefer a smaller, extremely reliable cleanup utility over a large feature-heavy "AI cleaner".

The product should feel like a serious Android utility, not a tutorial project.
