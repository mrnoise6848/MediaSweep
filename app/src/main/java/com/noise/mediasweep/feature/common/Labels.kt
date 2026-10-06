package com.noise.mediasweep.feature.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.noise.mediasweep.R
import com.noise.mediasweep.domain.model.CandidateType
import com.noise.mediasweep.domain.model.Confidence

/** Localized category label, shared by home, candidates and category screens. */
@Composable
fun categoryLabel(type: CandidateType): String = stringResource(categoryLabelRes(type))

/** String resource id for a category label (also usable outside composition). */
fun categoryLabelRes(type: CandidateType): Int = when (type) {
    CandidateType.EXACT_DUPLICATE -> R.string.category_exact_duplicates
    CandidateType.NEAR_DUPLICATE -> R.string.category_near_duplicates
    CandidateType.LARGE_FILE -> R.string.category_large_files
    CandidateType.SCREENSHOT -> R.string.category_screenshots
    CandidateType.OLD_MEDIA -> R.string.category_old_media
}

/** Localized confidence badge text (specification §28). */
@Composable
fun confidenceLabel(confidence: Confidence): String = when (confidence) {
    Confidence.HIGH -> stringResource(R.string.confidence_high)
    Confidence.MEDIUM -> stringResource(R.string.confidence_medium)
    Confidence.LOW -> stringResource(R.string.confidence_low)
}
