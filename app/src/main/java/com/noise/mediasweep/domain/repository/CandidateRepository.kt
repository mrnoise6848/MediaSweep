package com.noise.mediasweep.domain.repository

import com.noise.mediasweep.domain.model.CandidateGroup
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import kotlinx.coroutines.flow.Flow

/** Read/observe persisted cleanup candidates. */
interface CandidateRepository {
    fun observeCategorySummaries(): Flow<List<CategorySummary>>

    fun observeGroups(type: CandidateType): Flow<List<CandidateGroup>>

    /** Groups of one category together with their media, for the category screen. */
    fun observeGroupsWithItems(type: CandidateType): Flow<List<CandidateGroupWithItems>>

    fun observeGroup(groupId: Long): Flow<CandidateGroupWithItems?>

    suspend fun group(groupId: Long): CandidateGroupWithItems?
}
