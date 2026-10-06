package com.noise.mediasweep.feature.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSelectionTest {

    private fun media(id: Long, size: Long = 100L, video: Boolean = false) = SelectedMedia(
        id = id,
        contentUri = "content://media/$id",
        displayName = if (video) "clip_$id.mp4" else "img_$id.jpg",
        sizeBytes = size,
        isVideo = video,
    )

    @Test
    fun `toggling adds and removes items`() {
        var selection = ReviewSelection()

        selection = selection.toggle(media(1L))
        assertTrue(selection.isSelected(1L))
        assertEquals(1, selection.items.size)

        selection = selection.toggle(media(1L))
        assertFalse(selection.isSelected(1L))
        assertTrue(selection.isEmpty)
    }

    @Test
    fun `counts and totals are derived from the selected items`() {
        val photos = (1L..27L).map { media(id = it, size = 1_000L) }
        val videos = (28L..30L).map { media(id = it, size = 500_000L, video = true) }

        val selection = ReviewSelection(photos + videos)

        assertEquals(27, selection.photoCount)
        assertEquals(3, selection.videoCount)
        assertEquals(27L * 1_000L + 3L * 500_000L, selection.totalBytes)
        assertEquals(30, selection.items.size)
    }

    @Test
    fun `selecting all is idempotent and never duplicates an item`() {
        val items = listOf(media(1L), media(2L))
        var selection = ReviewSelection(listOf(media(1L)))

        selection = selection.withAll(items, selected = true)

        assertEquals(listOf(1L, 2L), selection.items.map { it.id })
    }

    @Test
    fun `deselecting a source list only removes those items`() {
        val selection = ReviewSelection(listOf(media(1L), media(2L), media(3L)))

        val updated = selection.withAll(listOf(media(1L), media(2L)), selected = false)

        assertEquals(listOf(3L), updated.items.map { it.id })
    }

    @Test
    fun `empty selection has zero counts`() {
        val selection = ReviewSelection()

        assertTrue(selection.isEmpty)
        assertEquals(0, selection.photoCount)
        assertEquals(0, selection.videoCount)
        assertEquals(0L, selection.totalBytes)
    }

    @Test
    fun `store toggles and clears`() {
        val store = ReviewSelectionStore()

        store.toggle(media(1L, size = 500L))
        store.toggle(media(2L, size = 250L, video = true))
        assertEquals(2, store.selection.value.items.size)
        assertEquals(750L, store.selection.value.totalBytes)

        store.setAll(listOf(media(3L, size = 10L)), selected = true)
        assertEquals(3, store.selection.value.items.size)

        store.clear()
        assertTrue(store.selection.value.isEmpty)
    }

    @Test
    fun `items confirmed as trashed leave the selection and the rest stay`() {
        val store = ReviewSelectionStore()
        store.toggle(media(1L, size = 500L))
        store.toggle(media(2L, size = 250L, video = true))
        store.toggle(media(3L, size = 10L))

        store.remove(listOf(1L, 3L))

        assertEquals(listOf(2L), store.selection.value.items.map { it.id })
        assertEquals(250L, store.selection.value.totalBytes)

        // Nothing removed (or only unknown ids) leaves the selection untouched.
        store.remove(emptyList())
        store.remove(listOf(99L))
        assertEquals(listOf(2L), store.selection.value.items.map { it.id })
    }
}
