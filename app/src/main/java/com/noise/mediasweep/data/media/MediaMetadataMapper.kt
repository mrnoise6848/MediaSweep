package com.noise.mediasweep.data.media

import android.content.ContentUris
import android.database.Cursor
import android.provider.MediaStore
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.model.MediaType

/**
 * Reads metadata rows from MediaStore.
 *
 * Isolated behind this interface so grouping, hashing and classification can be tested on the
 * JVM without a device, and so the rest of the app never touches [ContentResolver] directly.
 */
interface MediaStoreDataSource {
    /**
     * Number of photos and videos currently visible. Cheap enough to call before a scan so
     * progress can be reported against a real total.
     */
    suspend fun countMedia(): Int

    /**
     * Streams every photo and video in batches so a large library is never held in memory at
     * once. Returns the number of rows observed.
     */
    suspend fun forEachMedia(
        batchSize: Int,
        onBatch: suspend (List<MediaItem>) -> Unit,
    ): Int

    /**
     * Which of [ids] are still visible as active media in MediaStore (present, not
     * trashed, not pending).
     *
     * Called after a trash confirmation so the local index follows the real MediaStore
     * state instead of the intent result (specification §11, §46).
     */
    suspend fun activeIds(ids: List<Long>): Set<Long>

    suspend fun listMedia(): List<MediaItem>
}

/** Maps a MediaStore cursor row to a [MediaItem]; returns null for rows that cannot be read. */
object MediaMetadataMapper {

    fun map(cursor: Cursor): MediaItem? = try {
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val id = cursor.getLong(idIndex)
        val uri = ContentUris.withAppendedId(
            MediaStore.Files.getContentUri("external"),
            id,
        ).toString()

        MediaItem(
            id = id,
            contentUri = uri,
            mediaType = MediaType.fromMediaStore(
                cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)),
            ),
            displayName = cursor.getStringOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
            mimeType = cursor.getStringOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)),
            sizeBytes = cursor.getLongOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
            width = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)),
            height = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)),
            durationMs = cursor.getLongOrNull(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)),
            dateAdded = cursor.getLongOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)),
            dateModified = cursor.getLongOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)),
            relativePath = cursor.getStringOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)),
            bucketId = cursor.getStringOr(cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)),
            bucketDisplayName = cursor.getStringOr(
                cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
            ),
            isFavorite = cursor.getIntOr(cursor.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)) != 0,
            isPending = cursor.getIntOr(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PENDING)) != 0,
            isTrashed = cursor.getIntOr(cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)) != 0,
            isStale = false,
        )
    } catch (e: Exception) {
        // A single unreadable row must never abort a scan.
        null
    }

    private fun Cursor.getStringOr(index: Int): String? =
        if (index < 0 || isNull(index)) null else getString(index)

    private fun Cursor.getLongOr(index: Int): Long = if (index < 0 || isNull(index)) 0L else getLong(index)

    private fun Cursor.getIntOr(index: Int): Int = if (index < 0 || isNull(index)) 0 else getInt(index)

    private fun Cursor.getIntOrNull(index: Int): Int? =
        if (index < 0 || isNull(index)) null else getInt(index)

    private fun Cursor.getLongOrNull(index: Int): Long? =
        if (index < 0 || isNull(index)) null else getLong(index)
}
