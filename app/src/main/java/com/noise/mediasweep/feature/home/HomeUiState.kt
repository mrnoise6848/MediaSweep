package com.noise.mediasweep.feature.home

import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.domain.model.StorageSummary

/**
 * State of the home screen.
 *
 * Follows the status states required by the specification:
 * NO_PERMISSION, NO_MEDIA, SCANNING, SCAN_COMPLETE, SCAN_PARTIAL, SCAN_FAILED,
 * STALE_RESULTS — plus NOT_SCANNED before the first run.
 */
sealed interface HomeUiState {
    /** Initial state before the first database emission. */
    data object Loading : HomeUiState

    /** Media access is not granted (full or partial). */
    data object NoPermission : HomeUiState

    /** The library contains no photos or videos. */
    data object NoMedia : HomeUiState

    /** Permission granted but no scan has completed yet. */
    data object NotScanned : HomeUiState

    /**
     * A scan is running. [progress] is only populated from real work counters;
     * it stays null when no trustworthy counter is available (never fake progress).
     */
    data class Scanning(val progress: ScanProgress? = null) : HomeUiState

    data class Complete(val summary: StorageSummary) : HomeUiState

    /** Only selected/partial media was visible: never claim the full library was scanned. */
    data class Partial(val summary: StorageSummary) : HomeUiState

    /** Results exist but the index no longer matches MediaStore. */
    data class Stale(val summary: StorageSummary) : HomeUiState

    data class Failed(val message: String?) : HomeUiState
}

/** Pure mapping from the derived summary to the home state. Kept side-effect free for tests. */
fun StorageSummary.toHomeState(): HomeUiState = when (status) {
    ScanStatus.NOT_SCANNED -> HomeUiState.NotScanned
    ScanStatus.SCANNING -> HomeUiState.Scanning()
    ScanStatus.COMPLETE ->
        if (totals.mediaCount == 0L) HomeUiState.NoMedia else HomeUiState.Complete(this)

    ScanStatus.PARTIAL ->
        if (totals.mediaCount == 0L) HomeUiState.NoMedia else HomeUiState.Partial(this)

    ScanStatus.FAILED -> HomeUiState.Failed(null)
    ScanStatus.STALE ->
        if (totals.mediaCount == 0L) HomeUiState.NoMedia else HomeUiState.Stale(this)
}
