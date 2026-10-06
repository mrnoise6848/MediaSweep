package com.noise.mediasweep.feature.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noise.mediasweep.domain.model.Confidence

/** Small colored confidence badge (specification §28: confidence is always visible). */
@Composable
fun ConfidenceBadge(confidence: Confidence, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = when (confidence) {
            Confidence.HIGH -> MaterialTheme.colorScheme.primaryContainer
            Confidence.MEDIUM -> MaterialTheme.colorScheme.secondaryContainer
            Confidence.LOW -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = confidenceLabel(confidence),
            style = MaterialTheme.typography.labelSmall,
            color = when (confidence) {
                Confidence.HIGH -> MaterialTheme.colorScheme.onPrimaryContainer
                Confidence.MEDIUM -> MaterialTheme.colorScheme.onSecondaryContainer
                Confidence.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
