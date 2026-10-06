package com.noise.mediasweep.feature.review

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes
import com.noise.mediasweep.core.di.appContainer

/**
 * Review summary shown before any destructive system operation (specification §33).
 *
 * Counts and total are derived from the selection itself. The primary action launches the
 * system confirmation through [TrashViewModel]; MediaSweep only reconciles its index after
 * MediaStore reports the real outcome (specification §10, §11, §46).
 */
@Composable
fun ReviewSummaryRoute(
    onBack: () -> Unit,
    store: ReviewSelectionStore = appContainer(LocalContext.current).reviewSelection,
    viewModel: TrashViewModel = viewModel(factory = TrashViewModel.Factory),
) {
    val selection by store.selection.collectAsStateWithLifecycle()
    val trashState by viewModel.uiState.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onConfirmationResult(result.resultCode)
    }

    // The system dialog is launched exactly once per request, straight from the state that
    // carries its intent sender; a failure to launch changes nothing locally.
    LaunchedEffect(trashState) {
        val pending = trashState as? TrashUiState.LaunchConfirmation ?: return@LaunchedEffect
        try {
            launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            viewModel.onConfirmationLaunched()
        } catch (e: Exception) {
            viewModel.onLaunchFailed()
        }
    }

    ReviewSummaryScreen(
        selection = selection,
        trashState = trashState,
        trashSupported = viewModel.trashSupported,
        onBack = onBack,
        onClear = { store.clear() },
        onDismissResult = viewModel::dismissResult,
        onMoveToTrash = if (viewModel.trashSupported) ({ viewModel.moveToTrash() }) else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSummaryScreen(
    selection: ReviewSelection,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onMoveToTrash: (() -> Unit)?,
    modifier: Modifier = Modifier,
    trashState: TrashUiState = TrashUiState.Idle,
    trashSupported: Boolean = true,
    onDismissResult: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (trashState !is TrashUiState.Idle) {
                TrashStateCard(
                    state = trashState,
                    onDismiss = onDismissResult,
                )
            }

            if (selection.isEmpty) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.review_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.review_empty_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                return@Column
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.review_about_to),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (selection.photoCount > 0) {
                        Text(
                            text = stringResource(R.string.review_photos, selection.photoCount),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (selection.videoCount > 0) {
                        Text(
                            text = stringResource(R.string.review_videos, selection.videoCount),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.review_total),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = formatBytes(selection.totalBytes),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.review_trash_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val idle = trashState is TrashUiState.Idle
            when {
                onMoveToTrash != null && idle -> Button(
                    onClick = onMoveToTrash,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.review_move_to_trash))
                }

                // The platform cannot ask the system to trash media: say so up front
                // instead of offering a destructive action the system cannot honour.
                !trashSupported -> Text(
                    text = stringResource(R.string.trash_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
            if (selection.isNotEmpty) {
                TextButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_clear_selection))
                }
            }
        }
    }
}

/** Progress and result of one trash attempt (specification §46). */
@Composable
private fun TrashStateCard(
    state: TrashUiState,
    onDismiss: () -> Unit,
) {
    // One line per statement keeps each message independently readable (and assertable).
    val messages: List<String> = when (state) {
        is TrashUiState.LaunchConfirmation, TrashUiState.AwaitingConfirmation ->
            listOf(stringResource(R.string.trash_waiting))

        TrashUiState.Verifying -> listOf(stringResource(R.string.trash_verifying))

        is TrashUiState.Completed -> buildList {
            add(stringResource(R.string.trash_completed, state.removedCount))
            if (state.stillPresentCount > 0) {
                add(stringResource(R.string.trash_completed_unchanged, state.stillPresentCount))
            }
        }

        TrashUiState.Cancelled -> listOf(stringResource(R.string.trash_cancelled))
        TrashUiState.Unsupported -> listOf(stringResource(R.string.trash_unsupported))
        TrashUiState.RequestFailed -> listOf(stringResource(R.string.trash_request_failed))
        TrashUiState.VerifyFailed -> listOf(stringResource(R.string.trash_verify_failed))
        TrashUiState.Idle -> emptyList()
    }

    val dismissible = when (state) {
        is TrashUiState.LaunchConfirmation,
        TrashUiState.AwaitingConfirmation,
        TrashUiState.Verifying,
        TrashUiState.Idle,
        -> false

        else -> true
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            messages.forEach { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (dismissible) {
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.trash_result_done))
                }
            }
        }
    }
}
