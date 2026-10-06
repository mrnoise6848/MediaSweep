package com.noise.mediasweep.domain.model

/** Kind of media as reported by MediaStore. */
enum class MediaType {
    IMAGE,
    VIDEO,
    UNKNOWN,
    ;

    companion object {
        fun fromMediaStore(mediaType: Int): MediaType = when (mediaType) {
            1 -> IMAGE // MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            3 -> VIDEO // MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            else -> UNKNOWN
        }
    }
}

/**
 * Domain representation of a single media item.
 *
 * Deliberately free of Android types: no [android.net.Uri], no [android.content.Context],
 * no MediaStore. The content URI is carried as a opaque string and only resolved in the
 * data/infrastructure layer.
 */
data class MediaItem(
    val id: Long,
    val contentUri: String,
    val mediaType: MediaType,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    /** Seconds since epoch, as stored by MediaStore. */
    val dateAdded: Long,
    /** Seconds since epoch, as stored by MediaStore. */
    val dateModified: Long,
    val relativePath: String?,
    val bucketId: String?,
    val bucketDisplayName: String?,
    val isFavorite: Boolean,
    val isPending: Boolean,
    val isTrashed: Boolean,
    val isStale: Boolean,
) {
    val isVideo: Boolean get() = mediaType == MediaType.VIDEO
    val isImage: Boolean get() = mediaType == MediaType.IMAGE
}
