package com.noise.mediasweep.feature.scan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.core.common.formatCount
import com.noise.mediasweep.core.common.formatProgress
import com.noise.mediasweep.data.repository.ScanOutcome
import com.noise.mediasweep.data.repository.ScanRunState
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.ScanProgress
import com.noise.mediasweep.domain.model.ScanStatus
import com.noise.mediasweep.feature.common.categoryLabelRes

/**
 * Scan screen (specification §27): real progress counters, the candidates found so far
 * and a cooperative Cancel. The run itself lives in the application-scoped scan
 * controller, so this screen only observes and steers it (§18).
 */
@Composable
fun ScanRoute(
    onBack: () -> Unit,
    viewModel: ScanViewModel = viewModel(factory = ScanViewModel.Factory),
) {
    val state by viewModel.runState.collectAsStateWithLifecycle()
    val partialAccess by viewModel.partialAccess.collectAsStateWithLifecycle()

    ScanScreen(
        state = state,
        partialAccess = partialAccess,
        onStart = viewModel::start,
        onCancel = viewModel::cancel,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: ScanRunState,
    partialAccess: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            // Scrollable so long results and large fonts stay reachable on small screens.
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                ScanRunState.Idle -> {
                    ScanMessageCard(
                        title = stringResource(R.string.state_not_scanned_title),
                        body = stringResource(R.string.state_not_scanned_body),
                    )
                    StartButton(onStart, label = stringResource(R.string.home_scan_now))
                }

                is ScanRunState.Running -> RunningContent(
                    progress = state.progress,
                    partialAccess = partialAccess,
                    onCancel = onCancel,
                )

                ScanRunState.Cancelling -> ScanMessageCard(
                    title = stringResource(R.string.scan_stopping),
                    body = stringResource(R.string.state_scanning_body),
                )

                ScanRunState.Cancelled -> {
                    ScanMessageCard(
                        title = stringResource(R.string.scan_cancelled_title),
                        body = stringResource(R.string.scan_cancelled_body),
                    )
                    StartButton(onStart, label = stringResource(R.string.scan_again))
                }

                is ScanRunState.Finished -> FinishedContent(
                    outcome = state.outcome,
                    onStart = onStart,
                )
            }
        }
    }
}

@Composable
private fun RunningContent(
    progress: ScanProgress?,
    partialAccess: Boolean,
    onCancel: () -> Unit,
) {
    ScanMessageCard(
        title = stringResource(R.string.state_scanning_title),
        body = stringResource(R.string.state_scanning_body),
    )
    if (partialAccess) {
        Text(
            text = stringResource(R.string.state_partial_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (progress != null && progress.totalCount > 0L) {
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        // The visible line stays terse ("4,832 / 12,240"); assistive tech gets a
        // sentence that reads naturally (accessibility, specification Phase 10).
        val progressAnnouncement = stringResource(
            R.string.scan_progress_a11y,
            formatCount(progress.processedCount),
            formatCount(progress.totalCount),
        )
        Text(
            text = formatProgress(progress.processedCount, progress.totalCount),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { contentDescription = progressAnnouncement },
        )
    } else {
        // No trustworthy counter yet — show activity without inventing a percentage.
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }

    FoundCounts(foundCounts = progress?.foundCounts ?: emptyMap())

    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun FinishedContent(outcome: ScanOutcome, onStart: () -> Unit) {
    when (outcome.status) {
        ScanStatus.COMPLETE -> {
            ScanMessageCard(
                title = stringResource(R.string.scan_complete_title),
                body = stringResource(
                    R.string.scan_scanned,
                    formatCount(outcome.mediaCount),
                    formatBytes(outcome.mediaBytes),
                ),
            )
            FoundCounts(foundCounts = outcome.groupCounts)
            StartButton(onStart, label = stringResource(R.string.scan_again))
        }

        ScanStatus.PARTIAL -> {
            ScanMessageCard(
                title = stringResource(R.string.state_partial_title),
                body = stringResource(R.string.state_partial_body),
                extra = stringResource(
                    R.string.scan_scanned,
                    formatCount(outcome.mediaCount),
                    formatBytes(outcome.mediaBytes),
                ),
            )
            FoundCounts(foundCounts = outcome.groupCounts)
            StartButton(onStart, label = stringResource(R.string.scan_again))
        }

        ScanStatus.FAILED -> {
            ScanMessageCard(
                title = stringResource(R.string.state_failed_title),
                body = outcome.errorMessage ?: stringResource(R.string.state_failed_body),
            )
            StartButton(onStart, label = stringResource(R.string.action_retry))
        }

        // Terminal states are never produced by a finished run.
        ScanStatus.NOT_SCANNED, ScanStatus.SCANNING, ScanStatus.STALE -> ScanMessageCard(
            title = stringResource(R.string.state_scanning_title),
            body = stringResource(R.string.state_scanning_body),
        )
    }
}

/** "Found so far" list — only counts that really exist (§27). */
@Composable
private fun FoundCounts(foundCounts: Map<CandidateType, Int>) {
    val rows = CandidateType.entries.mapNotNull { type ->
        foundCounts[type]?.takeIf { it > 0 }?.let { type to it }
    }
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.scan_found_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.scan_found_title),
                style = MaterialTheme.typography.titleMedium,
            )
            rows.forEach { (type, count) ->
                Text(
                    text = stringResource(
                        R.string.scan_found_row,
                        stringResource(categoryLabelRes(type)),
                        count,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun StartButton(onStart: () -> Unit, label: String) {
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}

@Composable
private fun ScanMessageCard(title: String, body: String, extra: String? = null) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            // Separate node per statement: each message stays readable on its own.
            if (extra != null) {
                Text(text = extra, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
