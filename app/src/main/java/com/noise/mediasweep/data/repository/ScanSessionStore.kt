package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.ScanSessionDao
import com.noise.mediasweep.core.database.dao.ScanStateDao
import com.noise.mediasweep.core.database.entity.ScanStateEntity
import com.noise.mediasweep.core.database.entity.ScanSessionEntity
import com.noise.mediasweep.domain.model.ScanStatus

/**
 * Owns scan session lifecycle and the persisted scan state.
 *
 * Rules enforced here (specification §19):
 *  - a scan is only recorded as complete when it actually finished,
 *  - an interrupted scan is recovered on the next launch and never keeps claiming to run,
 *  - the published state always describes what actually happened: SCANNING while a session
 *    runs, FAILED/STALE/NOT_SCANNED when it dies, COMPLETE/PARTIAL only on a real finish.
 */
class ScanSessionStore(
    private val sessionDao: ScanSessionDao,
    private val scanStateDao: ScanStateDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun startSession(partialAccess: Boolean, totalCount: Long): Long {
        val sessionId = sessionDao.insert(
            ScanSessionEntity(
                startedAt = clock(),
                finishedAt = null,
                status = ScanStatus.SCANNING.name,
                processedCount = 0L,
                totalCount = totalCount,
                partialAccess = partialAccess,
                errorMessage = null,
            ),
        )
        // Publish SCANNING so the UI can show a real running state (§34). Details of the
        // last successful scan are preserved so an interruption can fall back on them.
        val current = scanStateDao.get()
        scanStateDao.upsert(
            ScanStateEntity(
                status = ScanStatus.SCANNING.name,
                lastSuccessfulScanAt = current?.lastSuccessfulScanAt,
                lastSessionId = current?.lastSessionId,
                lastMediaCount = current?.lastMediaCount ?: 0L,
                lastMediaBytes = current?.lastMediaBytes ?: 0L,
                partialAccess = current?.partialAccess ?: false,
                updatedAt = clock(),
            ),
        )
        return sessionId
    }

    suspend fun updateProgress(sessionId: Long, processed: Long, total: Long) =
        sessionDao.updateProgress(id = sessionId, processed = processed, total = total)

    suspend fun complete(
        sessionId: Long,
        mediaCount: Long,
        mediaBytes: Long,
        partialAccess: Boolean,
    ) {
        val now = clock()
        val status = if (partialAccess) ScanStatus.PARTIAL else ScanStatus.COMPLETE
        sessionDao.finish(
            id = sessionId,
            finishedAt = now,
            status = status.name,
            processed = mediaCount,
            error = null,
        )
        scanStateDao.upsert(
            ScanStateEntity(
                status = status.name,
                lastSuccessfulScanAt = now,
                lastSessionId = sessionId,
                lastMediaCount = mediaCount,
                lastMediaBytes = mediaBytes,
                partialAccess = partialAccess,
                updatedAt = now,
            ),
        )
    }

    suspend fun fail(sessionId: Long, message: String) {
        sessionDao.finish(
            id = sessionId,
            finishedAt = clock(),
            status = ScanStatus.FAILED.name,
            processed = sessionDao.getById(sessionId)?.processedCount ?: 0L,
            error = message.take(MAX_ERROR_LENGTH),
        )
        // No completion claim, but the UI must not stay stuck on SCANNING: record the
        // failure while keeping the previous successful scan's numbers.
        scanStateDao.upsert(
            stateOrEmpty(scanStateDao.get()).copy(
                status = ScanStatus.FAILED.name,
                updatedAt = clock(),
            ),
        )
    }

    /**
     * Marks sessions that were still running when the process died. Called on startup so an
     * interrupted scan can never be reported as complete, and so the published scan state
     * never keeps claiming a scan that no longer exists.
     */
    suspend fun recoverInterruptedSessions(): Int {
        val interruptedSessions = sessionDao.markInterrupted(now = clock())
        val current = scanStateDao.get()
        if (current != null && current.status == ScanStatus.SCANNING.name) {
            scanStateDao.upsert(
                current.copy(
                    status = if (current.lastSuccessfulScanAt != null) {
                        // Results exist but the interrupted run left the index mid-update.
                        ScanStatus.STALE.name
                    } else {
                        ScanStatus.NOT_SCANNED.name
                    },
                    updatedAt = clock(),
                ),
            )
        }
        return interruptedSessions
    }

    suspend fun latestSession(): ScanSessionEntity? = sessionDao.latest()

    private fun stateOrEmpty(current: ScanStateEntity?): ScanStateEntity = current
        ?: ScanStateEntity(
            status = ScanStatus.NOT_SCANNED.name,
            lastSuccessfulScanAt = null,
            lastSessionId = null,
            lastMediaCount = 0L,
            lastMediaBytes = 0L,
            partialAccess = false,
            updatedAt = clock(),
        )

    companion object {
        private const val MAX_ERROR_LENGTH = 500
    }
}
