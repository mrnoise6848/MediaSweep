package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.MediaSweepDatabase
import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.MediaItemEntity
import com.noise.mediasweep.core.database.entity.ScanStateEntity
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanStatus
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
class RoomRepositoriesTest {

    private lateinit var database: MediaSweepDatabase
    private lateinit var mediaIndexRepository: RoomMediaIndexRepository
    private lateinit var scanStateRepository: RoomScanStateRepository
    private lateinit var candidateRepository: RoomCandidateRepository

    @Before
    fun setUp() {
        database = MediaSweepDatabase.inMemory(RuntimeEnvironment.getApplication())
            .allowMainThreadQueries()
            .build()
        mediaIndexRepository = RoomMediaIndexRepository(database.mediaItemDao())
        scanStateRepository = RoomScanStateRepository(database.scanStateDao(), database.scanSessionDao())
        candidateRepository = RoomCandidateRepository(database.candidateGroupDao())
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

    @Test
    fun `media index reports totals for active media only`() = runBlocking {
        assertEquals(0L, mediaIndexRepository.totals().mediaCount)

        val dao = database.mediaItemDao()
        dao.upsertAll(listOf(media(1L, 100L), media(2L, 200L)))
        assertEquals(2L, mediaIndexRepository.totals().mediaCount)
        assertEquals(300L, mediaIndexRepository.totals().mediaBytes)

        dao.reconcile(listOf(2L))
        assertEquals(1L, mediaIndexRepository.observeTotals().first().mediaCount)
        assertEquals(200L, mediaIndexRepository.observeTotals().first().mediaBytes)
    }

    @Test
    fun `scan status defaults to NOT_SCANNED and then reflects persisted state`() = runBlocking {
        assertEquals(ScanStatus.NOT_SCANNED, scanStateRepository.status())
        assertNull(scanStateRepository.observeLastSession().first())

        database.scanStateDao().upsert(
            ScanStateEntity(
                status = "PARTIAL",
                lastSuccessfulScanAt = 1L,
                lastSessionId = 1L,
                lastMediaCount = 5L,
                lastMediaBytes = 500L,
                partialAccess = true,
                updatedAt = 1L,
            ),
        )

        assertEquals(ScanStatus.PARTIAL, scanStateRepository.status())
        assertEquals(ScanStatus.PARTIAL, scanStateRepository.observeStatus().first())
    }

    @Test
    fun `candidate summaries aggregate groups with their member counts`() = runBlocking {
        val mediaDao = database.mediaItemDao()
        val groupDao = database.candidateGroupDao()
        mediaDao.upsertAll(listOf(media(1L, 100L), media(2L, 100L), media(3L, 900L)))

        val duplicatesId = groupDao.insertGroup(
            CandidateGroupEntity(
                type = "EXACT_DUPLICATE",
                confidence = "HIGH",
                totalSizeBytes = 200L,
                itemCount = 2,
                reason = "Same file content",
                createdAt = 1L,
                sessionId = null,
            ),
        )
        groupDao.insertMembers(
            listOf(
                CandidateGroupMemberEntity(duplicatesId, 1L, 0),
                CandidateGroupMemberEntity(duplicatesId, 2L, 1),
            ),
        )
        groupDao.insertGroup(
            CandidateGroupEntity(
                type = "LARGE_FILE",
                confidence = "MEDIUM",
                totalSizeBytes = 900L,
                itemCount = 1,
                reason = "Large video",
                createdAt = 1L,
                sessionId = null,
            ),
        )

        val summaries = candidateRepository.observeCategorySummaries().first()
        val duplicates = summaries.first { it.type == CandidateType.EXACT_DUPLICATE }
        assertEquals(1, duplicates.groupCount)
        assertEquals(2, duplicates.itemCount)
        assertEquals(200L, duplicates.totalSizeBytes)

        val largeFiles = summaries.first { it.type == CandidateType.LARGE_FILE }
        assertEquals(1, largeFiles.groupCount)
        assertEquals(900L, largeFiles.totalSizeBytes)
    }

    @Test
    fun `candidate group detail exposes its media ordered by position`() = runBlocking {
        val mediaDao = database.mediaItemDao()
        val groupDao = database.candidateGroupDao()
        mediaDao.upsertAll(listOf(media(1L, 100L), media(2L, 100L), media(3L, 100L)))

        val groupId = groupDao.insertGroup(
            CandidateGroupEntity(
                type = "NEAR_DUPLICATE",
                confidence = "MEDIUM",
                totalSizeBytes = 300L,
                itemCount = 3,
                reason = "Similar images",
                createdAt = 1L,
                sessionId = null,
            ),
        )
        groupDao.insertMembers(
            listOf(
                CandidateGroupMemberEntity(groupId, 3L, 0),
                CandidateGroupMemberEntity(groupId, 1L, 1),
                CandidateGroupMemberEntity(groupId, 2L, 2),
            ),
        )

        val group = candidateRepository.group(groupId)
        assertNotNull(group)
        assertEquals(listOf(3L, 1L, 2L), group!!.items.map { it.id })
        assertEquals(CandidateType.NEAR_DUPLICATE, group.group.type)
        assertTrue(group.group.reason.isNotBlank())
        assertNotNull(candidateRepository.observeGroup(groupId).first())
    }
}
