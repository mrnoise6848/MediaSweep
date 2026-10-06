package com.noise.mediasweep.feature.groupdetail

import com.noise.mediasweep.domain.model.CandidateType
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
class GroupDetailViewModelTest {

    private val group = candidateGroup(
        id = 42L,
        type = CandidateType.EXACT_DUPLICATE,
        items = listOf(mediaItem(1L, sizeBytes = 1_000L), mediaItem(2L, sizeBytes = 2_000L)),
        reason = "2 identical files",
    )

    private fun viewModel(
        repo: FakeCandidateRepository = FakeCandidateRepository(detail = group),
        selection: ReviewSelectionStore = ReviewSelectionStore(),
    ) = GroupDetailViewModel(42L, repo, selection)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an existing group produces content state`() {
        val state = viewModel().uiState.value

        assertEquals(GroupDetailUiState.Content(group), state)
    }

    @Test
    fun `a vanished group produces not found instead of crashing`() {
        val vm = viewModel(repo = FakeCandidateRepository(detail = null))

        assertEquals(GroupDetailUiState.NotFound, vm.uiState.value)
    }

    @Test
    fun `a group id mismatch produces not found`() {
        val repo = FakeCandidateRepository(detail = group.copy(group = group.group.copy(id = 99L)))

        assertEquals(GroupDetailUiState.NotFound, viewModel(repo = repo).uiState.value)
    }

    @Test
    fun `toggling selects one item at a time`() {
        val store = ReviewSelectionStore()
        val vm = viewModel(selection = store)

        vm.toggle(group.items[0])
        assertEquals(setOf(1L), store.selection.value.ids)

        vm.toggle(group.items[1])
        assertEquals(setOf(1L, 2L), store.selection.value.ids)

        vm.toggle(group.items[0])
        assertEquals(setOf(2L), store.selection.value.ids)
    }

    @Test
    fun `select group and clear group only touch this group`() {
        val store = ReviewSelectionStore()
        store.toggle(com.noise.mediasweep.feature.review.SelectedMedia.from(mediaItem(77L)))
        val vm = viewModel(selection = store)

        vm.selectGroup()
        assertEquals(setOf(77L, 1L, 2L), store.selection.value.ids)

        vm.clearGroup()
        assertEquals(setOf(77L), store.selection.value.ids)
    }

    @Test
    fun `selection totals reflect exactly what is selected`() {
        val store = ReviewSelectionStore()
        val vm = viewModel(selection = store)

        vm.selectGroup()

        assertEquals(2, store.selection.value.items.size)
        assertEquals(3_000L, store.selection.value.totalBytes)
        assertTrue(store.selection.value.photoCount == 2)
    }
}
