package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.dao.CandidateGroupDao
import com.noise.mediasweep.data.mapper.toDomain
import com.noise.mediasweep.domain.model.CandidateGroup
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.repository.CandidateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomCandidateRepository(
    private val candidateGroupDao: CandidateGroupDao,
) : CandidateRepository {

    override fun observeCategorySummaries(): Flow<List<CategorySummary>> =
        candidateGroupDao.observeTypeTotals().map { rows ->
            rows.mapNotNull { row ->
                runCatching { CandidateType.valueOf(row.type) }.getOrNull()?.let { type ->
                    CategorySummary(
                        type = type,
                        groupCount = row.groupCount.toInt(),
                        itemCount = row.itemCount.toInt(),
                        totalSizeBytes = row.bytes,
                    )
                }
            }
        }

    override fun observeGroups(type: CandidateType): Flow<List<CandidateGroup>> =
        candidateGroupDao.observeGroups(type.name).map { groups -> groups.map { it.toDomain() } }

    override fun observeGroupsWithItems(type: CandidateType): Flow<List<CandidateGroupWithItems>> =
        candidateGroupDao.observeGroupsWithMedia(type.name).map { relations ->
            relations.map { relation -> relation.toDomainModel() }
        }

    override fun observeGroup(groupId: Long): Flow<CandidateGroupWithItems?> =
        candidateGroupDao.observeGroupWithMedia(groupId).map { relation -> relation?.toDomainModel() }

    override suspend fun group(groupId: Long): CandidateGroupWithItems? =
        candidateGroupDao.getGroupWithMedia(groupId)?.toDomainModel()

    private fun com.noise.mediasweep.core.database.dao.CandidateGroupWithMedia.toDomainModel(): CandidateGroupWithItems =
        CandidateGroupWithItems(
            group = group.toDomain(),
            items = members
                .sortedBy { it.member.position }
                .map { it.media.toDomain() },
        )
}
