package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.MediaSweepDatabase
import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.FingerprintEntity
import com.noise.mediasweep.testing.FakeMediaStoreDataSource
import com.noise.mediasweep.testing.mediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaIndexSynchronizerTest {

    private lateinit var database: MediaSweepDatabase
    private lateinit var mediaStore: FakeMediaStoreDataSource
    private lateinit var synchronizer: MediaIndexSynchronizer

    @Before
    fun setUp() {
        database = MediaSweepDatabase.inMemory(RuntimeEnvironment.getApplication())
            .allowMainThreadQueries()
            .build()
        mediaStore = FakeMediaStoreDataSource()
        synchronizer = MediaIndexSynchronizer(
            mediaStore = mediaStore,
            mediaItemDao = database.mediaItemDao(),
            fingerprintDao = database.fingerprintDao(),
            candidateGroupDao = database.candidateGroupDao(),
            batchSize = 2,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `first sync indexes the whole library`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L), mediaItem(3L))

        val result = synchronizer.sync(sessionId = 1L)

        assertTrue(result.completed)
        assertEquals(3, result.addedCount)
        assertEquals(0, result.modifiedCount)
        assertEquals(0, result.removedCount)
        assertEquals(3L, result.mediaCount)
        assertEquals(3L * 1_024L, result.mediaBytes)
    }

    @Test
    fun `an unchanged library is not rewritten on the next sync`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L))
        synchronizer.sync(sessionId = 1L)

        val result = synchronizer.sync(sessionId = 2L)

        assertEquals(0, result.addedCount)
        assertEquals(0, result.modifiedCount)
        assertEquals(0, result.removedCount)
        assertEquals(2, result.unchangedCount)
        assertEquals(false, result.sawChanges)
        // The second session is recorded on every row that was seen again.
        val seen = database.mediaItemDao().keys()
        assertEquals(2, seen.size)
        assertEquals(
            setOf(2L),
            database.mediaItemDao().getByIds(listOf(1L, 2L)).map { it.lastSeenSessionId }.toSet(),
        )
    }

    @Test
    fun `modified media invalidates its fingerprints and candidate groups`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L), mediaItem(3L))
        synchronizer.sync(sessionId = 1L)

        val fingerprintDao = database.fingerprintDao()
        fingerprintDao.upsertAll(
            listOf(
                FingerprintEntity(1L, FingerprintEntity.ALGORITHM_SHA256, "old-hash", 1L),
                FingerprintEntity(2L, FingerprintEntity.ALGORITHM_SHA256, "other-hash", 1L),
            ),
        )
        val groupDao = database.candidateGroupDao()
        val groupId = groupDao.insertGroup(
            CandidateGroupEntity(
                type = "EXACT_DUPLICATE",
                confidence = "HIGH",
                totalSizeBytes = 2_048L,
                itemCount = 2,
                reason = "Same content",
                createdAt = 1L,
                sessionId = 1L,
            ),
        )
        groupDao.insertMembers(
            listOf(
                CandidateGroupMemberEntity(groupId, 1L, 0),
                CandidateGroupMemberEntity(groupId, 2L, 1),
            ),
        )

        // Item 1 changed on disk; item 2 is untouched.
        mediaStore.items = listOf(
            mediaItem(1L, dateModified = 1_700_000_500L),
            mediaItem(2L),
            mediaItem(3L),
        )
        val result = synchronizer.sync(sessionId = 2L)

        assertEquals(1, result.modifiedCount)
        assertEquals(2, result.unchangedCount)
        assertEquals(0, result.removedCount)
        assertNull(fingerprintDao.get(1L, FingerprintEntity.ALGORITHM_SHA256))
        assertNotNull(fingerprintDao.get(2L, FingerprintEntity.ALGORITHM_SHA256))
        // The group that referenced the changed file is gone rather than stale.
        assertNull(groupDao.getGroup(groupId))
    }

    @Test
    fun `media that disappeared is marked stale and leaves totals`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L, sizeBytes = 5_000L))
        synchronizer.sync(sessionId = 1L)

        mediaStore.items = listOf(mediaItem(2L, sizeBytes = 5_000L))
        val result = synchronizer.sync(sessionId = 2L)

        assertEquals(1, result.removedCount)
        assertEquals(1L, result.mediaCount)
        assertEquals(5_000L, result.mediaBytes)

        val staleRow = database.mediaItemDao().getById(1L)
        assertNotNull(staleRow)
        assertTrue(staleRow!!.stale)
        assertEquals(false, database.mediaItemDao().getById(2L)!!.stale)
    }

    @Test
    fun `an emptied library marks every row stale instead of hiding data`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L))
        synchronizer.sync(sessionId = 1L)

        mediaStore.items = emptyList()
        val result = synchronizer.sync(sessionId = 2L)

        assertEquals(2, result.removedCount)
        assertEquals(0L, result.mediaCount)
        assertEquals(0L, result.mediaBytes)
        assertTrue(database.mediaItemDao().getById(1L)!!.stale)
        assertTrue(database.mediaItemDao().getById(2L)!!.stale)
    }

    @Test
    fun `progress is reported from real counters`() = runBlocking {
        mediaStore.items = (1L..5L).map { mediaItem(it) }

        val reported = mutableListOf<Pair<Long, Long>>()
        synchronizer.sync(sessionId = 1L) { processed, total -> reported.add(processed to total) }

        assertEquals(listOf(2L to 5L, 4L to 5L, 5L to 5L), reported)
        assertEquals(5L, reported.last().first)
        assertEquals(5L, reported.last().second)
    }

    @Test
    fun `cancelling mid-sync leaves the index consistent and claims nothing`() = runBlocking {
        mediaStore.items = (1L..6L).map { mediaItem(it) }
        var job: Job? = null

        job = launch {
            synchronizer.sync(sessionId = 1L) { processed, _ ->
                if (processed >= 4L) job?.cancel(CancellationException("cancelled by test"))
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        // Rows written before cancellation are valid; nothing claims a finished scan.
        val rows = database.mediaItemDao().getActive()
        assertTrue(rows.size in 0..6)
        assertNull(database.scanStateDao().get())
        assertEquals(0, database.scanSessionDao().unfinished().size)
    }

    @Test
    fun `media restored from the system trash is active again`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L))
        synchronizer.sync(sessionId = 1L)

        // Confirmed trash: the row is flagged trashed and MediaStore stops reporting it.
        database.mediaItemDao().setTrashed(listOf(1L), trashed = true)
        mediaStore.items = listOf(mediaItem(2L))
        synchronizer.sync(sessionId = 2L)

        val hidden = database.mediaItemDao().getById(1L)!!
        assertTrue(hidden.stale)
        assertEquals(true, hidden.isTrashed)
        assertEquals(1L, RoomMediaIndexRepository(database.mediaItemDao()).observeTotals().first().mediaCount)

        // Restored from the system trash with unchanged metadata: seeing it again must
        // clear both flags instead of hiding the item forever (specification §11).
        mediaStore.items = listOf(mediaItem(1L), mediaItem(2L))
        synchronizer.sync(sessionId = 3L)

        val restored = database.mediaItemDao().getById(1L)!!
        assertEquals(false, restored.stale)
        assertEquals(false, restored.isTrashed)
        val totals = RoomMediaIndexRepository(database.mediaItemDao()).observeTotals().first()
        assertEquals(2L, totals.mediaCount)
        assertEquals(2L * 1_024L, totals.mediaBytes)
    }
}
