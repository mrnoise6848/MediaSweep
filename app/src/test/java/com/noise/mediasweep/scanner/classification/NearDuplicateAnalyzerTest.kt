package com.noise.mediasweep.scanner.classification

import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaType
import com.noise.mediasweep.domain.model.ScanThresholds
import com.noise.mediasweep.scanner.image.DctImageHasher
import com.noise.mediasweep.scanner.image.ReducedImageDecoder
import com.noise.mediasweep.testing.FakeReducedImageDecoder
import com.noise.mediasweep.testing.mediaItem
import com.noise.mediasweep.testing.patternImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearDuplicateAnalyzerTest {

    private fun analyzer(
        decoder: ReducedImageDecoder,
        thresholds: ScanThresholds = ScanThresholds(),
    ) = NearDuplicateAnalyzer(decoder = decoder, thresholds = thresholds)

    @Test
    fun `two visually similar images form one high-confidence group`() = runBlocking {
        val similar = patternImage(seed = 7L, size = 64)
        val unrelated = patternImage(seed = 99L, size = 64)
        val decoder = FakeReducedImageDecoder(
            mapOf(
                "content://media/1" to similar,
                "content://media/2" to similar,
                "content://media/3" to unrelated,
            ),
        )
        val items = listOf(mediaItem(1L, sizeBytes = 100L), mediaItem(2L, sizeBytes = 200L), mediaItem(3L, sizeBytes = 300L))

        val result = analyzer(decoder).analyze(items)

        assertEquals(1, result.groups.size)
        val group = result.groups.single()
        assertEquals(CandidateType.NEAR_DUPLICATE, group.type)
        assertEquals(Confidence.HIGH, group.confidence)
        assertEquals(setOf(1L, 2L), group.items.map { it.id }.toSet())
        assertEquals(300L, group.totalSizeBytes)
        assertTrue(group.reason.contains("visually similar"))
        assertEquals(3, result.hashedCount)
        assertEquals(0, result.skippedCount)
        assertEquals(3, result.hashes.size)
    }

    @Test
    fun `undecodable images are skipped without failing the scan`() = runBlocking {
        val decoder = FakeReducedImageDecoder(
            mapOf("content://media/1" to patternImage(seed = 5L), "content://media/2" to patternImage(seed = 5L)),
        )
        // Item 3 has no decodable content.
        val items = listOf(mediaItem(1L), mediaItem(2L), mediaItem(3L))

        val result = analyzer(decoder).analyze(items)

        assertEquals(1, result.skippedCount)
        assertEquals(2, result.hashedCount)
        assertEquals(1, result.groups.size)
        assertEquals(2, result.groups.single().items.size)
    }

    @Test
    fun `videos are never decoded or perceptually hashed`() = runBlocking {
        val decoder = FakeReducedImageDecoder(
            mapOf("content://media/1" to patternImage(seed = 5L), "content://media/2" to patternImage(seed = 5L)),
        )
        val items = listOf(
            mediaItem(1L),
            mediaItem(2L),
            mediaItem(3L, mediaType = MediaType.VIDEO, displayName = "clip.mp4"),
        )

        val result = analyzer(decoder).analyze(items)

        assertEquals(2, decoder.decodeCount)
        assertEquals(2, result.hashes.size)
        assertTrue(result.groups.single().items.none { it.isVideo })
    }

    @Test
    fun `progress is reported for every decoded image`() = runBlocking {
        val decoder = FakeReducedImageDecoder(
            (1L..4L).associate { "content://media/$it" to patternImage(seed = it) },
        )
        val seen = mutableListOf<Pair<Long, Long>>()

        analyzer(decoder).analyze((1L..4L).map { mediaItem(it) }) { processed, total ->
            seen.add(processed to total)
        }

        assertEquals(4, seen.size)
        assertEquals(1L to 4L, seen.first())
        assertEquals(4L to 4L, seen.last())
        assertTrue(seen.map { it.first } == seen.map { it.first }.sorted())
    }

    @Test
    fun `an empty library produces no groups and no work`() = runBlocking {
        val decoder = FakeReducedImageDecoder()

        val result = analyzer(decoder).analyze(emptyList())

        assertTrue(result.groups.isEmpty())
        assertEquals(0, decoder.decodeCount)
    }

    @Test
    fun `pairs above the strong threshold are only surfaced as probably similar`() {
        // Distance of exactly 7 bits: within the candidate radius (10) but above the
        // strong threshold (6), so it must be MEDIUM, not HIGH.
        val items = listOf(mediaItem(1L, sizeBytes = 10L), mediaItem(2L, sizeBytes = 20L))
        val hashes = listOf(
            PerceptualHashResult(mediaId = 1L, hash = 0UL),
            PerceptualHashResult(mediaId = 2L, hash = 0b1111111UL),
        )

        val groups = analyzer(FakeReducedImageDecoder()).group(items, hashes)

        assertEquals(1, groups.size)
        assertEquals(Confidence.MEDIUM, groups.single().confidence)
        assertTrue(groups.single().reason.contains("hash distance up to 7"))
    }

    @Test
    fun `pairs at or below the strong threshold keep high confidence`() {
        val items = listOf(mediaItem(1L), mediaItem(2L), mediaItem(3L))
        val hashes = listOf(
            PerceptualHashResult(mediaId = 1L, hash = 0UL),
            PerceptualHashResult(mediaId = 2L, hash = 0b1111UL),
            PerceptualHashResult(mediaId = 3L, hash = 0xFFFFFFFFFFFFFFFFUL),
        )

        val groups = analyzer(FakeReducedImageDecoder()).group(items, hashes)

        // 1..2 are within 4 bits (strong); 3 is far away and must not be grouped.
        assertEquals(1, groups.size)
        assertEquals(Confidence.HIGH, groups.single().confidence)
        assertEquals(listOf(1L, 2L), groups.single().items.map { it.id })
    }

    @Test
    fun `thresholds are configurable instead of hardcoded`() = runBlocking {
        val decoder = FakeReducedImageDecoder(
            mapOf("content://media/1" to patternImage(3L), "content://media/2" to patternImage(3L)),
        )
        val lenient = ScanThresholds(nearDuplicateMaxHamming = 0, nearDuplicatePossibleHamming = 0)
        val items = listOf(mediaItem(1L), mediaItem(2L))

        // Identical images hash to distance 0, so even the strictest thresholds keep them.
        val strict = analyzer(decoder, lenient).analyze(items)
        assertEquals(1, strict.groups.size)
        assertEquals(Confidence.HIGH, strict.groups.single().confidence)
    }

    @Test
    fun `stored perceptual hashes skip decoding unchanged images`() = runBlocking {
        val pattern = patternImage(seed = 7L, size = 64)
        val decoder = FakeReducedImageDecoder(
            mapOf("content://media/3" to pattern),
        )
        // Items 1 and 2 were hashed in an earlier scan; only 3 must be decoded now.
        val known = DctImageHasher().hash(pattern).pHash.toString(16).padStart(16, '0')
        val items = listOf(mediaItem(1L), mediaItem(2L), mediaItem(3L))

        val result = analyzer(decoder).analyze(items, existingHashes = mapOf(1L to known, 2L to known))

        assertEquals(1, decoder.decodeCount)
        assertEquals(2, result.reusedCount)
        assertEquals(3, result.hashedCount)
        assertEquals(3, result.hashes.size)
        // The reused pair still clusters with the newly decoded copy.
        assertEquals(1, result.groups.size)
        assertEquals(
            setOf(1L, 2L, 3L),
            result.groups.single().items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `an unparsable stored hash falls back to decoding`() = runBlocking {
        val decoder = FakeReducedImageDecoder(
            mapOf("content://media/1" to patternImage(seed = 2L, size = 64)),
        )
        val items = listOf(mediaItem(1L))

        val result = analyzer(decoder).analyze(items, existingHashes = mapOf(1L to "not-a-hash"))

        assertEquals(1, decoder.decodeCount)
        assertEquals(0, result.reusedCount)
        assertEquals(1, result.hashedCount)
    }
}
