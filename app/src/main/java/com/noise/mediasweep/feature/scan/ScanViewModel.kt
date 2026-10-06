package com.noise.mediasweep.feature.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noise.mediasweep.core.di.appContainer
import com.noise.mediasweep.core.media.MediaAccess
import com.noise.mediasweep.core.media.MediaPermissionMonitor
import com.noise.mediasweep.data.repository.ScanController
import com.noise.mediasweep.data.repository.ScanRunState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the scan screen (specification §18, §27, §45).
 *
 * The run itself lives in the application-scoped [ScanController]: opening the screen
 * starts one when nothing is running, and leaving the screen never kills a scan silently.
 * The partial-access flag is read from the real permission grant, so a run never claims to
 * have seen more of the library than the user allowed (§19, §25).
 */
class ScanViewModel(
    private val controller: ScanController,
    private val permissionMonitor: MediaPermissionMonitor,
) : ViewModel() {

    val runState: StateFlow<ScanRunState> = controller.state

    private val _partialAccess = MutableStateFlow(false)
    /** Whether the running/last run was started with selected (partial) media access. */
    val partialAccess: StateFlow<Boolean> = _partialAccess.asStateFlow()

    init {
        // First visit starts a scan; re-entering while one runs (or after it finished)
        // shows that state instead of silently starting a second run.
        if (controller.state.value is ScanRunState.Idle) start()
    }

    /** Starts a scan unless one is already in flight, using the current permission grant. */
    fun start() {
        val access = permissionMonitor.status.value
        if (access == MediaAccess.DENIED) return
        val partial = access == MediaAccess.PARTIAL
        _partialAccess.value = partial
        controller.start(partialAccess = partial)
    }

    /** Cooperative cancellation; the pipeline stops safely and claims no completion. */
    fun cancel() {
        controller.cancel()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                ScanViewModel(
                    controller = appContainer().scanController,
                    permissionMonitor = appContainer().permissionMonitor,
                )
            }
        }
    }
}
