package com.noise.mediasweep.feature.candidates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noise.mediasweep.core.di.appContainer
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.repository.CandidateRepository
import com.noise.mediasweep.feature.review.ReviewSelectionStore
import com.noise.mediasweep.feature.review.SelectedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Sort order of a category list (specification §30 and §32). */
enum class GroupSort { LARGEST, NEWEST, OLDEST }

/** UI state of one category screen. */
data class CategoryUiState(
    val type: CandidateType,
    val summary: CategorySummary?,
    val sort: GroupSort,
    val groups: List<CandidateGroupWithItems>,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
    val isNotEmpty: Boolean get() = groups.isNotEmpty()

    /** Header figures, derived from the persisted scan (never invented). */
    val groupCount: Int get() = summary?.groupCount ?: groups.size
    val reviewableBytes: Long get() = summary?.totalSizeBytes ?: groups.sumOf { it.group.totalSizeBytes }

    fun groupItems(group: CandidateGroupWithItems): List<SelectedMedia> =
        group.items.map { SelectedMedia.from(it) }
}

internal fun sortGroups(
    groups: List<CandidateGroupWithItems>,
    sort: GroupSort,
): List<CandidateGroupWithItems> = when (sort) {
    GroupSort.LARGEST -> groups.sortedByDescending { it.group.totalSizeBytes }
    GroupSort.NEWEST -> groups.sortedByDescending { group -> group.items.maxOfOrNull { it.dateModified } ?: 0L }
    GroupSort.OLDEST -> groups.sortedBy { group -> group.items.minOfOrNull { it.dateModified } ?: Long.MAX_VALUE }
}

class CategoryViewModel(
    val type: CandidateType,
    candidateRepository: CandidateRepository,
    val selection: ReviewSelectionStore,
) : ViewModel() {

    private val sort = MutableStateFlow(GroupSort.LARGEST)

    val uiState: StateFlow<CategoryUiState> = combine(
        candidateRepository.observeGroupsWithItems(type),
        candidateRepository.observeCategorySummaries(),
        sort,
    ) { groups, summaries, currentSort ->
        CategoryUiState(
            type = type,
            summary = summaries.firstOrNull { it.type == type },
            sort = currentSort,
            groups = sortGroups(groups, currentSort),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CategoryUiState(type, null, GroupSort.LARGEST, emptyList()),
    )

    fun setSort(value: GroupSort) {
        sort.value = value
    }

    /** Category-level toggle: selects or deselects every member of one group. */
    fun toggleGroup(group: CandidateGroupWithItems) {
        val items = group.items.map { SelectedMedia.from(it) }
        val allSelected = items.isNotEmpty() && items.all { selection.selection.value.isSelected(it.id) }
        selection.setAll(items, selected = !allSelected)
    }

    fun areAllGroupItemsSelected(group: CandidateGroupWithItems): Boolean =
        group.items.isNotEmpty() && group.items.all { selection.selection.value.isSelected(it.id) }

    fun selectAll() {
        val all = uiState.value.groups.flatMap { group -> group.items.map { SelectedMedia.from(it) } }
        selection.setAll(all, selected = true)
    }

    fun clearCategory() {
        val all = uiState.value.groups.flatMap { group -> group.items.map { SelectedMedia.from(it) } }
        selection.setAll(all, selected = false)
    }

    companion object {
        fun factory(type: CandidateType) = viewModelFactory {
            initializer {
                CategoryViewModel(
                    type = type,
                    candidateRepository = appContainer().candidateRepository,
                    selection = appContainer().reviewSelection,
                )
            }
        }
    }
}
