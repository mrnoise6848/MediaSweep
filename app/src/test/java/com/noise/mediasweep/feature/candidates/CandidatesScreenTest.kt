package com.noise.mediasweep.feature.candidates

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.ui.theme.MediaSweepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-side Compose UI tests for the candidate list (specification §12/§13).
 * No device or emulator is used (constraint 0.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CandidatesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun summaries(
        exactGb: Long = 4L,
        nearMb: Long = 12L,
        screenshotsMb: Long = 183L,
        largeGb: Long = 17L,
        oldMb: Long = 40L,
    ) = listOf(
        CategorySummary(CandidateType.EXACT_DUPLICATE, 48, 120, exactGb * 1024 * 1024 * 1024),
        CategorySummary(CandidateType.NEAR_DUPLICATE, 12, 12, nearMb * 1024 * 1024),
        CategorySummary(CandidateType.SCREENSHOT, 183, 183, screenshotsMb * 1024 * 1024),
        CategorySummary(CandidateType.LARGE_FILE, 17, 17, largeGb * 1024 * 1024 * 1024),
        CategorySummary(CandidateType.OLD_MEDIA, 40, 40, oldMb * 1024 * 1024),
    )

    private fun setScreen(
        categories: List<CategorySummary>? = summaries(),
        onOpenCategory: (CandidateType) -> Unit = {},
    ) {
        composeRule.setContent {
            MediaSweepTheme(darkTheme = false, dynamicColor = false) {
                CandidatesScreen(
                    categories = categories,
                    onBack = {},
                    onOpenCategory = onOpenCategory,
                )
            }
        }
    }

    @Test
    fun `every category is listed with its label and real size`() {
        setScreen()

        composeRule.onNodeWithText("Exact duplicates").assertIsDisplayed()
        composeRule.onNodeWithText("4 GB").assertIsDisplayed()
        composeRule.onNodeWithText("Near duplicates").assertIsDisplayed()
        composeRule.onNodeWithText("12 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Screenshots").assertIsDisplayed()
        composeRule.onNodeWithText("183 MB").assertIsDisplayed()

        // The last rows sit below the fold: reach them the way a user would, by scrolling.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Large files"))
        composeRule.onNodeWithText("Large files").assertIsDisplayed()
        composeRule.onNodeWithText("17 GB").assertIsDisplayed()

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Old media"))
        composeRule.onNodeWithText("Old media").assertIsDisplayed()
        composeRule.onNodeWithText("40 MB").assertIsDisplayed()
    }

    @Test
    fun `category counts distinguish groups from loose items`() {
        setScreen()

        composeRule.onNodeWithText("48 groups").assertIsDisplayed()
        composeRule.onNodeWithText("183 items").assertIsDisplayed()
    }

    @Test
    fun `tapping a category opens it`() {
        var opened: CandidateType? = null
        setScreen(onOpenCategory = { opened = it })

        composeRule.onNodeWithText("Screenshots").performClick()

        assertEquals(CandidateType.SCREENSHOT, opened)
    }

    @Test
    fun `a clean library explains itself instead of showing empty rows`() {
        setScreen(categories = summaries().map { it.copy(groupCount = 0, itemCount = 0, totalSizeBytes = 0) })

        composeRule.onNodeWithText("Your library looks clean.").assertIsDisplayed()
        composeRule.onNodeWithText("No strong cleanup candidates were found.").assertIsDisplayed()
        composeRule.onNodeWithText("Exact duplicates").assertDoesNotExist()
    }

    @Test
    fun `before the database answers the screen shows loading, not an empty state`() {
        setScreen(categories = null)

        composeRule.onNodeWithText("Loading…").assertIsDisplayed()
        // The empty state is a result, not a placeholder: it must not flash first.
        composeRule.onNodeWithText("Your library looks clean.").assertDoesNotExist()
        composeRule.onNodeWithText("Exact duplicates").assertDoesNotExist()
    }

    @Test
    fun `the subtitle never promises automatic deletion`() {
        setScreen()

        composeRule
            .onNodeWithText("Choose a category to review. Nothing is deleted automatically.")
            .assertIsDisplayed()
    }
}
