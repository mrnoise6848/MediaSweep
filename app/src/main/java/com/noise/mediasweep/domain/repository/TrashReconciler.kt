package com.noise.mediasweep.domain.repository

/**
 * Outcome of aligning the local index with MediaStore after a trash confirmation
 * (specification §11, §46).
 */
data class TrashReconciliation(
    /** Requested rows MediaStore no longer shows as active media. */
    val removedIds: List<Long>,

    /** Requested rows MediaStore still shows as active media. */
    val stillPresentIds: List<Long>,
) {
    val anyRemoved: Boolean get() = removedIds.isNotEmpty()
}

/**
 * Re-queries MediaStore after the system confirmation and updates the local index to match
 * the real outcome.
 *
 * The intent result alone is never trusted: only what MediaStore actually reports counts
 * as changed (specification §11: "Re-query actual state after returning").
 */
interface TrashReconciler {

    /**
     * Reconciles [requestedIds] against MediaStore.
     *
     * @return which requested items are really gone from the active library and which
     *   are still present (for example when the user cancelled part of the flow).
     */
    suspend fun reconcile(requestedIds: List<Long>): TrashReconciliation
}
