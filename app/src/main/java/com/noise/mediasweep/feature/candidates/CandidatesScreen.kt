package com.noise.mediasweep.feature.candidates

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.feature.common.categoryLabel

@Composable
fun CandidatesRoute(
    onBack: () -> Unit,
    onOpenCategory: (CandidateType) -> Unit,
    viewModel: CandidatesViewModel = viewModel(factory = CandidatesViewModel.Factory),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    CandidatesScreen(
        categories = categories,
        onBack = onBack,
        onOpenCategory = onOpenCategory,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidatesScreen(
    categories: List<CategorySummary>?,
    onBack: () -> Unit,
    onOpenCategory: (CandidateType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.candidates_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { innerPadding ->
        val hasResults = categories?.any { !it.isEmpty } == true
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.candidates_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            when {
                // Room has not answered yet: never flash "clean library" for a library
                // that has simply not been read (loading states, specification Phase 10).
                categories == null -> LoadingCard()

                !hasResults -> EmptyCard(
                    title = stringResource(R.string.candidates_empty_title),
                    body = stringResource(R.string.candidates_empty_body),
                )

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(categories, key = { it.type.name }) { summary ->
                        CategoryRow(summary = summary, onClick = { onOpenCategory(summary.type) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(summary: CategorySummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = categoryLabel(summary.type),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        summary.groupCount == 0 -> stringResource(R.string.empty_library_clean_body)
                        summary.groupCount == summary.itemCount ->
                            stringResource(R.string.category_items_count, summary.itemCount)
                        else -> stringResource(R.string.category_groups_count, summary.groupCount)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatBytes(summary.totalSizeBytes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Loading state: one merged node so assistive tech announces it exactly once. */
@Composable
private fun LoadingCard() {
    val description = stringResource(R.string.state_loading)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
