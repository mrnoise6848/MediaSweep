package com.noise.mediasweep.scanner.classification

import com.noise.mediasweep.domain.model.CandidateGroupDraft
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.scanner.grouping.CheapGrouper
import com.noise.mediasweep.scanner.hashing.StreamHasher
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A computed exact hash for one media item, kept for persistence as a fingerprint. */
data class MediaHash(
    val mediaId: Long,
    val sha256: String,
)

data class ExactDuplicateResult(
    val groups: List<CandidateGroupDraft>,
    val hashes: List<MediaHash>,
    /** Items whose content could not be read (deleted, revoked, corrupt). */
    val skippedCount: Int,
    val hashedCount: Int,
    /** Items whose fingerprint was already known from an earlier scan (§20). */
    val reusedCount: Int = 0,
)

/**
 * Exact duplicate detection (specification §7.1).
 *
 * Cheap buckets first, streaming SHA-256 only for bucket members, groups only when two
 * distinct items produce the same hash. Cheap bucketing alone never declares a duplicate.
 *
 * [existingHashes] carries fingerprints of unchanged media from a previous scan: those
 * items are grouped with their stored hash instead of being read again, so a repeated scan
 * only hashes what is new or modified (specification §20).
 */
class ExactDuplicateAnalyzer(
    private val hasher: StreamHasher,
    private val grouper: CheapGrouper = CheapGrouper(),
) {

    suspend fun analyze(
        items: List<MediaItem>,
        existingHashes: Map<Long, String> = emptyMap(),
        onProgress: suspend (hashedCount: Long, candidateCount: Long) -> Unit = { _, _ -> },
    ): ExactDuplicateResult {
        val buckets = grouper.buckets(items)
        val candidates = buckets.sumOf { it.items.size }

        val groups = ArrayList<CandidateGroupDraft>()
        val hashes = ArrayList<MediaHash>(candidates)
        var hashed = 0L
        var skipped = 0
        var reused = 0

        for (bucket in buckets) {
            val byHash = LinkedHashMap<String, MutableList<MediaItem>>(bucket.items.size)
            for (item in bucket.items) {
                // Cooperative cancellation between items.
                coroutineContext.ensureActive()

                val stored = existingHashes[item.id]
                val hash = stored ?: hasher.sha256(item.contentUri)
                if (stored != null) reused++
                if (hash == null) {
                    skipped++
                } else {
                    hashes.add(MediaHash(item.id, hash))
                    byHash.getOrPut(hash) { ArrayList(2) }.add(item)
                }
                hashed++
                onProgress(hashed, candidates.toLong())
            }

            byHash.values.filter { it.size > 1 }.forEach { identical ->
                groups.add(
                    CandidateGroupDraft(
                        type = CandidateType.EXACT_DUPLICATE,
                        confidence = Confidence.HIGH,
                        reason = "${identical.size} identical files: same SHA-256 and same size " +
                            "(${identical.first().mimeType ?: "unknown type"})",
                        items = identical,
                    ),
                )
            }
        }

        return ExactDuplicateResult(
            groups = groups.sortedByDescending { it.totalSizeBytes },
            hashes = hashes,
            skippedCount = skipped,
            hashedCount = hashed.toInt(),
            reusedCount = reused,
        )
    }
}
