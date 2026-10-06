package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.MediaSweepDatabase
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanPhase
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.scanner.classification.CandidateClassifier
import com.noise.mediasweep.scanner.classification.ExactDuplicateAnalyzer
import com.noise.mediasweep.scanner.classification.NearDuplicateAnalyzer
import com.noise.mediasweep.scanner.hashing.Sha256
import com.noise.mediasweep.scanner.hashing.StreamHasher
import com.noise.mediasweep.testing.FakeMediaStoreDataSource
import com.noise.mediasweep.testing.FakeReducedImageDecoder
import com.noise.mediasweep.testing.mediaItem
import com.noise.mediasweep.testing.patternImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
class ScanPipelineTest {

    /** Identical content id ⇒ identical hash. */
    private class ContentHasher(private val content: Map<String, String>) : StreamHasher {
        override suspend fun sha256(contentUri: String): String? =
            Sha256.hex((content[contentUri] ?: contentUri).toByteArray())
    }

    private lateinit var database: MediaSweepDatabase
    private lateinit var mediaStore: FakeMediaStoreDataSource
    private lateinit var sessionStore: ScanSessionStore
    private lateinit var synchronizer: MediaIndexSynchronizer
    private var now = 1_700_000_000_000L

    private fun pipeline(
        hasher: StreamHasher,
        decoder: FakeReducedImageDecoder = FakeReducedImageDecoder(),
    ) = ScanPipeline(
        mediaStore = mediaStore,
        synchronizer = synchronizer,
        sessionStore = sessionStore,
        mediaItemDao = database.mediaItemDao(),
        fingerprintDao = database.fingerprintDao(),
        candidateGroupDao = database.candidateGroupDao(),
        exactDuplicateAnalyzer = ExactDuplicateAnalyzer(hasher),
        nearDuplicateAnalyzer = NearDuplicateAnalyzer(decoder),
        candidateClassifier = CandidateClassifier(),
        clock = { now },
    )

    @Before
    fun setUp() {
        database = MediaSweepDatabase.inMemory(RuntimeEnvironment.getApplication())
            .allowMainThreadQueries()
            .build()
        mediaStore = FakeMediaStoreDataSource()
        sessionStore = ScanSessionStore(database.scanSessionDao(), database.scanStateDao()) { now }
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
    fun `a full scan indexes the library, finds duplicates and records completion`() = runBlocking {
        mediaStore.items = listOf(
            mediaItem(1L, sizeBytes = 1_000L),
            mediaItem(2L, sizeBytes = 1_000L),
            mediaItem(3L, sizeBytes = 1_000L),
        )
        val hasher = ContentHasher(
            mapOf(
                "content://media/1" to "same",
                "content://media/2" to "same",
                "content://media/3" to "other",
            ),
        )

        val outcome = pipeline(hasher).run(partialAccess = false)

        assertEquals(ScanStatus.COMPLETE, outcome.status)
        assertEquals(3L, outcome.mediaCount)
        assertEquals(3_000L, outcome.mediaBytes)
        assertEquals(mapOf(CandidateType.EXACT_DUPLICATE to 1), outcome.groupCounts)

        val state = database.scanStateDao().get()
        assertNotNull(state)
        assertEquals("COMPLETE", state!!.status)

        val group = database.candidateGroupDao().getGroups(CandidateType.EXACT_DUPLICATE.name).single()
        assertEquals(2, group.itemCount)
        assertEquals(2_000L, group.totalSizeBytes)
        assertEquals("HIGH", group.confidence)
        assertTrue(group.reason.isNotBlank())

        // Fingerprints are persisted for every hashed item.
        assertEquals(
            3,
            database.fingerprintDao().getAll(com.noise.mediasweep.core.database.entity.FingerprintEntity.ALGORITHM_SHA256).size,
        )
    }

    @Test
    fun `progress moves through real phases with real counters`() = runBlocking {
        mediaStore.items = (1L..5L).map { mediaItem(it, sizeBytes = 100L) }
        val seen = mutableListOf<ScanProgress>()

        pipeline(ContentHasher(emptyMap())).run(partialAccess = false) { seen.add(it) }

        assertTrue(seen.first().phase == ScanPhase.INDEXING)
        assertTrue(seen.any { it.phase == ScanPhase.FINGERPRINTING && it.processedCount == it.totalCount })
        assertTrue(seen.last().foundCounts.isNotEmpty() || seen.last().phase == ScanPhase.CLASSIFYING)
        // No progress value ever exceeds its own total.
        assertTrue(seen.filter { it.totalCount > 0 }.all { it.processedCount <= it.totalCount })
        // Progress is monotonic within the indexing phase.
        val indexing = seen.filter { it.phase == ScanPhase.INDEXING && it.totalCount > 0 }
        assertEquals(indexing.map { it.processedCount }.sorted(), indexing.map { it.processedCount })
    }

    @Test
    fun `partial access is recorded as PARTIAL, never as a complete scan`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L))

        val outcome = pipeline(ContentHasher(emptyMap())).run(partialAccess = true)

        assertEquals(ScanStatus.PARTIAL, outcome.status)
        assertEquals("PARTIAL", database.scanStateDao().get()!!.status)
        assertTrue(database.scanStateDao().get()!!.partialAccess)
    }

    @Test
    fun `an empty library completes with zero media and zero candidates`() = runBlocking {
        mediaStore.items = emptyList()

        val outcome = pipeline(ContentHasher(emptyMap())).run(partialAccess = false)

        assertEquals(ScanStatus.COMPLETE, outcome.status)
        assertEquals(0L, outcome.mediaCount)
        assertTrue(outcome.groupCounts.isEmpty())
        assertTrue(database.candidateGroupDao().typeTotals().isEmpty())
    }

    @Test
    fun `re-running the scan replaces previous duplicate results instead of accumulating them`() = runBlocking {
        mediaStore.items = listOf(mediaItem(1L, sizeBytes = 10L), mediaItem(2L, sizeBytes = 10L))
        pipeline(ContentHasher(mapOf("content://media/1" to "x", "content://media/2" to "x")))
            .run(partialAccess = false)

        mediaStore.items = emptyList()
        pipeline(ContentHasher(emptyMap())).run(partialAccess = false)

        assertTrue(database.candidateGroupDao().getGroups(CandidateType.EXACT_DUPLICATE.name).isEmpty())
        assertEquals(0L, database.mediaItemDao().totals().count)
    }

    @Test
    fun `metadata classifiers persist screenshot, large and old candidates`() = runBlocking {
        val nowSeconds = now / 1000
        val oldDate = nowSeconds - 180L * 24 * 60 * 60 - 60
        mediaStore.items = listOf(
            mediaItem(1L, sizeBytes = 1_000L, dateModified = oldDate), // old media
            mediaItem(2L, sizeBytes = 600L * 1024 * 1024, mediaType = com.noise.mediasweep.domain.model.MediaType.VIDEO), // large video
            mediaItem(
                3L,
                sizeBytes = 2_000L,
                displayName = "Screenshot_20240101-120000.png",
                mimeType = "image/png",
                relativePath = "Pictures/Screenshots",
                bucketDisplayName = "Screenshots",
            ),
            mediaItem(4L, sizeBytes = 3_000L), // plain photo: no category
        )

        val outcome = pipeline(ContentHasher(emptyMap())).run(partialAccess = false)

        assertEquals(ScanStatus.COMPLETE, outcome.status)
        assertEquals(
            mapOf(
                CandidateType.LARGE_FILE to 1,
                CandidateType.OLD_MEDIA to 1,
                CandidateType.SCREENSHOT to 1,
            ),
            outcome.groupCounts,
        )

        val oldGroup = database.candidateGroupDao().getGroups(CandidateType.OLD_MEDIA.name).single()
        assertEquals(1, oldGroup.itemCount)
        assertTrue(oldGroup.reason.contains("Older than 180 days"))
        assertTrue(!oldGroup.reason.contains("unused"))

        // Re-running the scan replaces metadata candidates instead of accumulating them.
        pipeline(ContentHasher(emptyMap())).run(partialAccess = false)
        assertEquals(1, database.candidateGroupDao().getGroups(CandidateType.OLD_MEDIA.name).size)
    }

    @Test
    fun `a second scan reuses fingerprints instead of re-reading unchanged media`() = runBlocking {
        val decoder = FakeReducedImageDecoder(
            mapOf(
                "content://media/1" to patternImage(seed = 3L),
                "content://media/2" to patternImage(seed = 3L),
            ),
        )
        mediaStore.items = listOf(
            mediaItem(1L, sizeBytes = 1_000L),
            mediaItem(2L, sizeBytes = 1_000L),
        )
        val reads = mutableListOf<String>()
        val hasher = object : StreamHasher {
            override suspend fun sha256(contentUri: String): String? {
                reads.add(contentUri)
                // Distinct content per item: these are near duplicates, not exact ones.
                return Sha256.hex(contentUri.toByteArray())
            }
        }

        val first = pipeline(hasher, decoder).run(partialAccess = false)

        assertEquals(ScanStatus.COMPLETE, first.status)
        assertEquals(2, reads.size)
        assertEquals(2, decoder.decodeCount)
        assertEquals(mapOf(CandidateType.NEAR_DUPLICATE to 1), first.groupCounts)

        reads.clear()
        val second = pipeline(hasher, decoder).run(partialAccess = false)

        // Unchanged media is grouped from the stored fingerprints (§20): nothing is
        // re-read and no image is decoded again, yet the result is identical.
        assertEquals(emptyList<String>(), reads)
        assertEquals(2, decoder.decodeCount)
        assertEquals(mapOf(CandidateType.NEAR_DUPLICATE to 1), second.groupCounts)
        assertEquals(2L, second.mediaCount)
        assertEquals(2_000L, second.mediaBytes)
        // Existing fingerprints were reused, not rewritten or dropped.
        assertEquals(
            2,
            database.fingerprintDao()
                .getAll(com.noise.mediasweep.core.database.entity.FingerprintEntity.ALGORITHM_SHA256)
                .size,
        )
        assertEquals(
            2,
            database.fingerprintDao()
                .getAll(com.noise.mediasweep.core.database.entity.FingerprintEntity.ALGORITHM_PERCEPTUAL_HASH)
                .size,
        )
    }

    @Test
    fun `cancelling a scan never records it as complete`() = runBlocking {
        mediaStore.items = (1L..10L).map { mediaItem(it, sizeBytes = 5L) }
        var job: Job? = null

        job = launch {
            pipeline(ContentHasher(emptyMap())).run(partialAccess = false) { progress ->
                if (progress.phase == ScanPhase.INDEXING && progress.processedCount >= 4L) {
                    job?.cancel(CancellationException("cancelled by test"))
                }
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        // The interrupted run is recovered into an honest state (never COMPLETE), and the
        // published state never keeps claiming that a scan is running (§19, §45).
        val scanState = database.scanStateDao().get()
        assertNotNull(scanState)
        assertEquals(ScanStatus.NOT_SCANNED, ScanStatus.valueOf(scanState!!.status))
        val session = database.scanSessionDao().latest()
        assertNotNull(session)
        assertTrue(session!!.status != ScanStatus.COMPLETE.name)
    }
}
