package com.noise.mediasweep.navigation

import com.noise.mediasweep.domain.model.CandidateType

/**
 * Central route table. Screens are registered in [MediaSweepNavHost] as the phases that
 * introduce them land, so a route only exists once there is a real screen behind it.
 */
object Routes {
    const val HOME = "home"
    const val SCAN = "scan"
    const val CANDIDATES = "candidates"
    const val REVIEW = "review"
    const val SETTINGS = "settings"

    const val CATEGORY_ARG = "type"
    const val CATEGORY = "category/{$CATEGORY_ARG}"
    fun category(type: CandidateType): String = "category/${type.name}"

    const val GROUP_ARG = "groupId"
    const val GROUP = "group/{$GROUP_ARG}"
    fun group(groupId: Long): String = "group/$groupId"
}
