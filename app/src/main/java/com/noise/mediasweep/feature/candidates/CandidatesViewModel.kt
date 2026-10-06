package com.noise.mediasweep.feature.candidates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noise.mediasweep.core.di.appContainer
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.repository.CandidateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Candidates overview: one row per category with its real scan figures
 * (specification §8, §27). Categories without results are still listed with zeros so the
 * user always sees the full picture.
 */
class CandidatesViewModel(
    candidateRepository: CandidateRepository,
) : ViewModel() {

    /**
     * `null` until the first emission from Room, so the screen can show a loading state
     * instead of flashing "clean library" before the database has answered.
     */
    val categories: StateFlow<List<CategorySummary>?> = candidateRepository
        .observeCategorySummaries()
        .map { summaries ->
            CandidateType.entries.map { type ->
                summaries.firstOrNull { it.type == type } ?: CategorySummary(type, 0, 0, 0L)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    companion object {
        val Factory = viewModelFactory {
            initializer { CandidatesViewModel(appContainer().candidateRepository) }
        }
    }
}
