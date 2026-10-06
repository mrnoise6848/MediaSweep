package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.ScanStateDao
import com.noise.mediasweep.core.database.dao.ScanSessionDao
import com.noise.mediasweep.data.mapper.statusOrDefault
import com.noise.mediasweep.data.mapper.toDomain
import com.noise.mediasweep.domain.model.ScanSession
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.domain.repository.ScanStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomScanStateRepository(
    private val scanStateDao: ScanStateDao,
    private val scanSessionDao: ScanSessionDao,
) : ScanStateRepository {

    override fun observeStatus(): Flow<ScanStatus> =
        scanStateDao.observe().map { state -> state?.statusOrDefault() ?: ScanStatus.NOT_SCANNED }

    override fun observeLastSession(): Flow<ScanSession?> =
        scanSessionDao.observeSessions().map { sessions -> sessions.firstOrNull()?.toDomain() }

    override suspend fun status(): ScanStatus =
        scanStateDao.get()?.statusOrDefault() ?: ScanStatus.NOT_SCANNED
}
