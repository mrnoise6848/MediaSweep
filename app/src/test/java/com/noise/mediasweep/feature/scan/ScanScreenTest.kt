package com.noise.mediasweep.feature.scan

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.noise.mediasweep.data.repository.ScanOutcome
import com.noise.mediasweep.data.repository.ScanRunState
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanPhase
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.ui.theme.MediaSweepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-side Compose UI tests for the scan screen (specification §18, §27, §45).
 *
 * Every number on screen comes from a real counter: progress is only rendered when the
 * pipeline reported one, cancellation is cooperative and results are shown only after a
 * run actually ended. No device or emulator is used (constraint 0.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ScanScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: ScanRunState,
        partialAccess: Boolean = false,
        onStart: () -> Unit = {},
        onCancel: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaSweepTheme(darkTheme = false, dynamicColor = false) {
                ScanScreen(
                    state = state,
                    partialAccess = partialAccess,
                    onStart = onStart,
                    onCancel = onCancel,
                    onBack = onBack,
                )
            }
        }
    }

    private fun running(progress: ScanProgress?) = ScanRunState.Running(progress)

    @Test
    fun `progress shows the real counters reported by the scanner`() {
        setScreen(
            running(ScanProgress(4_832L, 12_240L, emptyMap(), ScanPhase.INDEXING)),
        )

        composeRule.onNodeWithText("4,832 / 12,240").assertIsDisplayed()
        composeRule.onNodeWithText("Scanning your media").assertIsDisplayed()
        composeRule.onNodeWithText("Analysis runs entirely on this device.").assertIsDisplayed()
    }

    @Test
    fun `candidates found so far are listed while the scan runs`() {
        setScreen(
            running(
                ScanProgress(
                    processedCount = 6_000L,
                    totalCount = 12_240L,
                    foundCounts = mapOf(
                        CandidateType.EXACT_DUPLICATE to 48,
                        CandidateType.SCREENSHOT to 183,
                    ),
                    phase = ScanPhase.FINGERPRINTING,
                ),
            ),
        )

        composeRule.onNodeWithText("Found:").assertIsDisplayed()
        composeRule.onNodeWithText("Exact duplicates: 48").assertIsDisplayed()
        composeRule.onNodeWithText("Screenshots: 183").assertIsDisplayed()
        // Counts that do not exist are never invented.
        composeRule.onNodeWithText("Large files: 17").assertDoesNotExist()
    }

    @Test
    fun `no percentage is invented before a trustworthy counter exists`() {
        setScreen(running(progress = null))

        composeRule.onNodeWithText("Scanning your media").assertIsDisplayed()
        composeRule.onNodeWithText("0 / 0").assertDoesNotExist()
        composeRule.onNodeWithText("No candidates found yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `progress is announced to assistive tech as a readable sentence`() {
        setScreen(running(ScanProgress(4_832L, 12_240L, emptyMap(), ScanPhase.INDEXING)))

        // The visible line stays terse; TalkBack gets "… of … items scanned".
        composeRule.onNodeWithText("4,832 / 12,240").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("4,832 of 12,240 items scanned")
            .assertIsDisplayed()
    }

    @Test
    fun `cancel is offered while the scan runs`() {
        var cancelled = false
        setScreen(
            state = running(ScanProgress(10L, 100L)),
            onCancel = { cancelled = true },
        )

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(true, cancelled)
    }

    @Test
    fun `partial access states what part of the library is covered`() {
        setScreen(
            state = running(ScanProgress(10L, 100L)),
            partialAccess = true,
        )

        composeRule.onNodeWithText("Showing results from available media.").assertIsDisplayed()
    }

    @Test
    fun `stopping the scan reports that it is winding down`() {
        var cancelled = false
        setScreen(state = ScanRunState.Cancelling, onCancel = { cancelled = true })

        composeRule.onNodeWithText("Stopping the scan…").assertIsDisplayed()
        // No further cancellation can be requested while one is already in flight.
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        assertEquals(false, cancelled)
    }

    @Test
    fun `a cancelled scan claims no completion and can be restarted`() {
        var started = false
        setScreen(state = ScanRunState.Cancelled, onStart = { started = true })

        composeRule.onNodeWithText("Scan cancelled").assertIsDisplayed()
        composeRule.onNodeWithText("The scan stopped safely. Nothing was marked as complete.")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Scan again").performClick()
        assertEquals(true, started)
    }

    @Test
    fun `a completed scan reports what was actually scanned`() {
        setScreen(
            state = ScanRunState.Finished(
                ScanOutcome(
                    status = ScanStatus.COMPLETE,
                    mediaCount = 12_240L,
                    mediaBytes = 4L * 1024 * 1024 * 1024,
                    groupCounts = mapOf(
                        CandidateType.EXACT_DUPLICATE to 48,
                        CandidateType.NEAR_DUPLICATE to 21,
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Scan complete").assertIsDisplayed()
        composeRule.onNodeWithText("Scanned 12,240 items • 4 GB").assertIsDisplayed()
        composeRule.onNodeWithText("Exact duplicates: 48").assertIsDisplayed()
        composeRule.onNodeWithText("Near duplicates: 21").assertIsDisplayed()
        composeRule.onNodeWithText("Scan again").assertIsDisplayed()
    }

    @Test
    fun `a partial scan never claims the whole library was seen`() {
        setScreen(
            state = ScanRunState.Finished(
                ScanOutcome(
                    status = ScanStatus.PARTIAL,
                    mediaCount = 40L,
                    mediaBytes = 1_024L,
                    groupCounts = emptyMap(),
                ),
            ),
        )

        composeRule.onNodeWithText("Scanning selected media").assertIsDisplayed()
        composeRule.onNodeWithText("Showing results from available media.").assertIsDisplayed()
        composeRule.onNodeWithText("Scanned 40 items • 1 KB").assertIsDisplayed()
        composeRule.onNodeWithText("Scan complete").assertDoesNotExist()
    }

    @Test
    fun `a failed scan shows the real error and a retry`() {
        var retried = false
        setScreen(
            state = ScanRunState.Finished(
                ScanOutcome(
                    status = ScanStatus.FAILED,
                    mediaCount = 0L,
                    mediaBytes = 0L,
                    groupCounts = emptyMap(),
                    errorMessage = "MediaStore unavailable",
                ),
            ),
            onStart = { retried = true },
        )

        composeRule.onNodeWithText("Scan failed").assertIsDisplayed()
        composeRule.onNodeWithText("MediaStore unavailable").assertIsDisplayed()

        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(true, retried)
    }

    @Test
    fun `an idle screen offers the first scan`() {
        var started = false
        setScreen(state = ScanRunState.Idle, onStart = { started = true })

        composeRule.onNodeWithText("No scan yet").assertIsDisplayed()
        composeRule.onNodeWithText("Scan now").performClick()

        assertEquals(true, started)
    }

    @Test
    fun `back is always available`() {
        var backed = false
        setScreen(state = ScanRunState.Cancelled, onBack = { backed = true })

        composeRule.onNodeWithText("Back").performClick()

        assertEquals(true, backed)
    }
}
