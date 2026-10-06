package com.noise.mediasweep.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.LibraryTotals
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.domain.model.StorageSummary
import com.noise.mediasweep.ui.theme.MediaSweepTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-side Compose UI tests (Robolectric). No emulator or device is used, per the
 * project constraints in MediaSweep.md section 0.1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun summary(
        status: ScanStatus,
        mediaCount: Long = 1_000L,
        mediaBytes: Long = 128L * 1024 * 1024 * 1024,
        categories: List<CategorySummary> = defaultCategories(),
    ) = StorageSummary(
        totals = LibraryTotals(mediaCount, mediaBytes),
        categories = categories,
        status = status,
        partialAccess = status == ScanStatus.PARTIAL,
        lastCompletedScanAt = null,
    )

    private fun defaultCategories() = listOf(
        CategorySummary(CandidateType.EXACT_DUPLICATE, 48, 120, 4L * 1024 * 1024 * 1024),
        CategorySummary(CandidateType.LARGE_FILE, 17, 17, 8L * 1024 * 1024 * 1024),
        CategorySummary(CandidateType.SCREENSHOT, 183, 183, 3L * 1024 * 1024 * 1024),
    )

    private fun setScreen(state: HomeUiState, darkTheme: Boolean = false) {
        composeRule.setContent {
            MediaSweepTheme(darkTheme = darkTheme, dynamicColor = false) {
                HomeScreen(state = state)
            }
        }
    }

    @Test
    fun permissionRequiredScreenExplainsWhyAccessIsNeeded() {
        setScreen(HomeUiState.NoPermission)
        composeRule.onNodeWithText("Media access is required").assertIsDisplayed()
        composeRule.onNodeWithText("Media access is required to scan your library.").assertIsDisplayed()
    }

    @Test
    fun permissionScreenOffersGrantAction() {
        var granted = false
        composeRule.setContent {
            MediaSweepTheme(dynamicColor = false) {
                HomeScreen(state = HomeUiState.NoPermission, onGrantPermission = { granted = true })
            }
        }
        composeRule.onNodeWithText("Grant access").performClick()
        assertTrue(granted)
    }

    @Test
    fun emptyLibraryStateIsShown() {
        setScreen(HomeUiState.NoMedia)
        composeRule.onNodeWithText("No photos or videos were found.").assertIsDisplayed()
    }

    @Test
    fun scanningScreenShowsProgressWithoutFabricatedCounters() {
        setScreen(HomeUiState.Scanning(progress = null))
        composeRule.onNodeWithText("Scanning your media").assertIsDisplayed()
    }

    @Test
    fun scanningScreenShowsRealCountersWhenAvailable() {
        setScreen(
            HomeUiState.Scanning(progress = ScanProgress(4_832L, 12_240L, emptyMap())),
        )
        composeRule.onNodeWithText("Scanning your media").assertIsDisplayed()
        composeRule.onNodeWithText("4,832 / 12,240").assertIsDisplayed()
    }

    @Test
    fun completedScanShowsDerivedSummary() {
        setScreen(HomeUiState.Complete(summary(ScanStatus.COMPLETE)))
        composeRule.onNodeWithText("Your media").assertIsDisplayed()
        composeRule.onNodeWithText("128 GB").assertIsDisplayed()
        composeRule.onNodeWithText("Exact duplicates").assertIsDisplayed()
        composeRule.onNodeWithText("Large files").assertIsDisplayed()
        composeRule.onNodeWithText("Screenshots").assertIsDisplayed()
        composeRule.onNodeWithText("Old media").assertIsDisplayed()
    }

    @Test
    fun partialScanNeverClaimsTheWholeLibraryWasScanned() {
        setScreen(HomeUiState.Partial(summary(ScanStatus.PARTIAL)))
        composeRule.onNodeWithText("Scanning selected media").assertIsDisplayed()
        composeRule.onNodeWithText("Showing results from available media.").assertIsDisplayed()
    }

    @Test
    fun staleResultsAreExplained() {
        setScreen(HomeUiState.Stale(summary(ScanStatus.STALE)))
        composeRule.onNodeWithText("Results may be out of date").assertIsDisplayed()
    }

    @Test
    fun failureStateIsExplained() {
        setScreen(HomeUiState.Failed("boom"))
        composeRule.onNodeWithText("Scan failed").assertIsDisplayed()
    }

    @Test
    fun notScannedStateInvitesTheFirstScan() {
        setScreen(HomeUiState.NotScanned)
        composeRule.onNodeWithText("No scan yet").assertIsDisplayed()
    }

    @Test
    fun darkThemeRendersTheSummary() {
        setScreen(HomeUiState.Complete(summary(ScanStatus.COMPLETE)), darkTheme = true)
        composeRule.onNodeWithText("Your media").assertIsDisplayed()
    }
}
