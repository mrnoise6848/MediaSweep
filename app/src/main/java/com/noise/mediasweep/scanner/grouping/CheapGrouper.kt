package com.noise.mediasweep.scanner.grouping

import com.noise.mediasweep.domain.model.MediaItem

/** A set of items that share cheap metadata and is therefore worth expensive checks. */
data class CheapBucket(
    val key: String,
    val items: List<MediaItem>,
)

/**
 * Cheap bucketing (specification §6).
 *
 * Groups by metadata MediaStore already provides: byte size, MIME type, dimensions and
 * duration. A bucket with a single member can never be a duplicate, so it is dropped here —
 * this is where most of the library is eliminated without touching file content.
 *
 * A bucket is never a "duplicate group": only a hash comparison may declare that.
 */
class CheapGrouper {

    fun buckets(items: List<MediaItem>): List<CheapBucket> {
        val grouped = HashMap<List<String>, MutableList<MediaItem>>()
        items.forEach { item ->
            val key = listOf(
                item.sizeBytes.toString(),
                item.mimeType ?: "unknown-mime",
                (item.width ?: -1).toString(),
                (item.height ?: -1).toString(),
                (item.durationMs ?: -1L).toString(),
            )
            grouped.getOrPut(key) { ArrayList(2) }.add(item)
        }
        return grouped
            .filterValues { it.size > 1 }
            .map { (key, members) -> CheapBucket(key.joinToString("|"), members) }
            .sortedByDescending { it.items.size }
    }
}
