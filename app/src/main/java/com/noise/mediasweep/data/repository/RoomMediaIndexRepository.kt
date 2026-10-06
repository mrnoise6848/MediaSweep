package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.MediaItemDao
import com.noise.mediasweep.domain.model.LibraryTotals
import com.noise.mediasweep.domain.repository.MediaIndexRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMediaIndexRepository(
    private val mediaItemDao: MediaItemDao,
) : MediaIndexRepository {

    override fun observeTotals(): Flow<LibraryTotals> = mediaItemDao.observeTotals().map { totals ->
        LibraryTotals(mediaCount = totals.count, mediaBytes = totals.bytes)
    }

    override suspend fun totals(): LibraryTotals = mediaItemDao.totals().let { totals ->
        LibraryTotals(mediaCount = totals.count, mediaBytes = totals.bytes)
    }
}
