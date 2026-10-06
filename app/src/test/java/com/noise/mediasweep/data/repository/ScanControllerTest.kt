package com.noise.mediasweep.data.repository

import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanPhase
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scan runner state machine tests (specification §18, §19, §45).
 *
 * The controller owns one application-scoped run: it publishes real progress, refuses a
 * second concurrent run and, on cancellation, reports "cancelled" only after the pipeline
 * has left the database consistent — never "complete".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanControllerTest {

    private class LambdaScanRunner(
        private val block: suspend (Boolean, suspend (ScanProgress) -> Unit) -> ScanOutcome,
    ) : ScanRunner {
        var invocations = 0
            private set

        override suspend fun run(
            partialAccess: Boolean,
            onProgress: suspend (ScanProgress) -> Unit,
        ): ScanOutcome {
            invocations++
            return block(partialAccess, onProgress)
        }
    }

    private val finishedOutcome = ScanOutcome(
        status = ScanStatus.COMPLETE,
        mediaCount = 12L,
        mediaBytes = 4_096L,
        groupCounts = mapOf(CandidateType.EXACT_DUPLICATE to 3),
    )

    private fun TestScope.controller(runner: ScanRunner) = ScanController(
        CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        runner,
    )

    @Test
    fun `a fresh controller is idle until a scan is requested`() = runTest {
        val runner = LambdaScanRunner { _, _ -> finishedOutcome }
        val controller = controller(runner)

        assertEquals(ScanRunState.Idle, controller.state.value)
        assertFalse(controller.isRunning)

        controller.start(partialAccess = true)
        advanceUntilIdle()

        assertEquals(1, runner.invocations)
        assertEquals(ScanRunState.Finished(finishedOutcome), controller.state.value)
        assertEquals(false, controller.isRunning)
    }

    @Test
    fun `progress is published while the scan runs`() = runTest {
        val runner = LambdaScanRunner { _, onProgress ->
            onProgress(ScanProgress(4_832L, 12_240L, emptyMap(), ScanPhase.FINGERPRINTING))
            awaitCancellation()
        }
        val controller = controller(runner)

        controller.start(partialAccess = false)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is ScanRunState.Running)
        val progress = (state as ScanRunState.Running).progress
        assertEquals(4_832L, progress?.processedCount)
        assertEquals(12_240L, progress?.totalCount)
        assertTrue(controller.isRunning)
    }

    @Test
    fun `only one run may be in flight at a time`() = runTest {
        val runner = LambdaScanRunner { _, onProgress ->
            onProgress(ScanProgress(1L, 10L))
            awaitCancellation()
        }
        val controller = controller(runner)

        assertTrue(controller.start(partialAccess = false))
        advanceUntilIdle()
        // A second request while the first is running is refused, not queued or duplicated.
        assertFalse(controller.start(partialAccess = false))
        advanceUntilIdle()

        assertEquals(1, runner.invocations)
        controller.cancel()
        advanceUntilIdle()
    }

    @Test
    fun `cancelling a running scan reports cancelled and claims no completion`() = runTest {
        val runner = LambdaScanRunner { _, onProgress ->
            onProgress(ScanProgress(2L, 10L))
            awaitCancellation()
        }
        val controller = controller(runner)

        controller.start(partialAccess = false)
        advanceUntilIdle()
        controller.cancel()

        // The state passes through Cancelling, then Cancelled once the run really stopped.
        advanceUntilIdle()
        assertEquals(ScanRunState.Cancelled, controller.state.value)
        assertFalse(controller.isRunning)
    }

    @Test
    fun `cancelling while idle changes nothing`() = runTest {
        val controller = controller(LambdaScanRunner { _, _ -> finishedOutcome })

        controller.cancel()
        advanceUntilIdle()

        assertEquals(ScanRunState.Idle, controller.state.value)
    }

    @Test
    fun `a new scan can start after the previous one finished`() = runTest {
        val runner = LambdaScanRunner { _, _ -> finishedOutcome }
        val controller = controller(runner)

        controller.start(partialAccess = false)
        advanceUntilIdle()
        assertTrue(controller.start(partialAccess = false))
        advanceUntilIdle()

        assertEquals(2, runner.invocations)
        assertEquals(ScanRunState.Finished(finishedOutcome), controller.state.value)
    }

    @Test
    fun `a runner that throws is reported as a failure, never as running`() = runTest {
        val runner = LambdaScanRunner { _, _ -> throw IllegalStateException("MediaStore down") }
        val controller = controller(runner)

        controller.start(partialAccess = false)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state is ScanRunState.Finished)
        val outcome = (state as ScanRunState.Finished).outcome
        assertEquals(ScanStatus.FAILED, outcome.status)
        assertEquals("MediaStore down", outcome.errorMessage)
    }
}
