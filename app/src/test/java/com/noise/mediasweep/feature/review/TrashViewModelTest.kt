package com.noise.mediasweep.feature.review

import android.app.PendingIntent
import android.content.Intent
import com.noise.mediasweep.MainActivity
import com.noise.mediasweep.core.media.TrashRequest
import com.noise.mediasweep.core.media.TrashRequestFactory
import com.noise.mediasweep.domain.repository.TrashReconciliation
import com.noise.mediasweep.domain.repository.TrashReconciler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Trash flow state machine tests (specification §10, §11, §46).
 *
 * The system dialog itself can never appear under constraint 0.1, so the flow is driven
 * through its seams: what matters is that a cancelled confirmation records nothing, that
 * success re-queries real MediaStore state through the reconciler, and that the confirmed
 * items leave the review selection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrashViewModelTest {

    private class FakeTrashRequestFactory(
        override val isSupported: Boolean = true,
        private val response: TrashRequest,
    ) : TrashRequestFactory {
        var created: List<String>? = null
        var createCount = 0

        override fun create(contentUris: List<String>): TrashRequest {
            created = contentUris
            createCount++
            return response
        }
    }

    private class FakeTrashReconciler(
        private val result: TrashReconciliation = TrashReconciliation(emptyList(), emptyList()),
        private val error: Boolean = false,
    ) : TrashReconciler {
        var requested: List<Long>? = null

        override suspend fun reconcile(requestedIds: List<Long>): TrashReconciliation {
            requested = requestedIds
            if (error) throw IllegalStateException("MediaStore unavailable")
            return result
        }
    }

    private val store = ReviewSelectionStore()

    private fun selected(vararg ids: Long) = ids.forEach { id ->
        store.toggle(
            SelectedMedia(
                id = id,
                contentUri = "content://media/$id",
                displayName = "IMG_$id.jpg",
                sizeBytes = 1_048_576L,
                isVideo = false,
            ),
        )
    }

    private fun sender() = PendingIntent.getActivity(
        RuntimeEnvironment.getApplication(),
        0,
        Intent(RuntimeEnvironment.getApplication(), MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    ).intentSender

    private fun viewModel(
        factory: TrashRequestFactory,
        reconciler: TrashReconciler = FakeTrashReconciler(),
    ) = TrashViewModel(store, factory, reconciler)

    private fun confirmingFactory(isSupported: Boolean = true) = FakeTrashRequestFactory(
        isSupported = isSupported,
        response = TrashRequest.SystemConfirmation(sender()),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is requested while the selection is empty`() {
        val factory = confirmingFactory()

        viewModel(factory).moveToTrash()

        assertNull(factory.created)
    }

    @Test
    fun `a successful request hands the system confirmation to the route exactly once`() {
        selected(1L, 2L)
        val factory = confirmingFactory()
        val vm = viewModel(factory)

        vm.moveToTrash()

        assertTrue(vm.uiState.value is TrashUiState.LaunchConfirmation)
        assertEquals(listOf("content://media/1", "content://media/2"), factory.created)
        assertEquals(1, factory.createCount)

        // A second tap while the confirmation is pending must not fire a second request.
        vm.moveToTrash()
        assertEquals(1, factory.createCount)

        vm.onConfirmationLaunched()
        assertEquals(TrashUiState.AwaitingConfirmation, vm.uiState.value)

        // Tapping again while the system dialog is up is ignored as well.
        vm.moveToTrash()
        assertEquals(1, factory.createCount)
        assertEquals(TrashUiState.AwaitingConfirmation, vm.uiState.value)
    }

    @Test
    fun `a cancelled confirmation records no local deletion state`() {
        selected(1L, 2L)
        val reconciler = FakeTrashReconciler()
        val vm = viewModel(confirmingFactory(), reconciler)

        vm.moveToTrash()
        vm.onConfirmationLaunched()
        vm.onConfirmationResult(android.app.Activity.RESULT_CANCELED)

        assertEquals(TrashUiState.Cancelled, vm.uiState.value)
        // The reconciler is never consulted: only MediaStore state may mark deletions.
        assertNull(reconciler.requested)
        assertEquals(2, store.selection.value.items.size)
    }

    @Test
    fun `a confirmed operation is verified against media store and leaves the selection`() {
        selected(1L, 2L, 3L)
        val reconciler = FakeTrashReconciler(
            result = TrashReconciliation(removedIds = listOf(1L, 3L), stillPresentIds = listOf(2L)),
        )
        val vm = viewModel(confirmingFactory(), reconciler)

        vm.moveToTrash()
        vm.onConfirmationLaunched()
        vm.onConfirmationResult(android.app.Activity.RESULT_OK)

        assertEquals(TrashUiState.Completed(removedCount = 2, stillPresentCount = 1), vm.uiState.value)
        assertEquals(listOf(1L, 2L, 3L), reconciler.requested)
        // Only the items MediaStore reported as gone leave the selection.
        assertEquals(listOf(2L), store.selection.value.items.map { it.id })

        vm.dismissResult()
        assertEquals(TrashUiState.Idle, vm.uiState.value)
    }

    @Test
    fun `media store failure surfaces a rescan hint and keeps the selection`() {
        selected(1L)
        val vm = viewModel(
            confirmingFactory(),
            FakeTrashReconciler(error = true),
        )

        vm.moveToTrash()
        vm.onConfirmationLaunched()
        vm.onConfirmationResult(android.app.Activity.RESULT_OK)

        assertEquals(TrashUiState.VerifyFailed, vm.uiState.value)
        assertEquals(listOf(1L), store.selection.value.items.map { it.id })
    }

    @Test
    fun `an unsupported platform is surfaced without launching anything`() {
        selected(1L)
        val factory = FakeTrashRequestFactory(
            isSupported = false,
            response = TrashRequest.Unsupported,
        )
        val vm = viewModel(factory)

        vm.moveToTrash()

        assertEquals(false, vm.trashSupported)
        assertEquals(TrashUiState.Unsupported, vm.uiState.value)
        assertEquals(1, store.selection.value.items.size)
    }

    @Test
    fun `a request the system refuses changes nothing locally`() {
        selected(1L)
        val vm = viewModel(
            FakeTrashRequestFactory(isSupported = true, response = TrashRequest.Failed),
        )

        vm.moveToTrash()

        assertEquals(TrashUiState.RequestFailed, vm.uiState.value)
        assertEquals(listOf(1L), store.selection.value.items.map { it.id })
    }

    @Test
    fun `a confirmation that fails to launch changes nothing locally`() {
        selected(1L)
        val vm = viewModel(confirmingFactory())

        vm.moveToTrash()
        vm.onLaunchFailed()

        assertEquals(TrashUiState.RequestFailed, vm.uiState.value)
        assertEquals(listOf(1L), store.selection.value.items.map { it.id })
    }

    @Test
    fun `results are ignored when no confirmation is pending`() {
        selected(1L)
        val reconciler = FakeTrashReconciler()
        val vm = viewModel(confirmingFactory(), reconciler)

        vm.onConfirmationResult(android.app.Activity.RESULT_OK)

        assertEquals(TrashUiState.Idle, vm.uiState.value)
        assertNull(reconciler.requested)
        assertEquals(1, store.selection.value.items.size)
    }
}
