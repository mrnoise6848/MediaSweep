package com.noise.mediasweep.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import com.noise.mediasweep.core.database.dao.MediaItemDao
import com.noise.mediasweep.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single query seam between the scanner and the Android framework.
 *
 * Everything else in the pipeline works with plain [MediaItem]s, which is what makes the
 * scan pipeline testable on the host JVM.
 */
fun interface MediaStoreQuery {
    fun query(
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
    ): android.database.Cursor?
}

class ContentResolverMediaStoreQuery(
    private val contentResolver: ContentResolver,
) : MediaStoreQuery {

    override fun query(
        projection: Array<String>,
        selection: String,
        selectionArgs: Array<String>,
        sortOrder: String,
    ): android.database.Cursor? = contentResolver.query(
        MediaStore.Files.getContentUri(EXTERNAL_VOLUME),
        projection,
        selection,
        selectionArgs,
        sortOrder,
    )

    companion object {
        const val EXTERNAL_VOLUME = "external"
    }
}

/**
 * Real MediaStore access.
 *
 * Only shared media storage is queried through `MediaStore`/`ContentResolver`; no arbitrary
 * filesystem path is ever opened. Trashed and pending items are excluded, and rows are
 * delivered in batches so memory stays bounded for libraries with tens of thousands of files.
 */
class AndroidMediaStoreDataSource(
    private val storeQuery: MediaStoreQuery,
) : MediaStoreDataSource {

    override suspend fun forEachMedia(
        batchSize: Int,
        onBatch: suspend (List<MediaItem>) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
            append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
        }
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )

        val cursor = storeQuery.query(
            projectionFor(Build.VERSION.SDK_INT),
            selection,
            selectionArgs,
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        ) ?: return@withContext 0

        cursor.use {
            var batch = ArrayList<MediaItem>(batchSize)
            var total = 0
            while (cursor.moveToNext()) {
                val item = MediaMetadataMapper.map(cursor) ?: continue
                batch.add(item)
                total++
                if (batch.size >= batchSize) {
                    onBatch(batch)
                    batch = ArrayList(batchSize)
                }
            }
            if (batch.isNotEmpty()) onBatch(batch)
            total
        }
    }

    override suspend fun countMedia(): Int = withContext(Dispatchers.IO) {
        val cursor = storeQuery.query(
            COUNT_PROJECTION,
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)",
            arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            ),
            "${MediaStore.MediaColumns._ID} ASC",
        ) ?: return@withContext 0
        cursor.use {
            var count = 0
            while (cursor.moveToNext()) count++
            count
        }
    }

    override suspend fun listMedia(): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        forEachMedia(BATCH_SIZE) { batch -> items.addAll(batch) }
        return items
    }

    override suspend fun activeIds(ids: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptySet()

        val wanted = ids.distinct()
        val active = HashSet<Long>(wanted.size)
        wanted.chunked(MediaItemDao.IN_CHUNK).forEach { chunk ->
            val selection = buildString {
                append("${MediaStore.MediaColumns._ID} IN (${chunk.joinToString(",") { "?" }})")
                append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)")
                append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Belt and braces: trashed rows are already filtered by default on
                    // Android 11+, but the post-confirmation check must be explicit.
                    append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0")
                }
            }
            val selectionArgs = chunk.map(Long::toString).toTypedArray() + arrayOf(
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
            )
            val cursor = storeQuery.query(COUNT_PROJECTION, selection, selectionArgs, "")
                ?: return@forEach
            cursor.use {
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (cursor.moveToNext()) active.add(cursor.getLong(idIndex))
            }
        }
        active
    }

    companion object {
        const val BATCH_SIZE = 500

        val COUNT_PROJECTION = arrayOf(MediaStore.MediaColumns._ID)

        /**
         * The API 30+ scan projection: every column, including the Android 11 flags.
         *
         * `IS_FAVORITE` and `IS_TRASHED` only exist in MediaStore from Android 11; asking
         * for them on Android 10 would make every scan query fail (constraint 0.2:
         * minSdk 29).
         */
        val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED,
        )

        /** Columns available on every supported API level (29+). */
        val BASE_PROJECTION = PROJECTION.filterNot {
            it == MediaStore.MediaColumns.IS_FAVORITE || it == MediaStore.MediaColumns.IS_TRASHED
        }.toTypedArray()

        /** Columns to request for the device this code is running on. */
        fun projectionFor(sdkInt: Int): Array<String> =
            if (sdkInt >= Build.VERSION_CODES.R) PROJECTION else BASE_PROJECTION
    }
}
