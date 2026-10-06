package com.noise.mediasweep.domain.model

/** Candidate categories supported by the MVP. */
enum class CandidateType {
    EXACT_DUPLICATE,
    NEAR_DUPLICATE,
    SCREENSHOT,
    LARGE_FILE,
    OLD_MEDIA,
}

/** How confident the classifier is that an item belongs to a candidate category. */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    ;

    /** Only strong matches are surfaced by default. */
    val isSurfaced: Boolean get() = this == HIGH || this == MEDIUM
}

/** A group of media items surfaced together as something worth reviewing. */
data class CandidateGroup(
    val id: Long,
    val type: CandidateType,
    val confidence: Confidence,
    val totalSizeBytes: Long,
    val itemCount: Int,
    /** Human readable explanation of why this group was surfaced. Never a deletion claim. */
    val reason: String,
)

/** A candidate group together with the media it contains. */
data class CandidateGroupWithItems(
    val group: CandidateGroup,
    val items: List<MediaItem>,
)

/** Aggregated figures for one candidate category. */
data class CategorySummary(
    val type: CandidateType,
    val groupCount: Int,
    val itemCount: Int,
    val totalSizeBytes: Long,
) {
    val isEmpty: Boolean get() = groupCount == 0
}

/** Totals for the whole library, derived from the scan. */
data class LibraryTotals(
    val mediaCount: Long,
    val mediaBytes: Long,
)

/**
 * A candidate group produced by an analyzer, before it is persisted.
 * Sizes and counts are derived from the items, never invented.
 */
data class CandidateGroupDraft(
    val type: CandidateType,
    val confidence: Confidence,
    /** Human readable explanation of why this group was surfaced. */
    val reason: String,
    val items: List<MediaItem>,
) {
    val totalSizeBytes: Long get() = items.sumOf { it.sizeBytes }
}
