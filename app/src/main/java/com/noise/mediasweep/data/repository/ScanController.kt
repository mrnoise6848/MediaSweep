package com.noise.mediasweep.data.repository

import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The unit of work the [ScanController] drives; implemented by [ScanPipeline] in
 * production and by a fake in tests, so the runner can be tested without a library.
 */
interface ScanRunner {
    suspend fun run(
        partialAccess: Boolean,
        onProgress: suspend (ScanProgress) -> Unit = {},
    ): ScanOutcome
}

/** Live state of the application-scanned run (specification §18, §27, §45). */
sealed interface ScanRunState {

    /** Nothing has been started yet. */
    data object Idle : ScanRunState

    /** A scan is running; [progress] is null until the first real counter arrives. */
    data class Running(val progress: ScanProgress?) : ScanRunState

    /** Cancellation was requested; the pipeline is winding down cooperatively. */
    data object Cancelling : ScanRunState

    /** The run ended on its own — [ScanOutcome.status] says COMPLETE/PARTIAL/FAILED. */
    data class Finished(val outcome: ScanOutcome) : ScanRunState

    /** The run was cancelled: no completion is claimed and the index stays consistent. */
    data object Cancelled : ScanRunState
}

/**
 * Application-scoped scan runner (specification §18, §19, §45).
 *
 * * One run at a time: [start] refuses to launch while a previous run — or its
 *   cooperative cleanup — is still in flight, so two pipelines can never write at once.
 * * The scan outlives the screen it was started from; progress is published as state so
 *   any screen can observe the same run.
 * * [cancel] is cooperative: the pipeline checks for cancellation between items, leaves
 *   the database consistent and rethrows, and only then does this controller publish
 *   [ScanRunState.Cancelled] — never "complete".
 */
class ScanController(
    private val scope: CoroutineScope,
    private val runner: ScanRunner,
) {

    private val _state = MutableStateFlow<ScanRunState>(ScanRunState.Idle)
    val state: StateFlow<ScanRunState> = _state.asStateFlow()

    private var job: Job? = null

    /** True while a run (including its cleanup) is still in flight. */
    val isRunning: Boolean get() = job?.isCompleted == false

    /**
     * Starts a scan for the given access level.
     *
     * @return false when a run is already in flight (nothing is started in that case).
     */
    fun start(partialAccess: Boolean): Boolean {
        if (isRunning) return false

        val launched = scope.launch {
            _state.value = ScanRunState.Running(null)
            val outcome = try {
                runner.run(partialAccess) { progress ->
                    // A cancel request owns the state until the run really ends.
                    if (_state.value !is ScanRunState.Cancelling) {
                        _state.value = ScanRunState.Running(progress)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // Defensive: the pipeline converts failures into FAILED outcomes itself.
                ScanOutcome(
                    status = ScanStatus.FAILED,
                    mediaCount = 0L,
                    mediaBytes = 0L,
                    groupCounts = emptyMap(),
                    errorMessage = error.message,
                )
            }
            _state.value = ScanRunState.Finished(outcome)
        }
        job = launched
        launched.invokeOnCompletion { cause ->
            // Only a cancellation can land here: the coroutine sets Finished itself, and
            // the pipeline has already recovered the session before rethrowing.
            if (cause is CancellationException) {
                _state.value = ScanRunState.Cancelled
            }
        }
        return true
    }

    /** Requests cooperative cancellation of the current run (no-op when idle). */
    fun cancel() {
        val current = job ?: return
        if (current.isCompleted) return
        _state.value = ScanRunState.Cancelling
        current.cancel()
    }
}
