package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.CandidateGroupDao
import com.noise.mediasweep.core.database.dao.MediaItemDao
import com.noise.mediasweep.data.media.MediaStoreDataSource
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.repository.TrashReconciliation
import com.noise.mediasweep.domain.repository.TrashReconciler

/**
 * MediaStore-backed trash reconciliation (specification §11, §46).
 *
 * After the system confirmation returns, the actual MediaStore state of the requested
 * rows is re-queried — never the intent result — and only rows that really disappeared
 * are updated locally:
 *
 *  * the media row is flagged `isTrashed`, so it leaves active queries and totals;
 *  * candidate groups lose that member, with surviving members recounted and groups that
 *    no longer prove anything removed.
 *
 * Rows still present in MediaStore are left untouched, so a cancelled or partially
 * applied operation cannot record a deletion that did not happen.
 */
class MediaStoreTrashReconciler(
    private val mediaStore: MediaStoreDataSource,
    private val mediaItemDao: MediaItemDao,
    private val candidateGroupDao: CandidateGroupDao,
) : TrashReconciler {

    override suspend fun reconcile(requestedIds: List<Long>): TrashReconciliation {
        if (requestedIds.isEmpty()) return TrashReconciliation(emptyList(), emptyList())

        val requested = requestedIds.distinct()
        val present = mediaStore.activeIds(requested)
        val removed = requested.filterNot { it in present }
        val stillPresent = requested.filter { it in present }

        if (removed.isNotEmpty()) {
            mediaItemDao.setTrashed(removed, trashed = true)
            candidateGroupDao.removeMediaFromGroups(removed, DUPLICATE_TYPES)
        }

        return TrashReconciliation(removedIds = removed, stillPresentIds = stillPresent)
    }

    private companion object {
        /** Groups of these types only exist while they hold at least two members. */
        val DUPLICATE_TYPES = listOf(
            CandidateType.EXACT_DUPLICATE.name,
            CandidateType.NEAR_DUPLICATE.name,
        )
    }
}
