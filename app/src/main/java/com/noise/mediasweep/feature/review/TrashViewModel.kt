package com.noise.mediasweep.feature.review

import android.app.Activity
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.noise.mediasweep.core.di.appContainer
import com.noise.mediasweep.core.media.TrashRequest
import com.noise.mediasweep.core.media.TrashRequestFactory
import com.noise.mediasweep.domain.repository.TrashReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the trash flow currently stands (specification §10, §11). */
sealed interface TrashUiState {

    /** Nothing in flight; the destructive action may be offered. */
    data object Idle : TrashUiState

    /** The system confirmation is ready and must be launched exactly once. */
    data class LaunchConfirmation(val intentSender: IntentSender) : TrashUiState

    /** The system dialog is showing: no local state may change yet. */
    data object AwaitingConfirmation : TrashUiState

    /** The user answered; MediaStore is being re-queried for the actual outcome. */
    data object Verifying : TrashUiState

    /**
     * Reconciliation finished: [removedCount] items left the active library,
     * [stillPresentCount] requested items are still present.
     */
    data class Completed(val removedCount: Int, val stillPresentCount: Int) : TrashUiState

    /** The system dialog was dismissed: nothing changed locally. */
    data object Cancelled : TrashUiState

    /** This platform cannot ask the system to trash media (Android 10). */
    data object Unsupported : TrashUiState

    /** The system confirmation could not be created: nothing changed locally. */
    data object RequestFailed : TrashUiState

    /** MediaStore could not be re-queried: the index may be behind until the next scan. */
    data object VerifyFailed : TrashUiState
}

/**
 * Drives the trash flow (specification Phase 8).
 *
 * Responsibilities, in order:
 *
 *  1. ask [TrashRequestFactory] for the system confirmation (never deletes anything);
 *  2. hand the confirmation to the route, which launches it exactly once;
 *  3. on the activity result, re-query MediaStore through [TrashReconciler] instead of
 *     trusting the result code, so a cancelled prompt records no local deletion state;
 *  4. drop the items that really left the library from the review selection.
 */
class TrashViewModel(
    private val selection: ReviewSelectionStore,
    private val requestFactory: TrashRequestFactory,
    private val reconciler: TrashReconciler,
) : ViewModel() {

    private val _uiState = MutableStateFlow<TrashUiState>(TrashUiState.Idle)
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    /** Ids covered by the confirmation currently in flight. */
    private var pendingIds: List<Long> = emptyList()

    /** Whether the platform offers a system trash request at all (drives the UI). */
    val trashSupported: Boolean get() = requestFactory.isSupported

    /** Starts the flow for the current selection; a no-op while one is already running. */
    fun moveToTrash() {
        val current = _uiState.value
        if (current == TrashUiState.AwaitingConfirmation ||
            current == TrashUiState.Verifying ||
            current is TrashUiState.LaunchConfirmation
        ) {
            return
        }
        val items = selection.selection.value.items
        if (items.isEmpty()) return

        pendingIds = items.map { it.id }
        _uiState.value = when (val request = requestFactory.create(items.map { it.contentUri })) {
            is TrashRequest.SystemConfirmation -> TrashUiState.LaunchConfirmation(request.intentSender)
            TrashRequest.Unsupported -> TrashUiState.Unsupported
            TrashRequest.Failed -> TrashUiState.RequestFailed
        }
    }

    /** Called by the route once the system dialog has actually been launched. */
    fun onConfirmationLaunched() {
        if (_uiState.value is TrashUiState.LaunchConfirmation) {
            _uiState.value = TrashUiState.AwaitingConfirmation
        }
    }

    /** Called when launching the confirmation itself failed; still nothing changed. */
    fun onLaunchFailed() {
        if (_uiState.value is TrashUiState.LaunchConfirmation) {
            pendingIds = emptyList()
            _uiState.value = TrashUiState.RequestFailed
        }
    }

    /** Reacts to the system confirmation result (RESULT_OK or cancelled). */
    fun onConfirmationResult(resultCode: Int) {
        val awaiting = _uiState.value == TrashUiState.AwaitingConfirmation ||
            _uiState.value is TrashUiState.LaunchConfirmation
        if (!awaiting) return

        if (resultCode != Activity.RESULT_OK) {
            // Cancelled: no local state is touched (specification §46).
            pendingIds = emptyList()
            _uiState.value = TrashUiState.Cancelled
            return
        }

        val requested = pendingIds
        pendingIds = emptyList()
        _uiState.value = TrashUiState.Verifying
        viewModelScope.launch {
            _uiState.value = try {
                val result = reconciler.reconcile(requested)
                selection.remove(result.removedIds)
                TrashUiState.Completed(
                    removedCount = result.removedIds.size,
                    stillPresentCount = result.stillPresentIds.size,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                TrashUiState.VerifyFailed
            }
        }
    }

    /** Returns to the idle summary after the user acknowledged a terminal result. */
    fun dismissResult() {
        val current = _uiState.value
        if (current is TrashUiState.Completed ||
            current == TrashUiState.Cancelled ||
            current == TrashUiState.RequestFailed ||
            current == TrashUiState.VerifyFailed ||
            current == TrashUiState.Unsupported
        ) {
            _uiState.value = TrashUiState.Idle
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                TrashViewModel(
                    selection = appContainer().reviewSelection,
                    requestFactory = appContainer().trashRequestFactory,
                    reconciler = appContainer().trashReconciler,
                )
            }
        }
    }
}
