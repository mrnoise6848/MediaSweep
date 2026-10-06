package com.noise.mediasweep.domain.usecase

import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.domain.model.StorageSummary
import com.noise.mediasweep.domain.repository.CandidateRepository
import com.noise.mediasweep.domain.repository.MediaIndexRepository
import com.noise.mediasweep.domain.repository.ScanStateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Produces the home screen snapshot by combining the scan state, the local media index
 * and the persisted candidate categories. Every number shown is derived from real data.
 */
class ObserveStorageSummaryUseCase(
    private val scanStateRepository: ScanStateRepository,
    private val mediaIndexRepository: MediaIndexRepository,
    private val candidateRepository: CandidateRepository,
) {
    operator fun invoke(): Flow<StorageSummary> = combine(
        scanStateRepository.observeStatus(),
        scanStateRepository.observeLastSession(),
        mediaIndexRepository.observeTotals(),
        candidateRepository.observeCategorySummaries(),
    ) { status, session, totals, categories ->
        StorageSummary(
            totals = totals,
            categories = categories,
            status = status,
            partialAccess = status == ScanStatus.PARTIAL,
            lastCompletedScanAt = session
                ?.takeIf { it.status == ScanStatus.COMPLETE || it.status == ScanStatus.PARTIAL }
                ?.finishedAt,
        )
    }
}
