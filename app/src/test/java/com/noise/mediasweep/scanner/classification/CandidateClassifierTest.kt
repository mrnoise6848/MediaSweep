package com.noise.mediasweep.scanner.classification

import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence
import com.noise.mediasweep.domain.model.MediaType
import com.noise.mediasweep.domain.model.ScanThresholds
import com.noise.mediasweep.testing.mediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateClassifierTest {

    private val thresholds = ScanThresholds()
    private val screenshot = ScreenshotClassifier(thresholds)
    private val largeFile = LargeFileClassifier(thresholds)
    private val oldMedia = OldMediaClassifier(thresholds)
    private val classifier = CandidateClassifier()

    private val nowSeconds = 1_700_000_000L
    private val recentDate = nowSeconds - 3 * 24 * 60 * 60
    private val oldDate = nowSeconds - thresholds.oldMediaAgeSeconds - 60

    // --- Screenshot (specification §42: multiple signals required) -----------------

    @Test
    fun `android screenshot naming with supporting signals classifies as high confidence`() {
        val item = mediaItem(
            id = 1L,
            displayName = "Screenshot_20240101-123456.png",
            mimeType = "image/png",
            width = 1080,
            height = 2400,
            relativePath = "Pictures/Screenshots",
            bucketDisplayName = "Screenshots",
            dateModified = recentDate,
        )

        val result = screenshot.classify(item)

        assertEquals(CandidateType.SCREENSHOT, result!!.type)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.reason.contains("4 screenshot signals"))
        assertTrue(result.reason.contains("screenshot filename pattern"))
        assertTrue(result.reason.contains("screenshot folder"))
    }

    @Test
    fun `a single strong signal classifies only with medium confidence`() {
        val item = mediaItem(
            id = 2L,
            displayName = "IMG_0001.jpg", // no filename signal, plain JPEG
            relativePath = "Pictures/Screenshots",
            bucketDisplayName = "Screenshots",
            dateModified = recentDate,
        )

        val result = screenshot.classify(item)

        assertEquals(Confidence.MEDIUM, result!!.confidence)
        assertTrue(result.reason.contains("1 screenshot signals"))
    }

    @Test
    fun `weak signals alone never classify an image as a screenshot`() {
        // PNG + screen-shaped aspect ratio, but no folder/filename signal.
        val item = mediaItem(
            id = 3L,
            displayName = "holiday.png",
            mimeType = "image/png",
            width = 1080,
            height = 2400,
            dateModified = recentDate,
        )

        assertNull(screenshot.classify(item))
    }

    @Test
    fun `videos are never screenshot candidates`() {
        val item = mediaItem(
            id = 4L,
            mediaType = MediaType.VIDEO,
            displayName = "Screen recording 2024-01-01.mp4",
            relativePath = "Movies",
            dateModified = recentDate,
        )

        assertNull(screenshot.classify(item))
    }

    @Test
    fun `screenshot filename patterns from different vendors match`() {
        for (name in listOf(
            "Screenshot (2).png",
            "Screen_Shot_2024-01-01_at_10.00.00.png",
            "screen shot 20240101.jpg",
            "screencap_0001.png",
        )) {
            val item = mediaItem(id = 5L, displayName = name, mimeType = "image/png")
            assertTrue("$name should match", screenshot.classify(item) != null)
        }
    }

    // --- Large file (specification §7.4) ------------------------------------------

    @Test
    fun `a large video is a large-file candidate`() {
        val item = mediaItem(
            id = 10L,
            mediaType = MediaType.VIDEO,
            displayName = "trip.mp4",
            sizeBytes = 1_800_000_000L,
            dateModified = recentDate,
        )

        val result = largeFile.classify(item)

        assertEquals(CandidateType.LARGE_FILE, result!!.type)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.reason.contains("video"))
        assertTrue(result.reason.contains("GB"))
    }

    @Test
    fun `the image threshold is independent of the video threshold`() {
        val atImageThreshold = mediaItem(id = 11L, sizeBytes = thresholds.largeImageBytes)
        val belowImageThreshold = mediaItem(id = 12L, sizeBytes = thresholds.largeImageBytes - 1)
        val belowVideoThreshold = mediaItem(
            id = 13L,
            mediaType = MediaType.VIDEO,
            sizeBytes = thresholds.largeVideoBytes - 1,
        )

        assertEquals(CandidateType.LARGE_FILE, largeFile.classify(atImageThreshold)!!.type)
        assertNull(largeFile.classify(belowImageThreshold))
        assertNull(largeFile.classify(belowVideoThreshold))
    }

    // --- Old media (specification §7.5) -------------------------------------------

    @Test
    fun `media older than the threshold is a candidate and never described as unused`() {
        val item = mediaItem(id = 20L, dateModified = oldDate)

        val result = oldMedia.classify(item, nowSeconds)

        assertEquals(CandidateType.OLD_MEDIA, result!!.type)
        assertEquals(Confidence.HIGH, result.confidence)
        assertTrue(result.reason.contains("Older than ${thresholds.oldMediaAgeDays} days"))
        assertTrue(result.reason.contains("days ago"))
        assertTrue("must not claim usage data: ${result.reason}", !result.reason.contains("unused"))
    }

    @Test
    fun `media exactly at the age boundary and newer is not old`() {
        val atBoundary = mediaItem(id = 21L, dateModified = thresholds.oldMediaCutoff(nowSeconds))
        val recent = mediaItem(id = 22L, dateModified = recentDate)
        val future = mediaItem(id = 23L, dateModified = nowSeconds + 60)

        assertNull(oldMedia.classify(atBoundary, nowSeconds))
        assertNull(oldMedia.classify(recent, nowSeconds))
        assertNull(oldMedia.classify(future, nowSeconds))
    }

    @Test
    fun `old-media threshold is configurable`() {
        val strict = OldMediaClassifier(ScanThresholds(oldMediaAgeDays = 1))
        val item = mediaItem(id = 24L, dateModified = nowSeconds - 3 * 24 * 60 * 60)

        assertEquals(CandidateType.OLD_MEDIA, strict.classify(item, nowSeconds)!!.type)
        assertNull(oldMedia.classify(item, nowSeconds))
    }

    // --- Aggregation (Phase 6: candidate aggregation) -----------------------------

    @Test
    fun `classification produces one candidate group per matching item`() {
        val screenshotItem = mediaItem(
            id = 30L,
            displayName = "Screenshot_20240101.png",
            mimeType = "image/png",
            relativePath = "Pictures/Screenshots",
            sizeBytes = 5_000L,
            dateModified = recentDate,
        )
        val largeVideo = mediaItem(
            id = 31L,
            mediaType = MediaType.VIDEO,
            sizeBytes = 2_000_000_000L,
            dateModified = recentDate,
        )
        val oldPhoto = mediaItem(id = 32L, sizeBytes = 9_000L, dateModified = oldDate)
        val plainPhoto = mediaItem(id = 33L, sizeBytes = 1_000L, dateModified = recentDate)

        val drafts = classifier.classifyAll(listOf(screenshotItem, largeVideo, oldPhoto, plainPhoto), nowSeconds)

        assertEquals(
            listOf(
                CandidateType.LARGE_FILE,
                CandidateType.OLD_MEDIA,
                CandidateType.SCREENSHOT,
            ),
            drafts.map { it.type }, // sorted by size: 2 GB > 9 KB > 5 KB
        )
        assertTrue(drafts.all { it.items.size == 1 })
        assertEquals(2_000_000_000L, drafts[0].totalSizeBytes)
        // The plain photo is not a candidate in any category.
        assertTrue(drafts.none { it.items.single().id == 33L })
    }

    @Test
    fun `one item may belong to several categories`() {
        val item = mediaItem(
            id = 40L,
            displayName = "Screenshot_big.png",
            mimeType = "image/png",
            relativePath = "Pictures/Screenshots",
            sizeBytes = thresholds.largeImageBytes,
            dateModified = oldDate,
        )

        val types = classifier.classify(item, nowSeconds).map { it.type }.toSet()

        assertEquals(
            setOf(CandidateType.SCREENSHOT, CandidateType.LARGE_FILE, CandidateType.OLD_MEDIA),
            types,
        )
    }

    @Test
    fun `an empty library classifies to nothing`() {
        assertTrue(classifier.classifyAll(emptyList(), nowSeconds).isEmpty())
    }
}
