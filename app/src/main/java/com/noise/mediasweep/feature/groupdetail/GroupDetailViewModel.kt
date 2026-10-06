package com.noise.mediasweep.feature.groupdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noise.mediasweep.core.di.appContainer
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.domain.repository.CandidateRepository
import com.noise.mediasweep.feature.review.ReviewSelectionStore
import com.noise.mediasweep.feature.review.SelectedMedia
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface GroupDetailUiState {
    data object Loading : GroupDetailUiState

    /** The group disappeared (library changed since the scan). */
    data object NotFound : GroupDetailUiState

    data class Content(val group: CandidateGroupWithItems) : GroupDetailUiState
}

/**
 * Group detail: image comparison + per-item selection (specification §29).
 *
 * Never decides which copy is the keeper; selection is entirely the user's.
 */
class GroupDetailViewModel(
    private val groupId: Long,
    candidateRepository: CandidateRepository,
    val selection: ReviewSelectionStore,
) : ViewModel() {

    val uiState: StateFlow<GroupDetailUiState> = candidateRepository
        .observeGroup(groupId)
        .map { withItems ->
            when {
                withItems == null -> GroupDetailUiState.NotFound
                else -> GroupDetailUiState.Content(withItems)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = GroupDetailUiState.Loading,
        )

    fun toggle(item: MediaItem) {
        selection.toggle(SelectedMedia.from(item))
    }

    /** "Select all duplicates" for this group only (specification §29). */
    fun selectGroup() {
        val group = (uiState.value as? GroupDetailUiState.Content)?.group ?: return
        selection.setAll(group.items.map { SelectedMedia.from(it) }, selected = true)
    }

    fun clearGroup() {
        val group = (uiState.value as? GroupDetailUiState.Content)?.group ?: return
        selection.setAll(group.items.map { SelectedMedia.from(it) }, selected = false)
    }

    companion object {
        fun factory(groupId: Long) = viewModelFactory {
            initializer {
                GroupDetailViewModel(
                    groupId = groupId,
                    candidateRepository = appContainer().candidateRepository,
                    selection = appContainer().reviewSelection,
                )
            }
        }
    }
}
