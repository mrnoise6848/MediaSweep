package com.noise.mediasweep.domain.model

/**
 * Status of the local index / last scan.
 *
 * Mirrors the home status states required by the specification:
 * NOT_SCANNED, SCANNING, SCAN_COMPLETE, SCAN_PARTIAL, SCAN_FAILED, STALE_RESULTS.
 * NO_PERMISSION is a presentation state and lives in the UI layer.
 */
enum class ScanStatus {
    /** No scan has ever completed. */
    NOT_SCANNED,
    /** A scan is running right now. */
    SCANNING,
    /** Last scan covered the full library. */
    COMPLETE,
    /** Last scan only covered selected/partial media access. */
    PARTIAL,
    /** Last scan ended with an error. */
    FAILED,
    /** Results exist but no longer match MediaStore; reconciliation is required. */
    STALE,
}

/** One scan run. */
data class ScanSession(
    val id: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val status: ScanStatus,
    val processedCount: Long,
    val totalCount: Long,
    val partialAccess: Boolean,
    val errorMessage: String?,
)

/** Stage of the current scan. Progress counters are always relative to the current phase. */
enum class ScanPhase {
    /** Reading metadata from MediaStore and reconciling the local index. */
    INDEXING,
    /** Computing fingerprints for candidate buckets. */
    FINGERPRINTING,
    /** Classifying candidates and persisting results. */
    CLASSIFYING,
}

/** Live progress of a running scan. Values are derived from actual work only. */
data class ScanProgress(
    val processedCount: Long,
    val totalCount: Long,
    val foundCounts: Map<CandidateType, Int> = emptyMap(),
    val phase: ScanPhase = ScanPhase.INDEXING,
) {
    val fraction: Float
        get() = if (totalCount <= 0L) 0f else (processedCount.toFloat() / totalCount).coerceIn(0f, 1f)
}

/** Snapshot shown on the home screen. */
data class StorageSummary(
    val totals: LibraryTotals,
    val categories: List<CategorySummary>,
    val status: ScanStatus,
    val partialAccess: Boolean,
    val lastCompletedScanAt: Long?,
) {
    fun category(type: CandidateType): CategorySummary? = categories.firstOrNull { it.type == type }

    val candidateBytes: Long
        get() = categories.sumOf { it.totalSizeBytes }
}

/**
 * Scanner thresholds.
 *
 * Kept in one place so they stay configurable internally and are never hardcoded
 * across the codebase (specification section 41).
 */
data class ScanThresholds(
    /** 20 MB */
    val largeImageBytes: Long = 20L * 1024 * 1024,
    /** 500 MB */
    val largeVideoBytes: Long = 500L * 1024 * 1024,
    /** Days */
    val oldMediaAgeDays: Long = 180,
    /** Hamming distance (of a 64-bit hash) at or below which two images are strong matches. */
    val nearDuplicateMaxHamming: Int = 6,
    /** Hamming distance at or below which two images are possible matches. */
    val nearDuplicatePossibleHamming: Int = 10,
    /** Minimum number of agreeing screenshot signals required to classify an image. */
    val screenshotMinSignals: Int = 2,
) {
    val oldMediaAgeSeconds: Long get() = oldMediaAgeDays * 24 * 60 * 60

    fun oldMediaCutoff(nowSeconds: Long): Long = nowSeconds - oldMediaAgeSeconds
}
