package com.noise.mediasweep.feature.groupdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.core.common.formatDate
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.MediaItem
import com.noise.mediasweep.feature.common.ConfidenceBadge
import com.noise.mediasweep.feature.common.MediaThumbnail
import com.noise.mediasweep.feature.review.ReviewBar
import com.noise.mediasweep.feature.review.ReviewSelection

@Composable
fun GroupDetailRoute(
    groupId: Long,
    onBack: () -> Unit,
    onReview: () -> Unit,
    viewModel: GroupDetailViewModel = viewModel(factory = GroupDetailViewModel.factory(groupId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.selection.collectAsStateWithLifecycle()

    GroupDetailScreen(
        state = state,
        selection = selection,
        onBack = onBack,
        onToggle = viewModel::toggle,
        onSelectGroup = viewModel::selectGroup,
        onClearGroup = viewModel::clearGroup,
        onReview = onReview,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(
    state: GroupDetailUiState,
    selection: ReviewSelection,
    onBack: () -> Unit,
    onToggle: (MediaItem) -> Unit,
    onSelectGroup: () -> Unit,
    onClearGroup: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_review_action)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = { ReviewBar(selection = selection, onReview = onReview) },
    ) { innerPadding ->
        when (state) {
            GroupDetailUiState.Loading -> Column(
                modifier = Modifier.padding(innerPadding).padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.state_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            GroupDetailUiState.NotFound -> Column(
                modifier = Modifier.padding(innerPadding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.group_not_found_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.group_not_found_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is GroupDetailUiState.Content -> GroupContent(
                state = state,
                selection = selection,
                onToggle = onToggle,
                onSelectGroup = onSelectGroup,
                onClearGroup = onClearGroup,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun GroupContent(
    state: GroupDetailUiState.Content,
    selection: ReviewSelection,
    onToggle: (MediaItem) -> Unit,
    onSelectGroup: () -> Unit,
    onClearGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val group = state.group
    val selectedInGroup = group.items.count { selection.isSelected(it.id) }
    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = group.group.reason, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${stringResource(R.string.category_items_count, group.group.itemCount)} • " +
                            formatBytes(group.group.totalSizeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ConfidenceBadge(confidence = group.group.confidence)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.group_selected_of, selectedInGroup, group.items.size),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (selectedInGroup == group.items.size) {
                    TextButton(onClick = onClearGroup) {
                        Text(stringResource(R.string.action_clear_selection))
                    }
                } else {
                    TextButton(onClick = onSelectGroup) {
                        Text(stringResource(R.string.action_select_all))
                    }
                }
            }
            HorizontalDivider()
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(group.items, key = { it.id }) { item ->
                MediaRow(
                    item = item,
                    selected = selection.isSelected(item.id),
                    onToggle = { onToggle(item) },
                )
            }
        }
    }
}

/**
 * One row of the image comparison (specification §29): thumbnail, name, size and date,
 * with individual selection. No automatic keeper decision is ever made here.
 */
@Composable
private fun MediaRow(
    item: MediaItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(
                contentUri = item.contentUri,
                size = 72.dp,
                contentDescription = item.displayName,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = item.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = formatBytes(item.sizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDate(item.dateModified),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}
