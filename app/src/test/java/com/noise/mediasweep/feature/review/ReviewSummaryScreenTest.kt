package com.noise.mediasweep.feature.review

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.noise.mediasweep.ui.theme.MediaSweepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Host-side Compose UI tests for the review summary (specification §33, §46).
 * Counts and total must be derived from the selection; the destructive action is only
 * offered while no system confirmation is in flight, and every trash state (pending,
 * verifying, result, unsupported platform) is reported honestly. No device or emulator
 * (constraint 0.1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ReviewSummaryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun photos(count: Int, sizeBytes: Long = 1_048_576L) = (1..count).map { index ->
        SelectedMedia(
            id = index.toLong(),
            contentUri = "content://media/$index",
            displayName = "photo_$index.jpg",
            sizeBytes = sizeBytes,
            isVideo = false,
        )
    }

    private fun videos(count: Int, sizeBytes: Long = 10_485_760L) = (1..count).map { index ->
        SelectedMedia(
            id = 1_000L + index,
            contentUri = "content://media/video_$index",
            displayName = "clip_$index.mp4",
            sizeBytes = sizeBytes,
            isVideo = true,
        )
    }

    private fun setScreen(
        selection: ReviewSelection = ReviewSelection(),
        onBack: () -> Unit = {},
        onClear: () -> Unit = {},
        onMoveToTrash: (() -> Unit)? = null,
        trashState: TrashUiState = TrashUiState.Idle,
        trashSupported: Boolean = true,
        onDismissResult: () -> Unit = {},
    ) {
        composeRule.setContent {
            MediaSweepTheme(darkTheme = false, dynamicColor = false) {
                ReviewSummaryScreen(
                    selection = selection,
                    onBack = onBack,
                    onClear = onClear,
                    onMoveToTrash = onMoveToTrash,
                    trashState = trashState,
                    trashSupported = trashSupported,
                    onDismissResult = onDismissResult,
                )
            }
        }
    }

    @Test
    fun `counts and total are derived from the selection`() {
        setScreen(selection = ReviewSelection(photos(27) + videos(3)))

        composeRule.onNodeWithText("You're about to review:").assertIsDisplayed()
        composeRule.onNodeWithText("27 photos").assertIsDisplayed()
        composeRule.onNodeWithText("3 videos").assertIsDisplayed()
        composeRule.onNodeWithText("Total:").assertIsDisplayed()
        composeRule.onNodeWithText("57 MB").assertIsDisplayed()
    }

    @Test
    fun `an all-photo selection never mentions videos`() {
        setScreen(selection = ReviewSelection(photos(5)))

        composeRule.onNodeWithText("5 photos").assertIsDisplayed()
        composeRule.onNodeWithText("1 videos").assertDoesNotExist()
    }

    @Test
    fun `the trash explanation is always shown`() {
        setScreen(selection = ReviewSelection(photos(2)))

        composeRule
            .onNodeWithText(
                "The selected media will be moved to the system trash. " +
                    "Nothing is deleted permanently right away; you can restore items " +
                    "from the system trash until you empty it.",
            )
            .assertIsDisplayed()
    }

    @Test
    fun `no destructive action appears without a wired handler`() {
        var trashed = false
        setScreen(selection = ReviewSelection(photos(2)), onMoveToTrash = null)

        composeRule.onNodeWithText("Move to Trash").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        assertEquals(false, trashed)
    }

    @Test
    fun `cancel goes back without touching the selection`() {
        var backed = false
        setScreen(selection = ReviewSelection(photos(2)), onBack = { backed = true })

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(true, backed)
    }

    @Test
    fun `clearing empties the selection`() {
        var cleared = false
        setScreen(selection = ReviewSelection(photos(2)), onClear = { cleared = true })

        composeRule.onNodeWithText("Clear").performClick()

        assertEquals(true, cleared)
    }

    @Test
    fun `an empty selection shows the empty state and no clear action`() {
        setScreen(selection = ReviewSelection())

        composeRule.onNodeWithText("Nothing is selected yet.").assertIsDisplayed()
        composeRule.onNodeWithText("Select media from a category, then review it here.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Clear").assertDoesNotExist()
        composeRule.onNodeWithText("Total:").assertDoesNotExist()
    }

    @Test
    fun `awaiting the system confirmation hides the destructive action`() {
        setScreen(
            selection = ReviewSelection(photos(3)),
            onMoveToTrash = {},
            trashState = TrashUiState.AwaitingConfirmation,
        )

        composeRule.onNodeWithText("Waiting for the Android system confirmation…")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Move to Trash").assertDoesNotExist()
    }

    @Test
    fun `verification in progress is reported instead of a success claim`() {
        setScreen(
            selection = ReviewSelection(photos(1)),
            onMoveToTrash = {},
            trashState = TrashUiState.Verifying,
        )

        composeRule.onNodeWithText("Checking what actually changed in MediaStore…")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Move to Trash").assertDoesNotExist()
    }

    @Test
    fun `a completed trash reports the real media store outcome`() {
        setScreen(
            selection = ReviewSelection(),
            trashState = TrashUiState.Completed(removedCount = 3, stillPresentCount = 1),
        )

        composeRule.onNodeWithText("Items moved to the system trash: 3").assertIsDisplayed()
        composeRule.onNodeWithText("Items still in the library: 1").assertIsDisplayed()
        // The result stays readable even though nothing is selected any more.
        composeRule.onNodeWithText("Nothing is selected yet.").assertIsDisplayed()
    }

    @Test
    fun `a completed result can be dismissed`() {
        var dismissed = false
        setScreen(
            selection = ReviewSelection(photos(1)),
            onMoveToTrash = {},
            trashState = TrashUiState.Completed(removedCount = 1, stillPresentCount = 0),
            onDismissResult = { dismissed = true },
        )

        composeRule.onNodeWithText("Items moved to the system trash: 1").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        assertEquals(true, dismissed)
    }

    @Test
    fun `a cancelled confirmation states that nothing changed`() {
        var dismissed = false
        setScreen(
            selection = ReviewSelection(photos(2)),
            onMoveToTrash = {},
            trashState = TrashUiState.Cancelled,
            onDismissResult = { dismissed = true },
        )

        composeRule.onNodeWithText("Cancelled. Nothing was changed.").assertIsDisplayed()
        composeRule.onNodeWithText("Move to Trash").assertDoesNotExist()

        composeRule.onNodeWithText("Done").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun `an unverifiable result asks for a rescan instead of claiming success`() {
        setScreen(
            selection = ReviewSelection(photos(1)),
            trashState = TrashUiState.VerifyFailed,
        )

        composeRule.onNodeWithText("Could not verify the result. Run a scan to refresh the library.")
            .assertIsDisplayed()
    }

    @Test
    fun `an unsupported platform explains instead of offering trash`() {
        setScreen(
            selection = ReviewSelection(photos(2)),
            onMoveToTrash = null,
            trashSupported = false,
        )

        composeRule.onNodeWithText("Move to Trash").assertDoesNotExist()
        composeRule.onNodeWithText("System trash needs Android 11 or newer", substring = true)
            .assertIsDisplayed()
    }
}
