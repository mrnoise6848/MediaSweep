package com.noise.mediasweep.feature.groupdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.noise.mediasweep.core.common.formatDate
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.feature.review.REVIEW_ACTION_TAG
import com.noise.mediasweep.feature.review.ReviewSelection
import com.noise.mediasweep.feature.review.SelectedMedia
import com.noise.mediasweep.testing.candidateGroup
import com.noise.mediasweep.testing.mediaItem
import com.noise.mediasweep.ui.theme.MediaSweepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-side Compose UI tests for the image comparison screen (specification §29).
 * No device or emulator is used (constraint 0.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class GroupDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val photoA = mediaItem(
        id = 1L,
        displayName = "beach.jpg",
        sizeBytes = 1_800_000L,
        dateModified = 1_700_000_000L,
    )
    private val photoB = mediaItem(
        id = 2L,
        displayName = "beach copy.jpg",
        sizeBytes = 1_800_000L,
        dateModified = 1_700_000_000L,
    )

    private val group = candidateGroup(
        id = 9L,
        type = CandidateType.EXACT_DUPLICATE,
        items = listOf(photoA, photoB),
        reason = "2 identical files: same SHA-256 and same size",
    )

    private fun setScreen(
        state: GroupDetailUiState = GroupDetailUiState.Content(group),
        selection: ReviewSelection = ReviewSelection(),
        onToggle: (com.noise.mediasweep.domain.model.MediaItem) -> Unit = {},
        onSelectGroup: () -> Unit = {},
        onClearGroup: () -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaSweepTheme(darkTheme = false, dynamicColor = false) {
                GroupDetailScreen(
                    state = state,
                    selection = selection,
                    onBack = {},
                    onToggle = onToggle,
                    onSelectGroup = onSelectGroup,
                    onClearGroup = onClearGroup,
                    onReview = onReview,
                )
            }
        }
    }

    @Test
    fun `the reason card explains why the group was surfaced`() {
        setScreen()

        composeRule.onNodeWithText("2 identical files: same SHA-256 and same size")
            .assertIsDisplayed()
        composeRule.onNodeWithText("2 items • 3.43 MB").assertIsDisplayed()
        composeRule.onNodeWithText("HIGH CONFIDENCE").assertIsDisplayed()
    }

    @Test
    fun `the counter shows how many items of the group are selected`() {
        setScreen()

        composeRule.onNodeWithText("0 of 2 selected").assertIsDisplayed()
    }

    @Test
    fun `select all selects the whole group`() {
        var selected = false
        setScreen(onSelectGroup = { selected = true })

        composeRule.onNodeWithText("Select all").performClick()

        assertEquals(true, selected)
    }

    @Test
    fun `a fully selected group offers clearing instead of selecting again`() {
        setScreen(selection = ReviewSelection(listOf(SelectedMedia.from(photoA), SelectedMedia.from(photoB))))

        composeRule.onNodeWithText("2 of 2 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").assertIsDisplayed()
        composeRule.onNodeWithText("Select all").assertDoesNotExist()
    }

    @Test
    fun `toggling one row selects exactly that item`() {
        var toggledId: Long? = null
        setScreen(onToggle = { toggledId = it.id })

        composeRule.onAllNodes(isToggleable())[0].performClick()

        assertEquals(1L, toggledId)
    }

    @Test
    fun `each row shows its name size and date`() {
        setScreen()

        // The first row is visible on arrival.
        composeRule.onNodeWithText("beach.jpg").assertIsDisplayed()
        composeRule.onAllNodesWithText("1.72 MB").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText(formatDate(1_700_000_000L)).onFirst().assertIsDisplayed()

        // The second row sits below the fold: scroll to it like a user, then check it.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("beach copy.jpg"))
        composeRule.onNodeWithText("beach copy.jpg").assertIsDisplayed()
        composeRule.onAllNodesWithText("1.72 MB").onLast().assertIsDisplayed()
        composeRule.onAllNodesWithText(formatDate(1_700_000_000L)).onLast().assertIsDisplayed()
    }

    @Test
    fun `an empty selection disables the review action`() {
        setScreen()

        composeRule.onNodeWithText("Select items to review").assertIsDisplayed()
        composeRule.onNodeWithTag(REVIEW_ACTION_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a selection shows its total and can open the review summary`() {
        var reviewed = false
        setScreen(
            selection = ReviewSelection(listOf(SelectedMedia.from(photoA))),
            onReview = { reviewed = true },
        )

        composeRule.onNodeWithText("1 selected • 1.72 MB").assertIsDisplayed()
        composeRule.onNodeWithTag(REVIEW_ACTION_TAG).assertIsEnabled().performClick()

        assertEquals(true, reviewed)
    }

    @Test
    fun `a vanished group explains itself instead of showing an empty list`() {
        setScreen(state = GroupDetailUiState.NotFound)

        composeRule.onNodeWithText("This candidate no longer exists.").assertIsDisplayed()
        composeRule.onNodeWithText("Your library changed. Run a scan to refresh the results.")
            .assertIsDisplayed()
    }

    @Test
    fun `a missing group never renders toggleable rows`() {
        setScreen(state = GroupDetailUiState.NotFound)

        composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
            .let { assertEquals(0, it.size) }
        composeRule.onNode(isToggleable()).assertDoesNotExist()
    }
}
