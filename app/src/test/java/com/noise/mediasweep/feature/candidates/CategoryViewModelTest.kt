package com.noise.mediasweep.feature.candidates

import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.feature.review.ReviewSelectionStore
import com.noise.mediasweep.testing.FakeCandidateRepository
import com.noise.mediasweep.testing.candidateGroup
import com.noise.mediasweep.testing.mediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    private val type = CandidateType.EXACT_DUPLICATE

    private val smallGroup = candidateGroup(
        id = 1L,
        type = type,
        items = listOf(mediaItem(1L, sizeBytes = 100L, dateModified = 1_000L), mediaItem(2L, sizeBytes = 100L, dateModified = 3_000L)),
        reason = "2 identical files",
    )
    private val largeGroup = candidateGroup(
        id = 2L,
        type = type,
        items = listOf(mediaItem(3L, sizeBytes = 900L, dateModified = 2_000L)),
        reason = "1 identical file",
    )
    private val middleGroup = candidateGroup(
        id = 3L,
        type = type,
        items = listOf(mediaItem(4L, sizeBytes = 500L, dateModified = 9_000L)),
        reason = "1 identical file",
    )

    private fun repository() = FakeCandidateRepository(
        groups = mapOf(type to listOf(smallGroup, largeGroup, middleGroup)),
    )

    private fun viewModel(
        repo: FakeCandidateRepository = repository(),
        selection: ReviewSelectionStore = ReviewSelectionStore(),
    ) = CategoryViewModel(type, repo, selection)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `groups default to largest first`() {
        val state = viewModel().uiState.value

        assertEquals(listOf(2L, 3L, 1L), state.groups.map { it.group.id })
        assertEquals(GroupSort.LARGEST, state.sort)
    }

    @Test
    fun `sorting by newest uses the most recent member date`() {
        val vm = viewModel()

        vm.setSort(GroupSort.NEWEST)

        // middleGroup: 9_000, smallGroup max: 3_000, largeGroup: 2_000
        assertEquals(listOf(3L, 1L, 2L), vm.uiState.value.groups.map { it.group.id })
        assertEquals(GroupSort.NEWEST, vm.uiState.value.sort)
    }

    @Test
    fun `sorting by oldest uses the earliest member date`() {
        val vm = viewModel()

        vm.setSort(GroupSort.OLDEST)

        // smallGroup min: 1_000, largeGroup: 2_000, middleGroup: 9_000
        assertEquals(listOf(1L, 2L, 3L), vm.uiState.value.groups.map { it.group.id })
    }

    @Test
    fun `header figures come from the persisted summary when available`() {
        val repo = FakeCandidateRepository(
            groups = mapOf(type to listOf(largeGroup)),
            summaries = listOf(CategorySummary(type, groupCount = 48, itemCount = 120, totalSizeBytes = 4L * 1024 * 1024 * 1024)),
        )

        val state = viewModel(repo).uiState.value

        assertEquals(48, state.groupCount)
        assertEquals(4L * 1024 * 1024 * 1024, state.reviewableBytes)
    }

    @Test
    fun `header figures fall back to the loaded groups`() {
        val state = viewModel().uiState.value

        assertEquals(3, state.groupCount)
        assertEquals(1_600L, state.reviewableBytes)
    }

    @Test
    fun `toggling a group selects and deselects all of its members`() {
        val store = ReviewSelectionStore()
        val vm = viewModel(selection = store)

        vm.toggleGroup(smallGroup)
        assertEquals(setOf(1L, 2L), store.selection.value.ids)
        assertTrue(vm.areAllGroupItemsSelected(smallGroup))

        vm.toggleGroup(smallGroup)
        assertTrue(store.selection.value.isEmpty)
    }

    @Test
    fun `select all and clear operate across every loaded group`() {
        val store = ReviewSelectionStore()
        val vm = viewModel(selection = store)

        vm.selectAll()
        assertEquals(setOf(1L, 2L, 3L, 4L), store.selection.value.ids)

        vm.clearCategory()
        assertTrue(store.selection.value.isEmpty)
    }

    @Test
    fun `an empty category shows an empty state with zeroed headers`() {
        val vm = viewModel(FakeCandidateRepository(groups = mapOf(type to emptyList())))

        assertTrue(vm.uiState.value.isEmpty)
        assertEquals(0, vm.uiState.value.groupCount)
        assertEquals(0L, vm.uiState.value.reviewableBytes)
    }

    @Test
    fun `near duplicate groups keep their confidence badge data`() {
        val repo = FakeCandidateRepository(
            groups = mapOf(
                CandidateType.NEAR_DUPLICATE to listOf(
                    candidateGroup(
                        id = 9L,
                        type = CandidateType.NEAR_DUPLICATE,
                        items = listOf(mediaItem(7L)),
                        confidence = Confidence.MEDIUM,
                        reason = "2 visually similar images",
                    ),
                ),
            ),
        )

        val state = CategoryViewModel(CandidateType.NEAR_DUPLICATE, repo, ReviewSelectionStore()).uiState.value

        assertEquals(Confidence.MEDIUM, state.groups.single().group.confidence)
    }
}
