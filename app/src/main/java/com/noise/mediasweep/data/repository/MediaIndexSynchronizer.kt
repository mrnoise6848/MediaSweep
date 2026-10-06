package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.CandidateGroupDao
import com.noise.mediasweep.core.database.dao.FingerprintDao
import com.noise.mediasweep.core.database.dao.MediaItemDao
import com.noise.mediasweep.data.mapper.toEntity
import com.noise.mediasweep.data.media.AndroidMediaStoreDataSource
import com.noise.mediasweep.data.media.MediaStoreDataSource
import com.noise.mediasweep.domain.model.MediaItem
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Outcome of one incremental index synchronisation. */
data class IndexSyncResult(
    /** False when the sync was cancelled before it finished. */
    val completed: Boolean,
    val mediaCount: Long,
    val mediaBytes: Long,
    val addedCount: Int,
    val modifiedCount: Int,
    val removedCount: Int,
    val unchangedCount: Int,
) {
    val sawChanges: Boolean
        get() = addedCount > 0 || modifiedCount > 0 || removedCount > 0
}

/**
 * Incremental synchronisation between MediaStore and the local index (specification §20).
 *
 * Only cheap metadata (id, dateModified, sizeBytes) is compared, so an unchanged library
 * costs one metadata query and no writes. Rows that disappeared are marked stale rather than
 * dropped, and any derived data that depended on changed rows (fingerprints, candidate groups)
 * is invalidated instead of being left to rot.
 */
class MediaIndexSynchronizer(
    private val mediaStore: MediaStoreDataSource,
    private val mediaItemDao: MediaItemDao,
    private val fingerprintDao: FingerprintDao,
    private val candidateGroupDao: CandidateGroupDao,
    private val batchSize: Int = AndroidMediaStoreDataSource.BATCH_SIZE,
) {

    suspend fun sync(
        sessionId: Long? = null,
        onProgress: suspend (processed: Long, total: Long) -> Unit = { _, _ -> },
    ): IndexSyncResult {
        val existing = mediaItemDao.keys().associateBy { it.id }
        val total = mediaStore.countMedia().toLong()

        var added = 0
        var modified = 0
        var unchanged = 0
        var processed = 0L
        val seen = HashSet<Long>()
        val changedIds = ArrayList<Long>()
        val unchangedIds = ArrayList<Long>()

        mediaStore.forEachMedia(batchSize) { batch ->
            // Cooperative cancellation: checked before any further work is started.
            coroutineContext.ensureActive()

            val toUpsert = ArrayList<MediaItem>(batch.size)
            batch.forEach { item ->
                seen.add(item.id)
                val previous = existing[item.id]
                when {
                    previous == null -> {
                        added++
                        toUpsert.add(item)
                    }

                    previous.dateModified != item.dateModified || previous.sizeBytes != item.sizeBytes -> {
                        modified++
                        changedIds.add(item.id)
                        toUpsert.add(item)
                    }

                    else -> {
                        unchanged++
                        unchangedIds.add(item.id)
                    }
                }
            }

            if (toUpsert.isNotEmpty()) {
                mediaItemDao.upsertAll(
                    toUpsert.map { it.toEntity().copy(lastSeenSessionId = sessionId) },
                )
            }
            processed += batch.size
            onProgress(processed, total)
        }

        val disappeared = existing.keys.filterNot { it in seen }
        val invalidated = changedIds + disappeared

        mediaItemDao.reconcile(seen)
        if (sessionId != null && unchangedIds.isNotEmpty()) {
            mediaItemDao.markSeen(unchangedIds, sessionId)
        }
        if (changedIds.isNotEmpty()) fingerprintDao.deleteForMedia(changedIds)
        if (invalidated.isNotEmpty()) candidateGroupDao.deleteGroupsContaining(invalidated)

        val totals = mediaItemDao.totals()
        return IndexSyncResult(
            completed = true,
            mediaCount = totals.count,
            mediaBytes = totals.bytes,
            addedCount = added,
            modifiedCount = modified,
            removedCount = disappeared.size,
            unchangedCount = unchanged,
        )
    }
}
