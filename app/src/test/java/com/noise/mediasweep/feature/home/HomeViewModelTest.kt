package com.noise.mediasweep.feature.home

import com.noise.mediasweep.core.media.MediaAccess
import com.noise.mediasweep.core.media.MediaPermissionMonitor
import com.noise.mediasweep.data.repository.ScanController
import com.noise.mediasweep.data.repository.ScanOutcome
import com.noise.mediasweep.data.repository.ScanRunner
import com.noise.mediasweep.domain.model.CandidateGroup
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.LibraryTotals
import com.noise.mediasweep.domain.model.ScanPhase
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanSession
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.domain.repository.CandidateRepository
import com.noise.mediasweep.domain.repository.MediaIndexRepository
import com.noise.mediasweep.domain.repository.ScanStateRepository
import com.noise.mediasweep.domain.usecase.ObserveStorageSummaryUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private class FakeScanStateRepository(status: ScanStatus) : ScanStateRepository {
        val status = MutableStateFlow(status)
        override fun observeStatus(): Flow<ScanStatus> = status
        override fun observeLastSession(): Flow<ScanSession?> = flowOf(null)
        override suspend fun status(): ScanStatus = status.value
    }

    private class FakePermissionMonitor(status: MediaAccess) : MediaPermissionMonitor {
        val value = MutableStateFlow(status)
        override val status: StateFlow<MediaAccess> = value
        override fun refresh() = Unit
    }

    private class FakeMediaIndexRepository(
        mediaCount: Long = 0L,
        mediaBytes: Long = 0L,
    ) : MediaIndexRepository {
        private val totals = LibraryTotals(mediaCount, mediaBytes)
        override fun observeTotals(): Flow<LibraryTotals> = flowOf(totals)
        override suspend fun totals() = totals
    }

    /** Runner whose body is supplied by the test; used to drive a real scan run. */
    private class LambdaScanRunner(
        private val block: suspend (Boolean, suspend (ScanProgress) -> Unit) -> ScanOutcome,
    ) : ScanRunner {
        override suspend fun run(
            partialAccess: Boolean,
            onProgress: suspend (ScanProgress) -> Unit,
        ): ScanOutcome = block(partialAccess, onProgress)
    }

    private fun controller(
        runner: ScanRunner = LambdaScanRunner { _, _ -> awaitCancellation() },
    ) = ScanController(CoroutineScope(UnconfinedTestDispatcher()), runner)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        status: ScanStatus,
        access: MediaAccess = MediaAccess.FULL,
        mediaCount: Long = 0L,
        mediaBytes: Long = 0L,
        scanState: FakeScanStateRepository = FakeScanStateRepository(status),
        permission: FakePermissionMonitor = FakePermissionMonitor(access),
        scanController: ScanController = controller(),
    ) = HomeViewModel(
        ObserveStorageSummaryUseCase(
            scanState,
            FakeMediaIndexRepository(mediaCount, mediaBytes),
            com.noise.mediasweep.testing.FakeCandidateRepository(),
        ),
        permission,
        scanController,
    )

    @Test
    fun `first launch reports that no scan has run yet`() = runTest {
        assertEquals(HomeUiState.NotScanned, viewModel(ScanStatus.NOT_SCANNED).uiState.value)
    }

    @Test
    fun `missing permission wins over any scan state`() = runTest {
        assertEquals(
            HomeUiState.NoPermission,
            viewModel(ScanStatus.COMPLETE, access = MediaAccess.DENIED).uiState.value,
        )
    }

    @Test
    fun `partial access still shows scan results`() = runTest {
        val state = viewModel(
            status = ScanStatus.PARTIAL,
            access = MediaAccess.PARTIAL,
            mediaCount = 10L,
            mediaBytes = 4_096L,
        ).uiState.value
        assertTrue(state is HomeUiState.Partial)
    }

    @Test
    fun `full access with a real library shows the summary`() = runTest {
        val state = viewModel(
            status = ScanStatus.COMPLETE,
            mediaCount = 25L,
            mediaBytes = 10_240L,
        ).uiState.value
        assertTrue(state is HomeUiState.Complete)
    }

    @Test
    fun `running scan is reported as scanning without fabricated numbers`() = runTest {
        val state = viewModel(ScanStatus.SCANNING).uiState.value
        assertTrue(state is HomeUiState.Scanning)
        assertEquals(null, (state as HomeUiState.Scanning).progress)
    }

    @Test
    fun `completed scan with an empty library reports the empty state`() = runTest {
        assertEquals(HomeUiState.NoMedia, viewModel(ScanStatus.COMPLETE).uiState.value)
    }

    @Test
    fun `failed scan reports a failure state`() = runTest {
        assertTrue(viewModel(ScanStatus.FAILED).uiState.value is HomeUiState.Failed)
    }

    @Test
    fun `state updates reactively when the persisted scan state changes`() = runTest {
        val scanState = FakeScanStateRepository(ScanStatus.NOT_SCANNED)
        val viewModel = viewModel(ScanStatus.NOT_SCANNED, scanState = scanState)
        assertEquals(HomeUiState.NotScanned, viewModel.uiState.value)

        scanState.status.value = ScanStatus.FAILED
        assertTrue(viewModel.uiState.value is HomeUiState.Failed)
    }

    @Test
    fun `revoking permission while the app is open switches to the permission screen`() = runTest {
        val permission = FakePermissionMonitor(MediaAccess.FULL)
        val viewModel = viewModel(ScanStatus.NOT_SCANNED, permission = permission)
        assertEquals(HomeUiState.NotScanned, viewModel.uiState.value)

        permission.value.value = MediaAccess.DENIED
        assertEquals(HomeUiState.NoPermission, viewModel.uiState.value)
    }

    @Test
    fun `a live scan publishes its real counters instead of the stored status`() = runTest {
        val runner = LambdaScanRunner { _, onProgress ->
            onProgress(ScanProgress(4_832L, 12_240L, emptyMap(), ScanPhase.FINGERPRINTING))
            awaitCancellation()
        }
        val scanController = controller(runner)
        val viewModel = viewModel(ScanStatus.SCANNING, scanController = scanController)

        scanController.start(partialAccess = false)

        val state = viewModel.uiState.value
        assertTrue(state is HomeUiState.Scanning)
        val progress = (state as HomeUiState.Scanning).progress
        assertEquals(4_832L, progress?.processedCount)
        assertEquals(12_240L, progress?.totalCount)

        // Clean up the live run so nothing is left running after the test.
        scanController.cancel()
    }
}
