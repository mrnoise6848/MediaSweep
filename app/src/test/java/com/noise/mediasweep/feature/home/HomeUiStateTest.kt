package com.noise.mediasweep.feature.home

import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.LibraryTotals
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.domain.model.StorageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {

    private fun summary(
        status: ScanStatus,
        mediaCount: Long = 10L,
        categories: List<CategorySummary> = emptyList(),
    ) = StorageSummary(
        totals = LibraryTotals(mediaCount = mediaCount, mediaBytes = 1024L),
        categories = categories,
        status = status,
        partialAccess = status == ScanStatus.PARTIAL,
        lastCompletedScanAt = null,
    )

    @Test
    fun `not scanned maps to NotScanned`() {
        assertEquals(HomeUiState.NotScanned, summary(ScanStatus.NOT_SCANNED).toHomeState())
    }

    @Test
    fun `scanning maps to Scanning without fabricated progress`() {
        val state = summary(ScanStatus.SCANNING).toHomeState()
        assertTrue(state is HomeUiState.Scanning)
        assertEquals(null, (state as HomeUiState.Scanning).progress)
    }

    @Test
    fun `complete maps to Complete`() {
        val state = summary(ScanStatus.COMPLETE).toHomeState()
        assertTrue(state is HomeUiState.Complete)
    }

    @Test
    fun `partial access never claims a complete scan`() {
        val state = summary(ScanStatus.PARTIAL).toHomeState()
        assertTrue(state is HomeUiState.Partial)
    }

    @Test
    fun `stale results are surfaced as stale`() {
        val state = summary(ScanStatus.STALE).toHomeState()
        assertTrue(state is HomeUiState.Stale)
    }

    @Test
    fun `failure is surfaced`() {
        assertTrue(summary(ScanStatus.FAILED).toHomeState() is HomeUiState.Failed)
    }

    @Test
    fun `a completed scan with no media shows the empty library state`() {
        assertTrue(summary(ScanStatus.COMPLETE, mediaCount = 0L).toHomeState() is HomeUiState.NoMedia)
        assertTrue(summary(ScanStatus.PARTIAL, mediaCount = 0L).toHomeState() is HomeUiState.NoMedia)
    }

    @Test
    fun `candidate bytes are the sum of the surfaced categories`() {
        val categories = listOf(
            CategorySummary(CandidateType.EXACT_DUPLICATE, groupCount = 2, itemCount = 4, totalSizeBytes = 100L),
            CategorySummary(CandidateType.LARGE_FILE, groupCount = 1, itemCount = 1, totalSizeBytes = 250L),
        )
        val state = summary(ScanStatus.COMPLETE, categories = categories)
        assertEquals(350L, state.candidateBytes)
        assertEquals(100L, state.category(CandidateType.EXACT_DUPLICATE)?.totalSizeBytes)
        assertEquals(null, state.category(CandidateType.OLD_MEDIA))
    }
}
