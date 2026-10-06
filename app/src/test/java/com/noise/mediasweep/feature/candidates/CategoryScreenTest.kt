package com.noise.mediasweep.feature.candidates

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.Confidence
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

/** Host-side Compose UI tests; no device or emulator (constraint 0.1). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class CategoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val duplicateGroup = candidateGroup(
        id = 1L,
        type = CandidateType.EXACT_DUPLICATE,
        items = listOf(
            mediaItem(1L, sizeBytes = 1_800_000L),
            mediaItem(2L, sizeBytes = 1_800_000L),
            mediaItem(3L, sizeBytes = 1_800_000L),
        ),
        reason = "3 identical files: same SHA-256 and same size",
    )

    private val singleGroup = candidateGroup(
        id = 2L,
        type = CandidateType.EXACT_DUPLICATE,
        items = listOf(mediaItem(4L, sizeBytes = 1_000L)),
        reason = "1 identical file",
        confidence = Confidence.MEDIUM,
    )

    private fun state(
        groups: List<com.noise.mediasweep.domain.model.CandidateGroupWithItems> = listOf(duplicateGroup, singleGroup),
        sort: GroupSort = GroupSort.LARGEST,
        summary: CategorySummary? = CategorySummary(
            CandidateType.EXACT_DUPLICATE,
            groupCount = 48,
            itemCount = 120,
            totalSizeBytes = 4L * 1024 * 1024 * 1024,
        ),
    ) = CategoryUiState(CandidateType.EXACT_DUPLICATE, summary, sort, groups)

    @Test
    fun `category header shows real group count and reviewable size`() {
        setScreen(state())

        composeRule.onNodeWithText("48 groups").assertIsDisplayed()
        composeRule.onNodeWithText("4 GB potentially reviewable").assertIsDisplayed()
    }

    @Test
    fun `group cards show reason, item count, size and confidence`() {
        setScreen(state())

        composeRule.onNodeWithText("3 identical files: same SHA-256 and same size")
            .assertIsDisplayed()
        composeRule.onNodeWithText("3 items • 5.15 MB").assertIsDisplayed()
        composeRule.onNodeWithText("HIGH CONFIDENCE").assertIsDisplayed()

        // The second card's badge is below the fold; scroll the list to reach it.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("MEDIUM CONFIDENCE"))
        composeRule.onNodeWithText("MEDIUM CONFIDENCE").assertIsDisplayed()
    }

    @Test
    fun `tapping a group opens its detail`() {
        var openedId: Long? = null
        setScreen(state(), onOpenGroup = { openedId = it })

        // Reach the second card first, then tap it like a user would.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("1 identical file"))
        composeRule.onNodeWithText("1 identical file").performClick()

        assertEquals(2L, openedId)
    }

    @Test
    fun `sort buttons report the requested order`() {
        var sort: GroupSort? = null
        setScreen(state(), onSort = { sort = it })

        composeRule.onNodeWithText("Newest").performClick()
        assertEquals(GroupSort.NEWEST, sort)

        composeRule.onNodeWithText("Oldest").performClick()
        assertEquals(GroupSort.OLDEST, sort)
    }

    @Test
    fun `empty category shows the empty state instead of blank space`() {
        setScreen(state(groups = emptyList(), summary = null))

        composeRule.onNodeWithText("No candidates in this category.").assertIsDisplayed()
        assertEquals(0, state(groups = emptyList(), summary = null).groupCount)
    }

    @Test
    fun `an empty selection disables the review bar`() {
        setScreen(state(), selection = ReviewSelection())

        composeRule.onNodeWithText("Select items to review").assertIsDisplayed()
        composeRule.onAllNodesWithText("Review")[0].assertIsNotEnabled()
        composeRule.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun `a non-empty selection shows its total in the review bar`() {
        val selected = ReviewSelection(listOf(SelectedMedia.from(duplicateGroup.items[0])))
        setScreen(state(), selection = selected)

        composeRule.onNodeWithText("1 selected • 1.72 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Clear").assertIsDisplayed()
        composeRule.onAllNodesWithText("Review")[0].assertIsEnabled()
    }

    @Test
    fun `review button navigates to the review summary`() {
        var reviewed = false
        setScreen(
            state(),
            selection = ReviewSelection(listOf(SelectedMedia.from(duplicateGroup.items[0]))),
            onReview = { reviewed = true },
        )

        composeRule.onAllNodesWithText("Review")[0].performClick()

        assertEquals(true, reviewed)
    }

    @Test
    fun `toggling a group checkbox reports the toggle`() {
        var toggledId: Long? = null
        setScreen(state(groups = listOf(singleGroup)), onToggleGroup = { toggledId = it.group.id })

        composeRule.onNode(isToggleable()).performClick()

        assertEquals(2L, toggledId)
    }

    private fun setScreen(
        state: CategoryUiState,
        selection: ReviewSelection = ReviewSelection(),
        onSort: (GroupSort) -> Unit = {},
        onToggleGroup: (com.noise.mediasweep.domain.model.CandidateGroupWithItems) -> Unit = {},
        onOpenGroup: (Long) -> Unit = {},
        onReview: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaSweepTheme(darkTheme = false, dynamicColor = false) {
                CategoryScreen(
                    state = state,
                    selection = selection,
                    isGroupSelected = { group -> group.items.all { selection.isSelected(it.id) } },
                    onBack = {},
                    onSort = onSort,
                    onToggleGroup = onToggleGroup,
                    onSelectAll = {},
                    onClearSelection = {},
                    onOpenGroup = onOpenGroup,
                    onReview = onReview,
                )
            }
        }
    }
}
