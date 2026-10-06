package com.noise.mediasweep.core.database

import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.FingerprintEntity
import com.noise.mediasweep.core.database.entity.MediaItemEntity
import com.noise.mediasweep.core.database.entity.ScanSessionEntity
import com.noise.mediasweep.core.database.entity.ScanStateEntity
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaSweepDatabaseTest {

    private lateinit var database: MediaSweepDatabase

    @Before
    fun setUp() {
        database = MediaSweepDatabase.inMemory(RuntimeEnvironment.getApplication())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun media(
        id: Long,
        size: Long = 1_000L,
        displayName: String = "IMG_$id.jpg",
        mimeType: String? = "image/jpeg",
        mediaType: Int = 1,
        dateModified: Long = 1_700_000_000L,
    ) = MediaItemEntity(
        id = id,
        contentUri = "content://media/external/images/media/$id",
        mediaType = mediaType,
        displayName = displayName,
        mimeType = mimeType,
        sizeBytes = size,
        width = 100,
        height = 100,
        durationMs = null,
        dateAdded = dateModified,
        dateModified = dateModified,
        relativePath = "DCIM/",
        bucketId = "bucket",
        bucketDisplayName = "Camera",
        isFavorite = false,
        isPending = false,
        isTrashed = false,
    )

    @Test
    fun `inserting and reading media keeps totals consistent`() = runBlocking {
        val dao = database.mediaItemDao()
        dao.upsertAll(listOf(media(1L, size = 100L), media(2L, size = 250L)))

        val totals = dao.totals()
        assertEquals(2L, totals.count)
        assertEquals(350L, totals.bytes)

        assertEquals("IMG_1.jpg", dao.getById(1L)?.displayName)
        assertNull(dao.getById(99L))
    }

    @Test
    fun `reconciliation marks disappeared media as stale instead of hiding it silently`() = runBlocking {
        val dao = database.mediaItemDao()
        dao.upsertAll(listOf(media(1L), media(2L), media(3L)))

        dao.reconcile(listOf(1L, 3L))

        val active = dao.getActive()
        assertEquals(setOf(1L, 3L), active.map { it.id }.toSet())
        val stale = dao.getById(2L)
        assertNotNull(stale)
        assertTrue(stale!!.stale)
        // Stale rows never count towards storage totals.
        assertEquals(2L, dao.totals().count)
    }

    @Test
    fun `reconciling against an empty MediaStore marks everything stale`() = runBlocking {
        val dao = database.mediaItemDao()
        dao.upsertAll(listOf(media(1L), media(2L)))
        dao.reconcile(emptyList())

        assertEquals(0L, dao.totals().count)
        assertTrue(dao.getById(1L)!!.stale)
    }

    @Test
    fun `fingerprints are stored per algorithm and removed with their media`() = runBlocking {
        val mediaDao = database.mediaItemDao()
        val fingerprintDao = database.fingerprintDao()
        mediaDao.upsertAll(listOf(media(1L), media(2L)))
        fingerprintDao.upsertAll(
            listOf(
                FingerprintEntity(1L, FingerprintEntity.ALGORITHM_SHA256, "abc", 1L),
                FingerprintEntity(2L, FingerprintEntity.ALGORITHM_SHA256, "def", 1L),
            ),
        )

        assertEquals("abc", fingerprintDao.get(1L, FingerprintEntity.ALGORITHM_SHA256)?.value)
        assertEquals(2, fingerprintDao.getAll(FingerprintEntity.ALGORITHM_SHA256).size)

        // Foreign key cascade: deleting the index row drops its fingerprints.
        mediaDao.deleteByIds(listOf(1L))
        assertNull(fingerprintDao.get(1L, FingerprintEntity.ALGORITHM_SHA256))
        assertNotNull(fingerprintDao.get(2L, FingerprintEntity.ALGORITHM_SHA256))
    }

    @Test
    fun `candidate groups carry members and cascade when the group is removed`() = runBlocking {
        val mediaDao = database.mediaItemDao()
        val groupDao = database.candidateGroupDao()
        mediaDao.upsertAll(listOf(media(1L, size = 100L), media(2L, size = 100L)))

        val groupId = groupDao.insertGroup(
            CandidateGroupEntity(
                type = "EXACT_DUPLICATE",
                confidence = "HIGH",
                totalSizeBytes = 200L,
                itemCount = 2,
                reason = "Same file content and size",
                createdAt = 1L,
                sessionId = null,
            ),
        )
        groupDao.insertMembers(
            listOf(
                CandidateGroupMemberEntity(groupId, 1L, 0),
                CandidateGroupMemberEntity(groupId, 2L, 1),
            ),
        )

        val relation = groupDao.getGroupWithMedia(groupId)
        assertNotNull(relation)
        assertEquals(listOf(1L, 2L), relation!!.members.map { it.media.id })
        assertEquals(2, relation.members.size)

        val totals = groupDao.typeTotals().first()
        assertEquals("EXACT_DUPLICATE", totals.type)
        assertEquals(2L, totals.itemCount)
        assertEquals(200L, totals.bytes)

        groupDao.deleteAll()
        assertEquals(0, groupDao.typeTotals().size)
        // Members follow the group, media index stays intact.
        assertEquals(2L, mediaDao.totals().count)
    }

    @Test
    fun `scan state behaves like a single row and restores after process death`() = runBlocking {
        val stateDao = database.scanStateDao()
        assertNull(stateDao.get())

        stateDao.upsert(
            ScanStateEntity(
                status = "COMPLETE",
                lastSuccessfulScanAt = 42L,
                lastSessionId = 7L,
                lastMediaCount = 10L,
                lastMediaBytes = 1_000L,
                partialAccess = false,
                updatedAt = 42L,
            ),
        )
        stateDao.upsert(
            ScanStateEntity(
                status = "PARTIAL",
                lastSuccessfulScanAt = 43L,
                lastSessionId = 8L,
                lastMediaCount = 4L,
                lastMediaBytes = 400L,
                partialAccess = true,
                updatedAt = 43L,
            ),
        )

        val stored = stateDao.get()
        assertNotNull(stored)
        assertEquals("PARTIAL", stored!!.status)
        assertTrue(stored.partialAccess)
        assertEquals("PARTIAL", stateDao.observe().first()?.status)
    }

    @Test
    fun `interrupted sessions are detectable so a scan can resume instead of lying about completion`() = runBlocking {
        val sessionDao = database.scanSessionDao()
        val running = sessionDao.insert(
            ScanSessionEntity(
                startedAt = 10L,
                finishedAt = null,
                status = "SCANNING",
                processedCount = 5L,
                totalCount = 10L,
                partialAccess = false,
                errorMessage = null,
            ),
        )
        sessionDao.insert(
            ScanSessionEntity(
                startedAt = 1L,
                finishedAt = 5L,
                status = "COMPLETE",
                processedCount = 10L,
                totalCount = 10L,
                partialAccess = false,
                errorMessage = null,
            ),
        )

        assertEquals(running, sessionDao.unfinished().first().id)
        assertEquals(running, sessionDao.latest()?.id)
        assertNotNull(sessionDao.getById(running))
        assertEquals(2, sessionDao.observeSessions().first().size)
    }
}
