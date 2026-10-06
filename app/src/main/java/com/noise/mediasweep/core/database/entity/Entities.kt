package com.noise.mediasweep.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lightweight local index of a media file known to MediaStore.
 *
 * MediaStore stays the source of truth: rows here are a cache/index only and are
 * reconciled against MediaStore after every scan and after every trash operation.
 */
@Entity(
    tableName = MediaItemEntity.TABLE,
    indices = [
        Index(value = ["sizeBytes"]),
        Index(value = ["dateModified"]),
        Index(value = ["mediaType"]),
        Index(value = ["bucketId"]),
        Index(value = ["stale"]),
    ],
)
data class MediaItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long,
    @ColumnInfo(name = "contentUri")
    val contentUri: String,
    @ColumnInfo(name = "mediaType")
    val mediaType: Int,
    @ColumnInfo(name = "displayName")
    val displayName: String,
    @ColumnInfo(name = "mimeType")
    val mimeType: String?,
    @ColumnInfo(name = "sizeBytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "width")
    val width: Int?,
    @ColumnInfo(name = "height")
    val height: Int?,
    @ColumnInfo(name = "durationMs")
    val durationMs: Long?,
    @ColumnInfo(name = "dateAdded")
    val dateAdded: Long,
    @ColumnInfo(name = "dateModified")
    val dateModified: Long,
    @ColumnInfo(name = "relativePath")
    val relativePath: String?,
    @ColumnInfo(name = "bucketId")
    val bucketId: String?,
    @ColumnInfo(name = "bucketDisplayName")
    val bucketDisplayName: String?,
    @ColumnInfo(name = "isFavorite")
    val isFavorite: Boolean,
    @ColumnInfo(name = "isPending")
    val isPending: Boolean,
    @ColumnInfo(name = "isTrashed")
    val isTrashed: Boolean,
    /** True when the row is no longer present in MediaStore (stale index entry). */
    @ColumnInfo(name = "stale")
    val stale: Boolean = false,
    /** Scan session that last observed this row; used for incremental reconciliation. */
    @ColumnInfo(name = "lastSeenSessionId")
    val lastSeenSessionId: Long? = null,
) {
    companion object {
        const val TABLE = "media_items"
    }
}

/** Derived fingerprint of a media file (exact hash, perceptual hash, ...). */
@Entity(
    tableName = FingerprintEntity.TABLE,
    primaryKeys = ["mediaId", "algorithm"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["algorithm"]), Index(value = ["mediaId"])],
)
data class FingerprintEntity(
    @ColumnInfo(name = "mediaId")
    val mediaId: Long,
    @ColumnInfo(name = "algorithm")
    val algorithm: String,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "calculatedAt")
    val calculatedAt: Long,
) {
    companion object {
        const val TABLE = "fingerprints"
        const val ALGORITHM_SHA256 = "SHA-256"
        const val ALGORITHM_PERCEPTUAL_HASH = "PERCEPTUAL_HASH"
    }
}

/** A group of media items surfaced as a cleanup candidate. */
@Entity(
    tableName = CandidateGroupEntity.TABLE,
    indices = [Index(value = ["type"]), Index(value = ["confidence"])],
)
data class CandidateGroupEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "type")
    val type: String,
    @ColumnInfo(name = "confidence")
    val confidence: String,
    @ColumnInfo(name = "totalSizeBytes")
    val totalSizeBytes: Long,
    @ColumnInfo(name = "itemCount")
    val itemCount: Int,
    /** Human readable explanation of why this group was surfaced. */
    @ColumnInfo(name = "reason")
    val reason: String,
    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
    @ColumnInfo(name = "sessionId")
    val sessionId: Long?,
) {
    companion object {
        const val TABLE = "candidate_groups"
    }
}

/** Membership of a media item inside a candidate group. */
@Entity(
    tableName = CandidateGroupMemberEntity.TABLE,
    primaryKeys = ["groupId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = CandidateGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mediaId"]), Index(value = ["groupId"])],
)
data class CandidateGroupMemberEntity(
    @ColumnInfo(name = "groupId")
    val groupId: Long,
    @ColumnInfo(name = "mediaId")
    val mediaId: Long,
    @ColumnInfo(name = "position")
    val position: Int,
) {
    companion object {
        const val TABLE = "candidate_group_members"
    }
}

/**
 * Single row describing the outcome of the most relevant scan.
 *
 * Never a source of truth for file existence: MediaStore is.
 */
@Entity(tableName = ScanStateEntity.TABLE)
data class ScanStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLE_ROW_ID,
    /** [com.noise.mediasweep.domain.model.ScanStatus] name. */
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "lastSuccessfulScanAt")
    val lastSuccessfulScanAt: Long?,
    @ColumnInfo(name = "lastSessionId")
    val lastSessionId: Long?,
    @ColumnInfo(name = "lastMediaCount")
    val lastMediaCount: Long,
    @ColumnInfo(name = "lastMediaBytes")
    val lastMediaBytes: Long,
    @ColumnInfo(name = "partialAccess")
    val partialAccess: Boolean,
    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE = "scan_state"
        const val SINGLE_ROW_ID = 1
    }
}

/** One scan run, kept so an interrupted scan can be resumed or restarted safely. */
@Entity(tableName = ScanSessionEntity.TABLE, indices = [Index(value = ["startedAt"])])
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "startedAt")
    val startedAt: Long,
    @ColumnInfo(name = "finishedAt")
    val finishedAt: Long?,
    /** [com.noise.mediasweep.domain.model.ScanStatus] name. */
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "processedCount")
    val processedCount: Long,
    @ColumnInfo(name = "totalCount")
    val totalCount: Long,
    @ColumnInfo(name = "partialAccess")
    val partialAccess: Boolean,
    @ColumnInfo(name = "errorMessage")
    val errorMessage: String?,
) {
    companion object {
        const val TABLE = "scan_sessions"
    }
}
