package com.noise.mediasweep.feature.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.core.common.formatCount
import com.noise.mediasweep.core.common.formatProgress
import com.noise.mediasweep.core.media.mediaPermissionRequest
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.CategorySummary
import com.noise.mediasweep.domain.model.StorageSummary
import com.noise.mediasweep.feature.common.categoryLabelRes

@Composable
fun HomeRoute(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
    onScanClick: (() -> Unit)? = null,
    onReviewClick: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Only the real OS grant counts: re-read it instead of assuming it was granted.
        viewModel.refreshPermission()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    HomeScreen(
        state = state,
        onScanClick = onScanClick,
        onReviewClick = onReviewClick,
        onGrantPermission = { permissionLauncher.launch(mediaPermissionRequest()) },
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onScanClick: (() -> Unit)? = null,
    onReviewClick: (() -> Unit)? = null,
    onGrantPermission: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (state) {
                HomeUiState.Loading -> LoadingContent()
                HomeUiState.NoPermission -> NoPermissionContent(onGrantPermission, onOpenSettings)
                HomeUiState.NoMedia -> MessageContent(
                    title = stringResource(R.string.state_no_media_title),
                    body = stringResource(R.string.state_no_media_body),
                )

                HomeUiState.NotScanned -> MessageContent(
                    title = stringResource(R.string.state_not_scanned_title),
                    body = stringResource(R.string.state_not_scanned_body),
                )

                is HomeUiState.Scanning -> ScanningContent(state)
                is HomeUiState.Failed -> MessageContent(
                    title = stringResource(R.string.state_failed_title),
                    body = stringResource(R.string.state_failed_body),
                )

                is HomeUiState.Stale -> SummaryContent(
                    summary = state.summary,
                    noticeTitle = stringResource(R.string.state_stale_title),
                    noticeBody = stringResource(R.string.state_stale_body),
                )

                is HomeUiState.Complete -> SummaryContent(summary = state.summary)
                is HomeUiState.Partial -> SummaryContent(
                    summary = state.summary,
                    noticeTitle = stringResource(R.string.state_partial_title),
                    noticeBody = stringResource(R.string.state_partial_body),
                )
            }

            if (onScanClick != null) {
                OutlinedButton(onClick = onScanClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_scan_now))
                }
            }
            if (onReviewClick != null) {
                Button(onClick = onReviewClick, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.home_review_candidates))
                }
            }
        }
    }
}

@Composable
private fun LoadingContent() {
    val description = stringResource(R.string.state_loading)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text = description, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoPermissionContent(onGrantPermission: (() -> Unit)?, onOpenSettings: (() -> Unit)?) {
    MessageCard(
        title = stringResource(R.string.state_no_permission_title),
        body = stringResource(R.string.state_no_permission_body),
    )
    if (onGrantPermission != null) {
        Button(onClick = onGrantPermission, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.state_no_permission_action))
        }
    }
    if (onOpenSettings != null) {
        TextButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.state_open_settings))
        }
    }
}

@Composable
private fun ScanningContent(state: HomeUiState.Scanning) {
    MessageCard(
        title = stringResource(R.string.state_scanning_title),
        body = stringResource(R.string.state_scanning_body),
    )
    val progress = state.progress
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        // Assistive tech gets a full sentence instead of a raw "4,832 / 12,240".
        val progressAnnouncement = stringResource(
            R.string.scan_progress_a11y,
            formatCount(progress.processedCount),
            formatCount(progress.totalCount),
        )
        Text(
            text = formatProgress(progress.processedCount, progress.totalCount),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = progressAnnouncement },
        )
    } else {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun MessageContent(title: String, body: String) {
    MessageCard(title = title, body = body)
}

@Composable
private fun MessageCard(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SummaryContent(
    summary: StorageSummary,
    noticeTitle: String? = null,
    noticeBody: String? = null,
) {
    if (noticeTitle != null && noticeBody != null) {
        MessageCard(title = noticeTitle, body = noticeBody)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryRow(
                label = stringResource(R.string.home_your_media),
                value = formatBytes(summary.totals.mediaBytes),
                emphasis = true,
            )
            HorizontalDivider()
            SummaryRow(
                label = stringResource(R.string.home_candidates_total),
                value = formatBytes(summary.candidateBytes),
                emphasis = true,
            )
            HorizontalDivider()
            CandidateType.entries.forEach { type ->
                val category = summary.category(type) ?: CategorySummary(type, 0, 0, 0L)
                SummaryRow(
                    label = stringResource(categoryLabelRes(type)),
                    value = formatBytes(category.totalSizeBytes),
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
