package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.MediaSweepDatabase
import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.MediaItemEntity
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.testing.FakeMediaStoreDataSource
import com.noise.mediasweep.testing.mediaItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Trash reconciliation tests (specification §11, §46).
 *
 * The local index may only follow what MediaStore actually reports after the system
 * confirmation — never the intent result — and both the candidate lists and the storage
 * summary must refresh from the reconciled state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreTrashReconcilerTest {

    private lateinit var database: MediaSweepDatabase
    private lateinit var mediaStore: FakeMediaStoreDataSource
    private lateinit var reconciler: MediaStoreTrashReconciler

    @Before
    fun setUp() {
        database = MediaSweepDatabase.inMemory(RuntimeEnvironment.getApplication())
            .allowMainThreadQueries()
            .build()
        mediaStore = FakeMediaStoreDataSource()
        reconciler = MediaStoreTrashReconciler(
            mediaStore = mediaStore,
            mediaItemDao = database.mediaItemDao(),
            candidateGroupDao = database.candidateGroupDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun media(id: Long, size: Long) = MediaItemEntity(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        mediaType = 1,
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        sizeBytes = size,
        width = 10,
        height = 10,
        durationMs = null,
        dateAdded = 1L,
        dateModified = 1L,
        relativePath = "DCIM/",
        bucketId = null,
        bucketDisplayName = null,
        isFavorite = false,
        isPending = false,
        isTrashed = false,
    )

    private suspend fun group(
        type: CandidateType,
        vararg memberIds: Long,
        sizePerItem: Long = 100L,
    ): Long {
        val groupId = database.candidateGroupDao().insertGroup(
            CandidateGroupEntity(
                type = type.name,
                confidence = "HIGH",
                totalSizeBytes = sizePerItem * memberIds.size,
                itemCount = memberIds.size,
                reason = "fixture",
                createdAt = 1L,
                sessionId = null,
            ),
        )
        database.candidateGroupDao().insertMembers(
            memberIds.mapIndexed { index, id ->
                CandidateGroupMemberEntity(groupId, id, index)
            },
        )
        return groupId
    }

    /**
     * Media 1 and 4 are gone from MediaStore (confirmed trash), 2 and 3 remain: only the
     * confirmed rows may change, and everything derived must refresh from that truth.
     */
    @Test
    fun `confirmed trash removes only what media store no longer reports`() = runBlocking {
        database.mediaItemDao().upsertAll(
            listOf(media(1L, 100L), media(2L, 200L), media(3L, 300L), media(4L, 400L)),
        )
        // A duplicate group that keeps two survivors, one that keeps a single copy,
        // a singleton category and a near-duplicate pair that both disappeared.
        val survivingDuplicates = group(CandidateType.EXACT_DUPLICATE, 1L, 2L, 3L)
        val halvedDuplicates = group(CandidateType.EXACT_DUPLICATE, 3L, 4L)
        val screenshot = group(CandidateType.SCREENSHOT, 1L)
        val vanishedNearDuplicates = group(CandidateType.NEAR_DUPLICATE, 1L, 2L)
        mediaStore.items = listOf(
            mediaItem(2L, sizeBytes = 200L),
            mediaItem(3L, sizeBytes = 300L),
        )

        val result = reconciler.reconcile(listOf(1L, 2L, 3L, 4L))

        assertEquals(listOf(1L, 4L), result.removedIds)
        assertEquals(listOf(2L, 3L), result.stillPresentIds)
        assertTrue(result.anyRemoved)
        assertEquals(1, mediaStore.activeIdsQueries)

        // The rows MediaStore still shows are untouched; only confirmed ones are trashed.
        assertEquals(true, database.mediaItemDao().getById(1L)!!.isTrashed)
        assertEquals(true, database.mediaItemDao().getById(4L)!!.isTrashed)
        assertEquals(false, database.mediaItemDao().getById(2L)!!.isTrashed)
        assertEquals(false, database.mediaItemDao().getById(3L)!!.isTrashed)

        // The storage summary refreshes from the reconciled state (active media only).
        val totals = RoomMediaIndexRepository(database.mediaItemDao()).observeTotals().first()
        assertEquals(2L, totals.mediaCount)
        assertEquals(500L, totals.mediaBytes)

        // Surviving duplicates keep their group with corrected counts and size.
        val summaries = RoomCandidateRepository(database.candidateGroupDao())
            .observeCategorySummaries().first()
        val exact = summaries.first { it.type == CandidateType.EXACT_DUPLICATE }
        assertEquals(1, exact.groupCount)
        assertEquals(2, exact.itemCount)
        assertEquals(500L, exact.totalSizeBytes)
        assertEquals(true, database.candidateGroupDao().getGroup(survivingDuplicates) != null)

        // A duplicate group with a single copy left is no longer a duplicate candidate,
        // and groups whose members are all gone disappear entirely.
        assertNull(database.candidateGroupDao().getGroup(halvedDuplicates))
        assertNull(database.candidateGroupDao().getGroup(screenshot))
        assertNull(database.candidateGroupDao().getGroup(vanishedNearDuplicates))
        assertEquals(false, summaries.any { it.type == CandidateType.SCREENSHOT })
        assertEquals(false, summaries.any { it.type == CandidateType.NEAR_DUPLICATE })
    }

    @Test
    fun `a cancelled confirmation records no local deletion state`() = runBlocking {
        database.mediaItemDao().upsertAll(listOf(media(1L, 100L), media(2L, 200L)))
        val duplicates = group(CandidateType.EXACT_DUPLICATE, 1L, 2L)
        // Everything the user selected is still visible: nothing was trashed.
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L))

        val result = reconciler.reconcile(listOf(1L, 2L))

        assertEquals(emptyList<Long>(), result.removedIds)
        assertEquals(listOf(1L, 2L), result.stillPresentIds)
        assertEquals(false, result.anyRemoved)
        assertEquals(false, database.mediaItemDao().getById(1L)!!.isTrashed)
        assertEquals(false, database.mediaItemDao().getById(2L)!!.isTrashed)
        assertNotNull(database.candidateGroupDao().getGroup(duplicates))
        assertEquals(
            2L,
            RoomMediaIndexRepository(database.mediaItemDao()).observeTotals().first().mediaCount,
        )
    }

    @Test
    fun `an empty request never queries media store`() = runBlocking {
        val result = reconciler.reconcile(emptyList())

        assertEquals(emptyList<Long>(), result.removedIds)
        assertEquals(emptyList<Long>(), result.stillPresentIds)
        assertEquals(0, mediaStore.activeIdsQueries)
    }

    @Test
    fun `duplicate requests are reconciled once`() = runBlocking {
        database.mediaItemDao().upsertAll(listOf(media(1L, 100L)))
        mediaStore.items = emptyList()

        val result = reconciler.reconcile(listOf(1L, 1L, 1L))

        assertEquals(listOf(1L), result.removedIds)
        assertEquals(1, mediaStore.activeIdsQueries)
    }
}
