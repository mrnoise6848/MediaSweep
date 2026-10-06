package com.noise.mediasweep.feature.review

import com.noise.mediasweep.domain.model.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One media item chosen for review, reduced to what the review summary needs. */
data class SelectedMedia(
    val id: Long,
    val contentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val isVideo: Boolean,
) {
    companion object {
        fun from(item: MediaItem): SelectedMedia = SelectedMedia(
            id = item.id,
            contentUri = item.contentUri,
            displayName = item.displayName,
            sizeBytes = item.sizeBytes,
            isVideo = item.isVideo,
        )
    }
}

/**
 * The current review selection with its derived counts (specification §33).
 *
 * Counts and totals are always derived from the selected items — never stored separately
 * so they can never drift from reality.
 */
data class ReviewSelection(
    val items: List<SelectedMedia> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty()
    val isNotEmpty: Boolean get() = items.isNotEmpty()
    val ids: Set<Long> by lazy { items.mapTo(LinkedHashSet()) { it.id } }

    val photoCount: Int get() = items.count { !it.isVideo }
    val videoCount: Int get() = items.count { it.isVideo }
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }

    fun isSelected(id: Long): Boolean = id in ids

    fun toggle(item: SelectedMedia): ReviewSelection =
        if (isSelected(item.id)) copy(items = items.filterNot { it.id == item.id })
        else copy(items = items + item)

    fun withAll(source: List<SelectedMedia>, selected: Boolean): ReviewSelection {
        if (!selected) {
            val deselected = source.mapTo(HashSet()) { it.id }
            return copy(items = items.filterNot { it.id in deselected })
        }
        val existing = ids
        return copy(items = items + source.filterNot { it.id in existing })
    }
}

/**
 * Application-scoped holder of the review selection.
 *
 * Lives in [com.noise.mediasweep.core.di.AppContainer] so the selection survives
 * navigation between the category, group detail and review screens (and does not reset
 * when a back stack entry is destroyed).
 */
class ReviewSelectionStore {

    private val _selection = MutableStateFlow(ReviewSelection())
    val selection: StateFlow<ReviewSelection> = _selection.asStateFlow()

    fun toggle(item: SelectedMedia) {
        _selection.value = _selection.value.toggle(item)
    }

    fun setAll(items: List<SelectedMedia>, selected: Boolean) {
        _selection.value = _selection.value.withAll(items, selected)
    }

    fun clear() {
        _selection.value = ReviewSelection()
    }

    /** Drops items whose media left the active library (after a confirmed trash). */
    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val removed = ids.toHashSet()
        _selection.value = _selection.value.copy(
            items = _selection.value.items.filterNot { it.id in removed },
        )
    }
}
