package com.noise.mediasweep.scanner.classification

import com.noise.mediasweep.core.common.ageInDays
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.domain.model.CandidateGroupDraft
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.model.ScanThresholds
import java.util.Locale

/** Metadata-level classification of one item, before it is turned into a group draft. */
data class Classification(
    val type: CandidateType,
    val confidence: Confidence,
    /** Human readable explanation. Never a deletion claim (specification §7.5). */
    val reason: String,
)

/**
 * Screenshot detection from deterministic signals (specification §7.3).
 *
 * Never a single weak signal: classification requires at least one *strong* signal
 * (screenshot folder or screenshot filename pattern), and HIGH confidence requires
 * [ScanThresholds.screenshotMinSignals] or more agreeing signals in total. MIME type and
 * screen-shaped aspect ratio only ever act as supporting evidence.
 */
class ScreenshotClassifier(
    private val thresholds: ScanThresholds = ScanThresholds(),
) {

    fun classify(item: MediaItem): Classification? {
        if (!item.isImage) return null

        val signals = ArrayList<String>(4)
        var strongSignals = 0

        val folder = listOfNotNull(item.relativePath, item.bucketDisplayName).joinToString("/")
        if (folder.contains(FOLDER_KEYWORD, ignoreCase = true)) {
            signals.add("stored in a screenshot folder")
            strongSignals++
        }
        if (FILENAME_PATTERN.containsMatchIn(item.displayName)) {
            signals.add("screenshot filename pattern")
            strongSignals++
        }
        val mimeType = item.mimeType?.lowercase(Locale.US)
        if (mimeType != null && mimeType in SUPPORTED_WEAK_MIME_TYPES) {
            signals.add("PNG/WebP image format")
        }
        if (hasScreenShapedAspect(item)) {
            signals.add("screen-shaped aspect ratio")
        }

        // At least one strong signal is always required; weak signals alone never classify.
        if (strongSignals == 0) return null
        val confidence = if (signals.size >= thresholds.screenshotMinSignals) {
            Confidence.HIGH
        } else {
            Confidence.MEDIUM
        }
        return Classification(
            type = CandidateType.SCREENSHOT,
            confidence = confidence,
            reason = "${signals.size} screenshot signals: ${signals.joinToString()}",
        )
    }

    private fun hasScreenShapedAspect(item: MediaItem): Boolean {
        val width = item.width ?: return false
        val height = item.height ?: return false
        if (width <= 0 || height <= 0) return false
        val ratio = maxOf(width, height).toDouble() / minOf(width, height)
        return SCREEN_ASPECT_RATIOS.any { kotlin.math.abs(it - ratio) <= ASPECT_TOLERANCE }
    }

    private companion object {
        const val FOLDER_KEYWORD = "screenshot"

        /** "screenshot", "Screen_Shot", "screen shot", "screencap", ... (case-insensitive). */
        val FILENAME_PATTERN = Regex("(?i)(screen[ _-]?shots?|screencaps?)")

        val SUPPORTED_WEAK_MIME_TYPES = setOf("image/png", "image/webp")

        /** Common phone/tablet/desktop screen aspect ratios (long edge / short edge). */
        val SCREEN_ASPECT_RATIOS = doubleArrayOf(
            16.0 / 9.0, // 1.778
            16.0 / 10.0, // 1.600
            4.0 / 3.0, // 1.333 (tablets)
            3.0 / 2.0, // 1.500
            18.0 / 9.0, // 2.000
            19.5 / 9.0, // 2.167
            20.0 / 9.0, // 2.222
        )

        const val ASPECT_TOLERANCE = 0.03
    }
}

/** Large-file detection using the configurable thresholds (specification §7.4). */
class LargeFileClassifier(
    private val thresholds: ScanThresholds = ScanThresholds(),
) {

    fun classify(item: MediaItem): Classification? {
        val limit = if (item.isVideo) thresholds.largeVideoBytes else thresholds.largeImageBytes
        if (item.sizeBytes < limit) return null
        val kind = if (item.isVideo) "video" else "image"
        return Classification(
            type = CandidateType.LARGE_FILE,
            confidence = Confidence.HIGH,
            reason = "${formatBytes(item.sizeBytes)} $kind, above the " +
                "${formatBytes(limit)} large-$kind threshold",
        )
    }
}

/**
 * Old-media detection (specification §7.5): purely an age-based review criterion.
 *
 * The reason text always says "Older than N days" and never claims unused status,
 * because no actual usage data exists.
 */
class OldMediaClassifier(
    private val thresholds: ScanThresholds = ScanThresholds(),
) {

    fun classify(item: MediaItem, nowSeconds: Long): Classification? {
        if (item.dateModified >= thresholds.oldMediaCutoff(nowSeconds)) return null
        return Classification(
            type = CandidateType.OLD_MEDIA,
            confidence = Confidence.HIGH,
            reason = "Older than ${thresholds.oldMediaAgeDays} days " +
                "(last modified ${ageInDays(item.dateModified, nowSeconds)} days ago)",
        )
    }
}

/**
 * Runs every metadata classifier over the indexed library and aggregates the results
 * into candidate group drafts (specification §17 "CandidateClassifier").
 *
 * Singleton categories are persisted as one-item candidate groups: a screenshot or an
 * old video is a review candidate on its own, and inventing artificial clusters would
 * misrepresent the data. Sizes and counts are always derived from the real items.
 */
class CandidateClassifier(
    private val screenshotClassifier: ScreenshotClassifier = ScreenshotClassifier(),
    private val largeFileClassifier: LargeFileClassifier = LargeFileClassifier(),
    private val oldMediaClassifier: OldMediaClassifier = OldMediaClassifier(),
) {

    /** Classifications for one item; an item may belong to several categories. */
    fun classify(item: MediaItem, nowSeconds: Long): List<Classification> = buildList {
        screenshotClassifier.classify(item)?.let { add(it) }
        largeFileClassifier.classify(item)?.let { add(it) }
        oldMediaClassifier.classify(item, nowSeconds)?.let { add(it) }
    }

    /** One candidate group per classified item, ordered by size so big wins surface first. */
    fun classifyAll(
        items: List<MediaItem>,
        nowSeconds: Long,
    ): List<CandidateGroupDraft> {
        val drafts = ArrayList<CandidateGroupDraft>(items.size / 8)
        for (item in items) {
            for (classification in classify(item, nowSeconds)) {
                drafts.add(
                    CandidateGroupDraft(
                        type = classification.type,
                        confidence = classification.confidence,
                        reason = classification.reason,
                        items = listOf(item),
                    ),
                )
            }
        }
        return drafts.sortedByDescending { it.totalSizeBytes }
    }
}
