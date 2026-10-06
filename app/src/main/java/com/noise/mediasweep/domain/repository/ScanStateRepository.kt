package com.noise.mediasweep.domain.repository

import com.noise.mediasweep.domain.model.ScanSession
import com.noise.mediasweep.domain.model.ScanStatus
import kotlinx.coroutines.flow.Flow

/**
 * Read/observe the persisted state of scanning.
 *
 * The database is only an index: this repository never decides whether a file exists,
 * it only reports what the last scan recorded.
 */
interface ScanStateRepository {
    fun observeStatus(): Flow<ScanStatus>

    fun observeLastSession(): Flow<ScanSession?>

    suspend fun status(): ScanStatus
}
