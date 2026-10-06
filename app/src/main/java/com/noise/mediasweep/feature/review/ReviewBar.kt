package com.noise.mediasweep.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noise.mediasweep.R
import com.noise.mediasweep.core.common.formatBytes

/** Test tag for the primary action, so host-side tests can assert its enabled state. */
const val REVIEW_ACTION_TAG = "review_action"

/**
 * Persistent action bar showing what is currently selected and leading to the review
 * summary (specification §9 flow: select → review total size → next step).
 */
@Composable
fun ReviewBar(
    selection: ReviewSelection,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (selection.isNotEmpty) {
                    Text(
                        text = stringResource(
                            R.string.review_selection_summary,
                            selection.items.size,
                            formatBytes(selection.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.group_review_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = onReview,
                enabled = selection.isNotEmpty,
                modifier = Modifier.testTag(REVIEW_ACTION_TAG),
            ) {
                Text(stringResource(R.string.group_review_action))
            }
        }
    }
}
