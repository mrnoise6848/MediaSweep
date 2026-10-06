package com.noise.mediasweep.scanner.classification

import com.noise.mediasweep.domain.model.CandidateGroupDraft
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.model.ScanThresholds
import com.noise.mediasweep.scanner.grouping.HammingGrouping
import com.noise.mediasweep.scanner.image.DctImageHasher
import com.noise.mediasweep.scanner.image.HashDistance
import com.noise.mediasweep.scanner.image.ImageHasher
import com.noise.mediasweep.scanner.image.ReducedImageDecoder
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A computed perceptual hash for one media item, kept for persistence. */
data class PerceptualHashResult(
    val mediaId: Long,
    val hash: ULong,
) {
    /** Fixed-width hexadecimal form used by the fingerprint table. */
    val hex: String get() = hash.toString(16).padStart(16, '0')
}

data class NearDuplicateResult(
    val groups: List<CandidateGroupDraft>,
    val hashes: List<PerceptualHashResult>,
    /** Images that produced a hash. */
    val hashedCount: Int,
    /** Images that could not be decoded; skipped with reason, never failing the scan. */
    val skippedCount: Int,
    /** Images whose perceptual hash was already known from an earlier scan (§20). */
    val reusedCount: Int = 0,
)

/**
 * Near-duplicate image detection (specification §7.2, Phase 5).
 *
 * Pipeline: reduced decode → EXIF-normalized grayscale → perceptual hash → Hamming
 * distance → clustering. Decoding is strictly sequential, so at most one reduced bitmap
 * is alive at a time (§21: no full-size or simultaneous decodes).
 *
 * Clustering uses [ScanThresholds.nearDuplicatePossibleHamming] as the candidate radius;
 * a cluster is only surfaced when its worst pair is within
 * [ScanThresholds.nearDuplicateMaxHamming] (HIGH confidence, "strong matches by default"),
 * otherwise it is reported as MEDIUM ("probably similar").
 */
class NearDuplicateAnalyzer(
    private val decoder: ReducedImageDecoder,
    private val hasher: ImageHasher = DctImageHasher(),
    private val thresholds: ScanThresholds = ScanThresholds(),
    /** Clusters larger than this skip the exact diameter computation (bounded work). */
    private val maxDiameterClusterSize: Int = 256,
) {

    /**
     * @param existingHashes perceptual hashes of unchanged images from a previous scan;
     *   those images are grouped from the stored value instead of being decoded again
     *   (specification §20 incremental scanning).
     */
    suspend fun analyze(
        items: List<MediaItem>,
        existingHashes: Map<Long, String> = emptyMap(),
        onProgress: suspend (processedCount: Long, totalCount: Long) -> Unit = { _, _ -> },
    ): NearDuplicateResult {
        val images = items.filter { it.isImage }
        val hashes = ArrayList<PerceptualHashResult>(images.size)
        val hashedItems = ArrayList<MediaItem>(images.size)
        var skipped = 0
        var processed = 0L
        var reused = 0

        for (item in images) {
            // Cooperative cancellation between decodes.
            coroutineContext.ensureActive()

            val stored = existingHashes[item.id]?.toULongOrNull(16)
            if (stored != null) {
                hashes.add(PerceptualHashResult(item.id, stored))
                hashedItems.add(item)
                reused++
            } else {
                val reduced = decoder.decode(item)
                if (reduced == null) {
                    skipped++
                } else {
                    hashes.add(PerceptualHashResult(item.id, hasher.hash(reduced).pHash))
                    hashedItems.add(item)
                }
            }
            processed++
            onProgress(processed, images.size.toLong())
        }

        return NearDuplicateResult(
            groups = group(hashedItems, hashes),
            hashes = hashes,
            hashedCount = hashedItems.size,
            skippedCount = skipped,
            reusedCount = reused,
        )
    }

    /** Clusters hashed items by perceptual hash distance and turns clusters into drafts. */
    internal fun group(
        items: List<MediaItem>,
        hashes: List<PerceptualHashResult>,
    ): List<CandidateGroupDraft> {
        require(items.size == hashes.size) { "items and hashes must be parallel" }
        if (items.size < 2) return emptyList()

        val clusters = HammingGrouping.group(
            hashes = hashes.map { it.hash },
            radius = thresholds.nearDuplicatePossibleHamming,
        )

        return clusters.asSequence()
            .filter { it.size > 1 }
            .map { cluster ->
                val clusterHashes = cluster.map { hashes[it].hash }
                val diameter = maxPairwiseDistance(clusterHashes)
                val strong = diameter <= thresholds.nearDuplicateMaxHamming
                val confidence = if (strong) Confidence.HIGH else Confidence.MEDIUM
                val distanceText = if (diameter == Int.MAX_VALUE) {
                    "grouped by perceptual hash"
                } else {
                    "hash distance up to $diameter"
                }
                CandidateGroupDraft(
                    type = CandidateType.NEAR_DUPLICATE,
                    confidence = confidence,
                    reason = "${cluster.size} visually similar images ($distanceText, " +
                        "perceptual hashing; review before deleting)",
                    items = cluster.map { items[it] },
                )
            }
            .sortedByDescending { it.totalSizeBytes }
            .toList()
    }

    /**
     * Worst pairwise Hamming distance in the cluster; [Int.MAX_VALUE] once the cluster is
     * too large to justify an exact O(n^2) pass (such clusters are never HIGH confidence).
     */
    private fun maxPairwiseDistance(clusterHashes: List<ULong>): Int {
        if (clusterHashes.size > maxDiameterClusterSize) return Int.MAX_VALUE
        var max = 0
        for (i in clusterHashes.indices) {
            for (j in i + 1 until clusterHashes.size) {
                val distance = HashDistance.hamming(clusterHashes[i], clusterHashes[j])
                if (distance > max) max = distance
            }
        }
        return max
    }
}
