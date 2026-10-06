package com.noise.mediasweep.domain.repository

import com.noise.mediasweep.domain.model.LibraryTotals
import kotlinx.coroutines.flow.Flow

/** Read-only view over the local media index. */
interface MediaIndexRepository {
    fun observeTotals(): Flow<LibraryTotals>

    suspend fun totals(): LibraryTotals
}
