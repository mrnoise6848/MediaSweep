package com.noise.mediasweep.feature.scan

import com.noise.mediasweep.core.media.MediaAccess
import com.noise.mediasweep.core.media.MediaPermissionMonitor
import com.noise.mediasweep.data.repository.ScanController
import com.noise.mediasweep.data.repository.ScanOutcome
import com.noise.mediasweep.data.repository.ScanRunner
import com.noise.mediasweep.data.repository.ScanRunState
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan screen view model tests (specification §18, §27, §45).
 *
 * The run lives in the application-scoped controller, so the view model only decides when
 * to start (never a duplicate run), how to cancel and whether the run was partial.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {

    private class LambdaScanRunner(
        private val block: suspend (Boolean, suspend (ScanProgress) -> Unit) -> ScanOutcome,
    ) : ScanRunner {
        var invocations = 0
            private set
        var lastPartialAccess: Boolean? = null
            private set

        override suspend fun run(
            partialAccess: Boolean,
            onProgress: suspend (ScanProgress) -> Unit,
        ): ScanOutcome {
            invocations++
            lastPartialAccess = partialAccess
            return block(partialAccess, onProgress)
        }
    }

    private class FakePermissionMonitor(status: MediaAccess) : MediaPermissionMonitor {
        val value = kotlinx.coroutines.flow.MutableStateFlow(status)
        override val status: StateFlow<MediaAccess> = value
        override fun refresh() = Unit
    }

    private val finishedOutcome = ScanOutcome(
        status = ScanStatus.COMPLETE,
        mediaCount = 8L,
        mediaBytes = 2_048L,
        groupCounts = mapOf(CandidateType.SCREENSHOT to 4),
    )

    private fun TestScope.controller(runner: ScanRunner) = ScanController(
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        runner,
    )

    private fun TestScope.viewModel(
        controller: ScanController,
        access: MediaAccess = MediaAccess.FULL,
    ) = ScanViewModel(controller, FakePermissionMonitor(access))

    @Test
    fun `opening the scan screen starts a scan when none is running`() = runTest {
        val runner = LambdaScanRunner { _, onProgress ->
            onProgress(ScanProgress(1L, 4L))
            awaitCancellation()
        }
        val controller = controller(runner)

        viewModel(controller)

        advanceUntilIdle()
        assertEquals(1, runner.invocations)
        assertTrue(controller.state.value is ScanRunState.Running)
    }

    @Test
    fun `opening the screen while a scan runs never starts a second one`() = runTest {
        val runner = LambdaScanRunner { _, _ -> awaitCancellation() }
        val controller = controller(runner)
        controller.start(partialAccess = false)
        advanceUntilIdle()

        val viewModel = viewModel(controller)
        advanceUntilIdle()

        assertEquals(1, runner.invocations)
        assertEquals(controller.state, viewModel.runState)
    }

    @Test
    fun `re-entering after a finished scan shows that outcome instead of rescanning`() = runTest {
        val runner = LambdaScanRunner { _, _ -> finishedOutcome }
        val controller = controller(runner)
        controller.start(partialAccess = false)
        advanceUntilIdle()

        val viewModel = viewModel(controller)
        advanceUntilIdle()

        assertEquals(1, runner.invocations)
        assertEquals(ScanRunState.Finished(finishedOutcome), viewModel.runState.value)
    }

    @Test
    fun `partial access is read from the real permission grant`() = runTest {
        val runner = LambdaScanRunner { _, _ -> awaitCancellation() }
        val controller = controller(runner)

        val viewModel = viewModel(controller, access = MediaAccess.PARTIAL)
        advanceUntilIdle()

        assertEquals(true, runner.lastPartialAccess)
        assertEquals(true, viewModel.partialAccess.value)

        viewModel.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `denied permission starts nothing`() = runTest {
        val runner = LambdaScanRunner { _, _ -> finishedOutcome }
        val controller = controller(runner)

        viewModel(controller, access = MediaAccess.DENIED)

        advanceUntilIdle()
        assertEquals(0, runner.invocations)
        assertEquals(ScanRunState.Idle, controller.state.value)
    }

    @Test
    fun `cancelling from the screen stops the run without claiming completion`() = runTest {
        val runner = LambdaScanRunner { _, _ -> awaitCancellation() }
        val controller = controller(runner)
        val viewModel = viewModel(controller)
        advanceUntilIdle()
        assertEquals(1, runner.invocations)

        viewModel.cancel()
        advanceUntilIdle()

        assertEquals(ScanRunState.Cancelled, viewModel.runState.value)
        assertFalse(controller.isRunning)
    }
}
