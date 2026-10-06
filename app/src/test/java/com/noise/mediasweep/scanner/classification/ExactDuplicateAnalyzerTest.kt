package com.noise.mediasweep.scanner.classification

import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaType
import com.noise.mediasweep.scanner.hashing.Sha256
import com.noise.mediasweep.scanner.hashing.StreamHasher
import com.noise.mediasweep.testing.mediaItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactDuplicateAnalyzerTest {

    /** Content-addressed fake: identical content id ⇒ identical hash, unreadable ⇒ null. */
    private class FakeHasher(
        private val contentIdByUri: Map<String, String>,
        private val unreadable: Set<String> = emptySet(),
    ) : StreamHasher {
        override suspend fun sha256(contentUri: String): String? = when {
            contentUri in unreadable -> null
            else -> Sha256.hex((contentIdByUri[contentUri] ?: contentUri).toByteArray())
        }
    }

    private fun hasherFor(vararg contentIds: Pair<Long, String>) =
        FakeHasher(contentIds.associate { (id, content) -> "content://media/$id" to content })

    @Test
    fun `three identical files become one HIGH confidence group`() = runBlocking {
        val items = listOf(
            mediaItem(1L, sizeBytes = 1_800_000L),
            mediaItem(2L, sizeBytes = 1_800_000L),
            mediaItem(3L, sizeBytes = 1_800_000L),
        )
        val analyzer = ExactDuplicateAnalyzer(
            hasherFor(1L to "same-bytes", 2L to "same-bytes", 3L to "same-bytes"),
        )

        val result = analyzer.analyze(items)

        assertEquals(1, result.groups.size)
        val group = result.groups.first()
        assertEquals(CandidateType.EXACT_DUPLICATE, group.type)
        assertEquals(Confidence.HIGH, group.confidence)
        assertEquals(3, group.items.size)
        assertEquals(3L * 1_800_000L, group.totalSizeBytes)
        assertTrue(group.reason.contains("identical"))
        assertEquals(3, result.hashes.size)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `same metadata but different bytes is not an exact duplicate`() = runBlocking {
        val items = listOf(
            mediaItem(1L, sizeBytes = 2_048L),
            mediaItem(2L, sizeBytes = 2_048L),
        )
        val analyzer = ExactDuplicateAnalyzer(hasherFor(1L to "bytes-a", 2L to "bytes-b"))

        val result = analyzer.analyze(items)

        assertTrue(result.groups.isEmpty())
        assertEquals(2, result.hashes.size)
    }

    @Test
    fun `two groups of identical files are reported separately`() = runBlocking {
        val items = listOf(
            mediaItem(1L, sizeBytes = 100L),
            mediaItem(2L, sizeBytes = 100L),
            mediaItem(3L, sizeBytes = 100L),
            mediaItem(4L, sizeBytes = 100L),
            mediaItem(5L, sizeBytes = 100L),
        )
        val analyzer = ExactDuplicateAnalyzer(
            hasherFor(
                1L to "pair-a", 2L to "pair-a",
                3L to "pair-b", 4L to "pair-b",
                5L to "lonely",
            ),
        )

        val result = analyzer.analyze(items)

        assertEquals(2, result.groups.size)
        assertTrue(result.groups.all { it.items.size == 2 })
    }

    @Test
    fun `an unreadable file is skipped without failing the scan`() = runBlocking {
        val items = listOf(
            mediaItem(1L, sizeBytes = 42L),
            mediaItem(2L, sizeBytes = 42L),
        )
        val hasher = FakeHasher(contentIdByUri = mapOf("content://media/2" to "x"), unreadable = setOf("content://media/1"))

        val result = ExactDuplicateAnalyzer(hasher).analyze(items)

        assertEquals(1, result.skippedCount)
        assertTrue(result.groups.isEmpty())
        assertEquals(1, result.hashes.size)
    }

    @Test
    fun `singletons are never hashed and progress counts only real work`() = runBlocking {
        val items = listOf(mediaItem(1L, sizeBytes = 10L), mediaItem(2L, sizeBytes = 20L))
        var calls = 0
        val analyzer = ExactDuplicateAnalyzer(FakeHasher(emptyMap()))

        val result = analyzer.analyze(items) { hashed, candidates ->
            calls++
            assertEquals(0L, candidates)
            assertEquals(hashed, 0L)
        }

        assertTrue(result.groups.isEmpty())
        assertEquals(0, result.hashedCount)
        assertEquals(0, calls)
    }

    @Test
    fun `progress reports hashed against candidate totals`() = runBlocking {
        val items = (1L..4L).map { mediaItem(it, sizeBytes = 500L) }
        val analyzer = ExactDuplicateAnalyzer(
            hasherFor(1L to "a", 2L to "a", 3L to "b", 4L to "b"),
        )

        val progress = mutableListOf<Pair<Long, Long>>()
        val result = analyzer.analyze(items) { hashed, candidates -> progress.add(hashed to candidates) }

        assertEquals(4, result.hashedCount)
        assertEquals(listOf(1L to 4L, 2L to 4L, 3L to 4L, 4L to 4L), progress)
    }

    @Test
    fun `videos are compared by content, not by frame sampling`() = runBlocking {
        val items = listOf(
            mediaItem(1L, sizeBytes = 9_000L, mediaType = MediaType.VIDEO),
            mediaItem(2L, sizeBytes = 9_000L, mediaType = MediaType.VIDEO),
        )
        val analyzer = ExactDuplicateAnalyzer(hasherFor(1L to "video-bytes", 2L to "video-bytes"))

        val result = analyzer.analyze(items)

        assertEquals(1, result.groups.size)
        assertEquals(CandidateType.EXACT_DUPLICATE, result.groups.first().type)
    }

    @Test
    fun `stored fingerprints of unchanged media are reused instead of re-hashing`() = runBlocking {
        val items = listOf(
            mediaItem(1L, sizeBytes = 1_800_000L),
            mediaItem(2L, sizeBytes = 1_800_000L),
            mediaItem(3L, sizeBytes = 1_800_000L),
        )
        val readUris = mutableListOf<String>()
        val hasher = object : StreamHasher {
            override suspend fun sha256(contentUri: String): String? {
                readUris.add(contentUri)
                return Sha256.hex(contentUri.toByteArray())
            }
        }
        val known = Sha256.hex("same-bytes".toByteArray())
        val stored = mapOf(1L to known, 2L to known)

        val result = ExactDuplicateAnalyzer(hasher).analyze(items, stored)

        // Only item 3 is read from storage: 1 and 2 come from the earlier scan.
        assertEquals(listOf("content://media/3"), readUris)
        assertEquals(2, result.reusedCount)
        assertEquals(3, result.hashedCount)
        assertEquals(3, result.hashes.size)
        // Reused hashes still group exactly like freshly computed ones.
        assertEquals(1, result.groups.size)
        assertEquals(listOf(1L, 2L), result.groups.single().items.map { it.id }.sorted())
    }

    @Test
    fun `without stored fingerprints everything is hashed as usual`() = runBlocking {
        val items = listOf(mediaItem(1L, sizeBytes = 2_048L), mediaItem(2L, sizeBytes = 2_048L))
        val analyzer = ExactDuplicateAnalyzer(hasherFor(1L to "bytes-a", 2L to "bytes-b"))

        val result = analyzer.analyze(items, existingHashes = emptyMap())

        assertEquals(0, result.reusedCount)
        assertEquals(2, result.hashedCount)
    }
}
