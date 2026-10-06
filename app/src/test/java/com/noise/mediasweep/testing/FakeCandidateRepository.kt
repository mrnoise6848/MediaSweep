package com.noise.mediasweep.testing

import com.noise.mediasweep.domain.model.CandidateGroup
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.repository.CandidateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory candidate repository for host-side tests. Flows stay reactive so ViewModels
 * can be exercised the same way they run in production.
 */
class FakeCandidateRepository(
    summaries: List<CategorySummary> = emptyList(),
    groups: Map<CandidateType, List<CandidateGroupWithItems>> = emptyMap(),
    detail: CandidateGroupWithItems? = null,
) : CandidateRepository {

    private val summariesFlow = MutableStateFlow(summaries)
    private val groupsFlow = MutableStateFlow(groups)
    private val detailFlow = MutableStateFlow(detail)

    fun setSummaries(value: List<CategorySummary>) {
        summariesFlow.value = value
    }

    fun setGroups(type: CandidateType, value: List<CandidateGroupWithItems>) {
        groupsFlow.value = groupsFlow.value + (type to value)
    }

    fun setDetail(value: CandidateGroupWithItems?) {
        detailFlow.value = value
    }

    override fun observeCategorySummaries(): Flow<List<CategorySummary>> = summariesFlow

    override fun observeGroups(type: CandidateType): Flow<List<com.noise.mediasweep.domain.model.CandidateGroup>> =
        groupsFlow.map { groups -> groups[type].orEmpty().map { it.group } }

    override fun observeGroupsWithItems(type: CandidateType): Flow<List<CandidateGroupWithItems>> =
        groupsFlow.map { groups -> groups[type].orEmpty() }

    override fun observeGroup(groupId: Long): Flow<CandidateGroupWithItems?> =
        detailFlow.map { detail -> detail?.takeIf { it.group.id == groupId } }

    override suspend fun group(groupId: Long): CandidateGroupWithItems? =
        detailFlow.value?.takeIf { it.group.id == groupId }
}

/** Builds a persisted-shape candidate group from plain media items. */
fun candidateGroup(
    id: Long,
    type: CandidateType,
    items: List<MediaItem>,
    confidence: Confidence = Confidence.HIGH,
    reason: String = "${items.size} items for review",
) = CandidateGroupWithItems(
    group = CandidateGroup(
        id = id,
        type = type,
        confidence = confidence,
        totalSizeBytes = items.sumOf { it.sizeBytes },
        itemCount = items.size,
        reason = reason,
    ),
    items = items,
)
