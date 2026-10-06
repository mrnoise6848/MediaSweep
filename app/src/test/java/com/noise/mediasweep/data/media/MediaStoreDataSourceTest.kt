package com.noise.mediasweep.data.media

import android.database.MatrixCursor
import android.provider.MediaStore
import com.noise.mediasweep.domain.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreDataSourceTest {

    private fun cursor(vararg columnNames: String) = MatrixCursor(arrayOf(*columnNames))

    private fun MatrixCursor.addRow(
        id: Long,
        name: String,
        size: Long,
        mediaType: Int = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
        width: Int? = 1080,
        height: Int? = 1920,
        duration: Long? = null,
        mime: String? = "image/jpeg",
    ) {
        addRow(
            arrayOf<Any?>(
                id,
                mediaType,
                name,
                mime,
                size,
                width,
                height,
                duration,
                1_700_000_000L,
                1_700_000_100L,
                "DCIM/Camera",
                "bucket-1",
                "Camera",
                0,
                0,
                0,
            ),
        )
    }

    private fun fullCursor(rows: Int = 0): MatrixCursor {
        val c = cursor(*AndroidMediaStoreDataSource.PROJECTION)
        repeat(rows) { index ->
            c.addRow(
                id = index.toLong() + 1,
                name = "IMG_$index.jpg",
                size = 1_000L + index,
            )
        }
        return c
    }

    @Test
    fun `media store rows map to domain metadata`() {
        val c = cursor(*AndroidMediaStoreDataSource.PROJECTION)
        c.addRow(id = 42L, name = "VID_2026.mp4", size = 1_887_436_800L, mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, mime = "video/mp4", duration = 65_000L)

        assertTrue(c.moveToFirst())
        val item = MediaMetadataMapper.map(c)
        assertNotNull(item)
        assertEquals(42L, item!!.id)
        assertEquals(MediaType.VIDEO, item.mediaType)
        assertEquals("VID_2026.mp4", item.displayName)
        assertEquals("video/mp4", item.mimeType)
        assertEquals(1_887_436_800L, item.sizeBytes)
        assertEquals(65_000L, item.durationMs)
        assertEquals("DCIM/Camera", item.relativePath)
        assertEquals("Camera", item.bucketDisplayName)
        assertEquals(false, item.isFavorite)
        assertEquals(false, item.isTrashed)
        assertTrue(item.contentUri.contains("42"))
    }

    @Test
    fun `a row missing required columns is skipped instead of failing the scan`() {
        val broken = cursor(MediaStore.MediaColumns.DISPLAY_NAME)
        broken.addRow(arrayOf("IMG_broken.jpg"))

        assertTrue(broken.moveToFirst())
        assertNull(MediaMetadataMapper.map(broken))
    }

    @Test
    fun `rows with null optional fields still map`() {
        val c = cursor(*AndroidMediaStoreDataSource.PROJECTION)
        c.addRow(id = 7L, name = "IMG_7.jpg", size = 10L, width = null, height = null, duration = null, mime = null)

        assertTrue(c.moveToFirst())
        val item = MediaMetadataMapper.map(c)
        assertNotNull(item)
        assertNull(item!!.width)
        assertNull(item.height)
        assertNull(item.mimeType)
        assertEquals(MediaType.IMAGE, item.mediaType)
    }

    @Test
    fun `batching delivers every row in bounded batches`() = runBlocking {
        val total = 1_012
        val dataSource = AndroidMediaStoreDataSource { _, _, _, _ -> fullCursor(total) }

        val sizes = mutableListOf<Int>()
        val seen = mutableListOf<Long>()
        val reported = dataSource.forEachMedia(batchSize = 250) { batch ->
            sizes.add(batch.size)
            seen.addAll(batch.map { it.id })
        }

        assertEquals(total, reported)
        assertEquals(total, seen.size)
        assertEquals(total, seen.distinct().size)
        assertTrue("expected the last batch to be partial", sizes.last() < 250)
        assertTrue("expected bounded batches", sizes.all { it <= 250 })
    }

    @Test
    fun `an empty library reports zero media without failing`() = runBlocking {
        val dataSource = AndroidMediaStoreDataSource { _, _, _, _ -> fullCursor(0) }
        val batches = mutableListOf<List<com.noise.mediasweep.domain.model.MediaItem>>()
        val reported = dataSource.forEachMedia(batchSize = 100) { batches.add(it) }

        assertEquals(0, reported)
        assertTrue(batches.isEmpty())
        assertTrue(dataSource.listMedia().isEmpty())
    }

    @Test
    fun `a failing provider is treated as an empty library`() = runBlocking {
        val dataSource = AndroidMediaStoreDataSource { _, _, _, _ -> null }
        assertEquals(0, dataSource.forEachMedia(batchSize = 100) { })
        assertTrue(dataSource.listMedia().isEmpty())
    }

    @Test
    fun `post-confirmation verification only reports rows media store still shows`() = runBlocking {
        var queries = 0
        val dataSource = AndroidMediaStoreDataSource { _, _, _, _ ->
            queries++
            cursor(MediaStore.MediaColumns._ID).apply {
                addRow(arrayOf<Any?>(1L))
                addRow(arrayOf<Any?>(3L))
            }
        }

        val present = dataSource.activeIds(listOf(1L, 2L, 3L))

        assertEquals(setOf(1L, 3L), present)
        assertEquals(1, queries)
    }

    @Test
    fun `the verification query asks for active media explicitly`() = runBlocking {
        var seenSelection: String? = null
        var seenProjection: Array<String>? = null
        val dataSource = AndroidMediaStoreDataSource { projection, selection, args, _ ->
            seenSelection = selection
            seenProjection = projection
            // id parameter first, then the media type filter arguments.
            assertEquals("42", args.first())
            assertEquals(3, args.size)
            cursor(MediaStore.MediaColumns._ID)
        }

        dataSource.activeIds(listOf(42L))

        assertTrue(seenSelection!!.contains(MediaStore.MediaColumns._ID))
        assertTrue(seenSelection.contains("IN (?)"))
        assertTrue(seenSelection.contains("${MediaStore.MediaColumns.IS_PENDING} = 0"))
        assertTrue(seenSelection.contains("${MediaStore.MediaColumns.IS_TRASHED} = 0"))
        assertTrue(seenProjection!!.contentEquals(arrayOf(MediaStore.MediaColumns._ID)))
    }

    @Test
    @Config(sdk = [29])
    fun `android 10 verification never mentions columns it does not have`() = runBlocking {
        var seenSelection: String? = null
        val dataSource = AndroidMediaStoreDataSource { _, selection, _, _ ->
            seenSelection = selection
            cursor(MediaStore.MediaColumns._ID)
        }

        val present = dataSource.activeIds(listOf(7L))

        assertEquals(emptySet<Long>(), present)
        assertTrue(seenSelection!!.contains(MediaStore.MediaColumns.IS_PENDING))
        assertFalse(seenSelection.contains(MediaStore.MediaColumns.IS_TRASHED))
    }

    @Test
    fun `scan projections match the api level of the device`() {
        val base = AndroidMediaStoreDataSource.projectionFor(29)
        assertTrue(base.contains(MediaStore.MediaColumns.IS_PENDING))
        assertFalse(base.contains(MediaStore.MediaColumns.IS_FAVORITE))
        assertFalse(base.contains(MediaStore.MediaColumns.IS_TRASHED))

        val modern = AndroidMediaStoreDataSource.projectionFor(35)
        assertTrue(modern.contains(MediaStore.MediaColumns.IS_TRASHED))
        assertTrue(modern.contains(MediaStore.MediaColumns.IS_FAVORITE))
    }
}
