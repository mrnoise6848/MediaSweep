package com.noise.mediasweep.data.mapper

import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.MediaItemEntity
import com.noise.mediasweep.core.database.entity.ScanSessionEntity
import com.noise.mediasweep.core.database.entity.ScanStateEntity
import com.noise.mediasweep.domain.model.CandidateGroup
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.model.MediaType
import com.noise.mediasweep.domain.model.ScanSession
import com.noise.mediasweep.domain.model.ScanStatus

fun MediaItemEntity.toDomain(): MediaItem = MediaItem(
    id = id,
    contentUri = contentUri,
    mediaType = MediaType.fromMediaStore(mediaType),
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    dateAdded = dateAdded,
    dateModified = dateModified,
    relativePath = relativePath,
    bucketId = bucketId,
    bucketDisplayName = bucketDisplayName,
    isFavorite = isFavorite,
    isPending = isPending,
    isTrashed = isTrashed,
    isStale = stale,
)

fun MediaItem.toEntity(): MediaItemEntity = MediaItemEntity(
    id = id,
    contentUri = contentUri,
    mediaType = mediaType.toMediaStore(),
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
    dateAdded = dateAdded,
    dateModified = dateModified,
    relativePath = relativePath,
    bucketId = bucketId,
    bucketDisplayName = bucketDisplayName,
    isFavorite = isFavorite,
    isPending = isPending,
    isTrashed = isTrashed,
    stale = isStale,
)

fun MediaType.toMediaStore(): Int = when (this) {
    MediaType.IMAGE -> 1
    MediaType.VIDEO -> 3
    MediaType.UNKNOWN -> 0
}

fun CandidateGroupEntity.toDomain(): CandidateGroup = CandidateGroup(
    id = id,
    type = CandidateType.valueOf(type),
    confidence = Confidence.valueOf(confidence),
    totalSizeBytes = totalSizeBytes,
    itemCount = itemCount,
    reason = reason,
)

fun CandidateGroup.toEntity(sessionId: Long?, createdAt: Long): CandidateGroupEntity =
    CandidateGroupEntity(
        id = id.takeIf { it > 0L } ?: 0L,
        type = type.name,
        confidence = confidence.name,
        totalSizeBytes = totalSizeBytes,
        itemCount = itemCount,
        reason = reason,
        createdAt = createdAt,
        sessionId = sessionId,
    )

fun ScanStateEntity.statusOrDefault(): ScanStatus = runCatching { ScanStatus.valueOf(status) }
    .getOrDefault(ScanStatus.NOT_SCANNED)

fun ScanSessionEntity.toDomain(): ScanSession = ScanSession(
    id = id,
    startedAt = startedAt,
    finishedAt = finishedAt,
    status = runCatching { ScanStatus.valueOf(status) }.getOrDefault(ScanStatus.FAILED),
    processedCount = processedCount,
    totalCount = totalCount,
    partialAccess = partialAccess,
    errorMessage = errorMessage,
)
