package com.noise.mediasweep.testing

import com.noise.mediasweep.data.media.MediaStoreDataSource
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.model.MediaType
import com.noise.mediasweep.scanner.image.GrayImage
import com.noise.mediasweep.scanner.image.ReducedImageDecoder
import kotlin.math.cos
import kotlin.math.sin

/** Deterministic media metadata for host-side tests (fakes are allowed in tests only). */
fun mediaItem(
    id: Long,
    sizeBytes: Long = 1_024L,
    dateModified: Long = 1_700_000_000L,
    displayName: String = "IMG_$id.jpg",
    mediaType: MediaType = MediaType.IMAGE,
    mimeType: String? = null,
    width: Int? = 100,
    height: Int? = 100,
    relativePath: String? = "DCIM/Camera",
    bucketDisplayName: String? = "Camera",
    isTrashed: Boolean = false,
) = MediaItem(
    id = id,
    contentUri = "content://media/$id",
    mediaType = mediaType,
    displayName = displayName,
    mimeType = mimeType ?: if (mediaType == MediaType.VIDEO) "video/mp4" else "image/jpeg",
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = if (mediaType == MediaType.VIDEO) 10_000L else null,
    dateAdded = dateModified,
    dateModified = dateModified,
    relativePath = relativePath,
    bucketId = "bucket",
    bucketDisplayName = bucketDisplayName,
    isFavorite = false,
    isPending = false,
    isTrashed = isTrashed,
    isStale = false,
)

class FakeMediaStoreDataSource(initial: List<MediaItem> = emptyList()) : MediaStoreDataSource {

    var items: List<MediaItem> = initial

    /** How often [activeIds] was consulted (used to assert post-confirmation verification). */
    var activeIdsQueries = 0

    override suspend fun countMedia(): Int = items.size

    override suspend fun activeIds(ids: List<Long>): Set<Long> {
        activeIdsQueries++
        val wanted = ids.toHashSet()
        return items.asSequence()
            .filter { it.id in wanted && !it.isTrashed && !it.isPending && !it.isStale }
            .map { it.id }
            .toSet()
    }

    override suspend fun forEachMedia(
        batchSize: Int,
        onBatch: suspend (List<MediaItem>) -> Unit,
    ): Int {
        items.chunked(batchSize.coerceAtLeast(1)).forEach { batch -> onBatch(batch) }
        return items.size
    }

    override suspend fun listMedia(): List<MediaItem> = items
}

/**
 * Reduced decoder returning pre-seeded images per content URI; anything not seeded is
 * treated as undecodable (null), matching the SKIP_WITH_REASON contract.
 */
class FakeReducedImageDecoder(
    private val images: Map<String, GrayImage> = emptyMap(),
) : ReducedImageDecoder {

    var decodeCount: Int = 0
        private set

    override suspend fun decode(item: MediaItem): GrayImage? {
        decodeCount++
        return images[item.contentUri]
    }
}

/**
 * Deterministic synthetic photo-like pattern in normalized coordinates, so the same seed
 * rendered at different resolutions represents "the same picture" (near-duplicate pair),
 * while different seeds produce visibly different pictures (unrelated pair).
 *
 * Frequencies stay below four cycles per image so the energy lives inside the 8x8
 * low-frequency DCT block that pHash retains; values stay within 37..217 so
 * brightness-shift variants never clamp.
 */
fun patternImage(seed: Long, size: Int = 64): GrayImage {
    val frequencyA = 1.2 + (seed % 6) * 0.4
    val frequencyB = 1.0 + (seed % 4) * 0.6
    val frequencyC = 1.5 + (seed % 5) * 0.4
    val phaseA = (seed * 37L % 628L) / 100.0
    val phaseB = (seed * 91L % 628L) / 100.0
    val phaseC = (seed * 53L % 628L) / 100.0
    val pixels = FloatArray(size * size) { index ->
        val x = (index % size).toDouble() / (size - 1).coerceAtLeast(1)
        val y = (index / size).toDouble() / (size - 1).coerceAtLeast(1)
        val value = 127.5 +
            60.0 * sin(frequencyA * x + phaseA) * cos(frequencyB * y + phaseB) +
            30.0 * sin(frequencyC * (x + y) + phaseC)
        value.coerceIn(0.0, 255.0).toFloat()
    }
    return GrayImage(size, size, pixels)
}
