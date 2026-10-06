# MediaSweep — Privacy

MediaSweep is a local utility, not a service. The position is simple:

> **Nothing about your photo library leaves your device.**

## 1. What the app can access

| Permission | Why |
| --- | --- |
| `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | read the library to scan it |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ *selected* (partial) access |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`) | same purpose on Android 12 and 12L |

* Access is **read-only**: MediaStore metadata (name, size, dimensions, dates, folder
  bucket, MIME type) plus pixel data that is decoded in memory for hashing and thumbnails.
* The only mutation MediaSweep can perform is a **system trash request**, and that happens
  only after you explicitly confirm it in the Android system UI
  (`docs/decisions/004-no-automatic-deletion.md`). Nothing is ever deleted silently.

## 2. What is stored on the device

One Room database (`mediasweep.db`), containing:

| Table | Contents |
| --- | --- |
| `media_items` | metadata mirrored from MediaStore (a cache, not a copy of your files) |
| `fingerprints` | SHA-256 of file *bytes* and a 64-bit perceptual hash of reduced images |
| `candidate_groups` / `candidate_group_members` | the review groups and their reasons |
| `scan_state`, `scan_sessions` | scan status, totals and timings |

Properties of that database:

* it is **derived data** — deleting the app (or its data) recreates it with the next scan;
* it contains **no media bytes**, no full-size images, and no thumbnails (thumbnails are
  decoded in memory only while the screen needs them);
* it contains no EXIF beyond what the scan reads transiently to orient an image before
  hashing (orientation itself is not persisted);
* it is never synced anywhere.

## 3. What is never collected or sent

* **No `INTERNET` permission** in the manifest — the process cannot open a network socket.
  The scanner works in airplane mode (specification §60).
* **No network code**: the sources contain no HTTP client, no URL fetching, no socket use.
* **No analytics, no crash reporting, no ads**: dependencies are AndroidX, Kotlin and
  coroutines only (test-only: JUnit/Robolectric).
* **No logging**: production sources do not call `android.util.Log`, so media names and
  paths never end up in logcat through MediaSweep.
* **Android Auto Backup is disabled** (`android:allowBackup="false"`), so the local index —
  file names, sizes, dates and hash values — is not uploaded by the system backup
  transport either.
* **No account, no server, no telemetry of any kind.**

## 4. Accounts

None. There is no sign-in, no identifier and no device fingerprint. The app is fully
usable offline.

## 5. Deletion and trash

Moving items to trash is a two-step, user-confirmed operation:

1. MediaSweep issues `MediaStore.createTrashRequest` (API 30+);
2. the system shows its own confirmation; only on success does MediaSweep refresh its
   index.

If you cancel the system dialog, MediaSweep records **nothing** — it re-reads MediaStore
afterwards instead of trusting the intent result (`docs/architecture.md` §9). On Android 10
(min SDK) there is no system trash request at all, so the app reports the limitation and
deletes nothing.

## 6. How to verify the claims

```bash
# 1. No network permission
grep -n "INTERNET" app/src/main/AndroidManifest.xml     # no output

# 2. No network usage in the app sources
grep -rn "java.net\|okhttp\|retrofit\|https://" app/src/main/java   # no output

# 3. No logging of user data
grep -rn "android.util.Log" app/src/main/java           # no output

# 4. No cloud backup of the local index
grep -n "allowBackup" app/src/main/AndroidManifest.xml  # allowBackup="false"

# 5. Offline operation
./gradlew assembleDebug   # the APK is built and runs with the network disabled
```

## 7. Honest limits

* The scanner reads pixel data (downscaled) locally; that is unavoidable for duplicate
  detection and happens entirely in the app process.
* Moving files to trash is handled by Android; the system's own retention (typically 30
  days) and any other apps' behaviour apply afterwards — MediaSweep does not control them.
* The index describes your library (names, sizes, dates). It stays on the device; if you
  want it gone, clear the app's data.
