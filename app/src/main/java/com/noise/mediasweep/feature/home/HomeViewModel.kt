package com.noise.mediasweep.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noise.mediasweep.core.di.appContainer
import com.noise.mediasweep.core.media.MediaAccess
import com.noise.mediasweep.core.media.MediaPermissionMonitor
import com.noise.mediasweep.data.repository.ScanController
import com.noise.mediasweep.data.repository.ScanRunState
import com.noise.mediasweep.domain.usecase.ObserveStorageSummaryUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeViewModel(
    private val observeStorageSummary: ObserveStorageSummaryUseCase,
    private val permissionMonitor: MediaPermissionMonitor,
    scanController: ScanController,
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                observeStorageSummary(),
                permissionMonitor.status,
                scanController.state,
            ) { summary, access, run ->
                when (access) {
                    MediaAccess.DENIED -> HomeUiState.NoPermission
                    // While a run is live, publish its real counters rather than the
                    // coarser stored status (specification §18: actual work, never fake).
                    MediaAccess.FULL, MediaAccess.PARTIAL -> when (run) {
                        is ScanRunState.Running -> HomeUiState.Scanning(run.progress)
                        else -> summary.toHomeState()
                    }
                }
            }.collect { state -> _uiState.value = state }
        }
    }

    /** Re-reads the permission grant after a dialog result or when the app resumes. */
    fun refreshPermission() {
        permissionMonitor.refresh()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    observeStorageSummary = appContainer().observeStorageSummary,
                    permissionMonitor = appContainer().permissionMonitor,
                    scanController = appContainer().scanController,
                )
            }
        }
    }
}
