package com.noise.mediasweep.core.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Opens the system settings page for this app (used when a permission was permanently denied). */
fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
