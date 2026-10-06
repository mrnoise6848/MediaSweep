package com.noise.mediasweep.feature.candidates

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.domain.model.CandidateGroupWithItems
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.feature.common.ConfidenceBadge
import com.noise.mediasweep.feature.common.MediaThumbnail
import com.noise.mediasweep.feature.common.categoryLabel
import com.noise.mediasweep.feature.review.ReviewBar
import com.noise.mediasweep.feature.review.ReviewSelection

@Composable
fun CategoryRoute(
    type: CandidateType,
    onBack: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    onReview: () -> Unit,
    viewModel: CategoryViewModel = viewModel(factory = CategoryViewModel.factory(type)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selection by viewModel.selection.selection.collectAsStateWithLifecycle()

    CategoryScreen(
        state = state,
        selection = selection,
        isGroupSelected = viewModel::areAllGroupItemsSelected,
        onBack = onBack,
        onSort = viewModel::setSort,
        onToggleGroup = viewModel::toggleGroup,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearCategory,
        onOpenGroup = onOpenGroup,
        onReview = onReview,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    state: CategoryUiState,
    selection: ReviewSelection,
    isGroupSelected: (CandidateGroupWithItems) -> Boolean,
    onBack: () -> Unit,
    onSort: (GroupSort) -> Unit,
    onToggleGroup: (CandidateGroupWithItems) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenGroup: (Long) -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(categoryLabel(state.type)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = { ReviewBar(selection = selection, onReview = onReview) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.category_groups_count, state.groupCount),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "${formatBytes(state.reviewableBytes)} ${stringResource(R.string.category_reviewable)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SortRow(current = state.sort, onSort = onSort)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.isNotEmpty) {
                        TextButton(onClick = onSelectAll) {
                            Text(stringResource(R.string.action_select_all))
                        }
                    }
                    if (selection.isNotEmpty) {
                        TextButton(onClick = onClearSelection) {
                            Text(stringResource(R.string.action_clear_selection))
                        }
                    }
                }
                HorizontalDivider()
            }

            if (state.isEmpty) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.category_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.category_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.groups, key = { it.group.id }) { group ->
                        GroupCard(
                            group = group,
                            selected = isGroupSelected(group),
                            onToggle = { onToggleGroup(group) },
                            onClick = { onOpenGroup(group.group.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SortRow(current: GroupSort, onSort: (GroupSort) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SortButton(label = stringResource(R.string.sort_largest), selected = current == GroupSort.LARGEST) {
            onSort(GroupSort.LARGEST)
        }
        SortButton(label = stringResource(R.string.sort_newest), selected = current == GroupSort.NEWEST) {
            onSort(GroupSort.NEWEST)
        }
        SortButton(label = stringResource(R.string.sort_oldest), selected = current == GroupSort.OLDEST) {
            onSort(GroupSort.OLDEST)
        }
    }
}

@Composable
private fun SortButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun GroupCard(
    group: CandidateGroupWithItems,
    selected: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(
                contentUri = group.items.firstOrNull()?.contentUri ?: "",
                size = 56.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = group.group.reason,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${stringResource(R.string.category_items_count, group.group.itemCount)} • " +
                        formatBytes(group.group.totalSizeBytes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConfidenceBadge(confidence = group.group.confidence)
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}
