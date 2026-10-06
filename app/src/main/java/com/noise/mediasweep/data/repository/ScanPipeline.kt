package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.CandidateGroupDao
import com.noise.mediasweep.core.database.dao.FingerprintDao
import com.noise.mediasweep.core.database.dao.MediaItemDao
import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.FingerprintEntity
import com.noise.mediasweep.data.media.MediaStoreDataSource
import com.noise.mediasweep.data.mapper.toDomain
import com.noise.mediasweep.domain.model.CandidateGroupDraft
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanPhase
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.scanner.classification.CandidateClassifier
import com.noise.mediasweep.scanner.classification.ExactDuplicateAnalyzer
import com.noise.mediasweep.scanner.classification.NearDuplicateAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What a finished scan produced. */
data class ScanOutcome(
    val status: ScanStatus,
    val mediaCount: Long,
    val mediaBytes: Long,
    /** Number of persisted groups per candidate type. */
    val groupCounts: Map<CandidateType, Int>,
    val errorMessage: String? = null,
)

/**
 * Runs the staged scan pipeline (specification §6):
 *
 * ```text
 * MediaStore query → cheap metadata → local index → cheap bucketing →
 * streaming SHA-256 → reduced decode + perceptual hash → classification → persist results
 * ```
 *
 * Each stage reports real counters, the whole run is cancellable, and the session is only
 * recorded as complete when every stage actually finished.
 */
class ScanPipeline(
    private val mediaStore: MediaStoreDataSource,
    private val synchronizer: MediaIndexSynchronizer,
    private val sessionStore: ScanSessionStore,
    private val mediaItemDao: MediaItemDao,
    private val fingerprintDao: FingerprintDao,
    private val candidateGroupDao: CandidateGroupDao,
    private val exactDuplicateAnalyzer: ExactDuplicateAnalyzer,
    private val nearDuplicateAnalyzer: NearDuplicateAnalyzer,
    private val candidateClassifier: CandidateClassifier,
    private val clock: () -> Long = System::currentTimeMillis,
) : ScanRunner {

    /** Bridges to [ScanRunner]; the whole run is cooperative with cancellation. */
    override suspend fun run(
        partialAccess: Boolean,
        onProgress: suspend (ScanProgress) -> Unit,
    ): ScanOutcome {
        val totalMedia = mediaStore.countMedia().toLong()
        val sessionId = sessionStore.startSession(partialAccess = partialAccess, totalCount = totalMedia)
        var foundCounts = emptyMap<CandidateType, Int>()

        return try {
            // 1. Index: stream metadata and reconcile the local index against MediaStore.
            onProgress(ScanProgress(0L, totalMedia, emptyMap(), ScanPhase.INDEXING))
            synchronizer.sync(sessionId) { processed, total ->
                onProgress(ScanProgress(processed, total, emptyMap(), ScanPhase.INDEXING))
            }

            val items = mediaItemDao.getActive().map { it.toDomain() }
            foundCounts = emptyMap()

            // 2. Fingerprints: cheap buckets first, hashing only for candidates that a
            // previous scan has not already fingerprinted (§20: the synchronizer drops
            // fingerprints of changed or removed rows, so what is left describes
            // unchanged media and does not need to be hashed again).
            val activeIds = items.mapTo(HashSet()) { it.id }
            val existingExact = fingerprintDao.getValues(FingerprintEntity.ALGORITHM_SHA256)
                .filter { it.mediaId in activeIds }
                .associate { it.mediaId to it.value }
            val existingNear = fingerprintDao.getValues(FingerprintEntity.ALGORITHM_PERCEPTUAL_HASH)
                .filter { it.mediaId in activeIds }
                .associate { it.mediaId to it.value }

            onProgress(ScanProgress(0L, 0L, foundCounts, ScanPhase.FINGERPRINTING))
            val exact = exactDuplicateAnalyzer.analyze(items, existingExact) { hashed, candidates ->
                onProgress(ScanProgress(hashed, candidates, foundCounts, ScanPhase.FINGERPRINTING))
            }
            // Publish what is really known so far so the UI can report found candidates
            // while the scan is still running (§27).
            foundCounts = foundCounts + (CandidateType.EXACT_DUPLICATE to exact.groups.size)

            // 3. Near-duplicate images: reduced decode + perceptual hash. Items already
            // claimed by an exact-duplicate group are excluded so one item never lands in
            // two groups of the same review flow, and videos are never perceptually hashed.
            val exactMemberIds = exact.groups.flatMapTo(HashSet()) { group ->
                group.items.map { it.id }
            }
            val near = nearDuplicateAnalyzer.analyze(
                items.filter { it.id !in exactMemberIds },
                existingNear,
            ) { processed, candidates ->
                onProgress(ScanProgress(processed, candidates, foundCounts, ScanPhase.FINGERPRINTING))
            }
            foundCounts = foundCounts + (CandidateType.NEAR_DUPLICATE to near.groups.size)

            // 4. Persist fingerprints the previous scan did not already know, and the
            // duplicate candidate groups.
            onProgress(ScanProgress(0L, 0L, foundCounts, ScanPhase.CLASSIFYING))
            exact.hashes
                .filterNot { it.mediaId in existingExact }
                .map {
                    FingerprintEntity(
                        mediaId = it.mediaId,
                        algorithm = FingerprintEntity.ALGORITHM_SHA256,
                        value = it.sha256,
                        calculatedAt = clock(),
                    )
                }
                .chunked(FINGERPRINT_BATCH)
                .forEach { batch -> fingerprintDao.upsertAll(batch) }
            near.hashes
                .filterNot { it.mediaId in existingNear }
                .map {
                    FingerprintEntity(
                        mediaId = it.mediaId,
                        algorithm = FingerprintEntity.ALGORITHM_PERCEPTUAL_HASH,
                        value = it.hex,
                        calculatedAt = clock(),
                    )
                }
                .chunked(FINGERPRINT_BATCH)
                .forEach { batch -> fingerprintDao.upsertAll(batch) }
            persistGroups(
                type = CandidateType.EXACT_DUPLICATE,
                drafts = exact.groups,
                sessionId = sessionId,
            )
            persistGroups(
                type = CandidateType.NEAR_DUPLICATE,
                drafts = near.groups,
                sessionId = sessionId,
            )

            // 5. Metadata classifiers: screenshot / large file / old media.
            val nowSeconds = clock() / 1000
            val classified = candidateClassifier.classifyAll(items, nowSeconds)
            foundCounts = foundCounts + METADATA_CATEGORIES.associateWith { type ->
                classified.count { it.type == type }
            }
            onProgress(
                ScanProgress(
                    processedCount = classified.size.toLong(),
                    totalCount = items.size.toLong(),
                    foundCounts = foundCounts,
                    phase = ScanPhase.CLASSIFYING,
                ),
            )
            for (type in METADATA_CATEGORIES) {
                persistGroups(
                    type = type,
                    drafts = classified.filter { it.type == type },
                    sessionId = sessionId,
                )
            }

            // 6. Aggregate the real outcome from the persisted state.
            val groupCounts = candidateGroupDao.typeTotals()
                .associate { CandidateType.valueOf(it.type) to it.groupCount.toInt() }
            foundCounts = groupCounts

            val totals = mediaItemDao.totals()
            sessionStore.complete(
                sessionId = sessionId,
                mediaCount = totals.count,
                mediaBytes = totals.bytes,
                partialAccess = partialAccess,
            )
            onProgress(ScanProgress(totals.count, totals.count, groupCounts, ScanPhase.CLASSIFYING))

            ScanOutcome(
                status = if (partialAccess) ScanStatus.PARTIAL else ScanStatus.COMPLETE,
                mediaCount = totals.count,
                mediaBytes = totals.bytes,
                groupCounts = groupCounts,
            )
        } catch (cancellation: CancellationException) {
            // Cancelled by the user: never leave a session pretending it is still running.
            withContext(NonCancellable) { sessionStore.recoverInterruptedSessions() }
            throw cancellation
        } catch (error: Exception) {
            val totals = mediaItemDao.totals()
            sessionStore.fail(sessionId, error.message ?: error::class.java.simpleName)
            ScanOutcome(
                status = ScanStatus.FAILED,
                mediaCount = totals.count,
                mediaBytes = totals.bytes,
                groupCounts = foundCounts,
                errorMessage = error.message,
            )
        }
    }

    private companion object {
        const val FINGERPRINT_BATCH = 500

        /** Categories produced by the metadata classifiers rather than by hashing. */
        val METADATA_CATEGORIES = listOf(
            CandidateType.SCREENSHOT,
            CandidateType.LARGE_FILE,
            CandidateType.OLD_MEDIA,
        )
    }

    private suspend fun persistGroups(
        type: CandidateType,
        drafts: List<CandidateGroupDraft>,
        sessionId: Long,
    ) {
        candidateGroupDao.deleteByType(type.name)
        drafts.forEach { draft ->
            val groupId = candidateGroupDao.insertGroup(
                CandidateGroupEntity(
                    type = draft.type.name,
                    confidence = draft.confidence.name,
                    totalSizeBytes = draft.totalSizeBytes,
                    itemCount = draft.items.size,
                    reason = draft.reason,
                    createdAt = clock(),
                    sessionId = sessionId,
                ),
            )
            candidateGroupDao.insertMembers(
                draft.items.mapIndexed { position, item ->
                    CandidateGroupMemberEntity(groupId = groupId, mediaId = item.id, position = position)
                },
            )
        }
    }
}
