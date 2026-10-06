package com.noise.mediasweep.core.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reads the current media permission state without owning when it is re-read. */
fun interface MediaPermissionChecker {
    fun check(): MediaAccess
}

/**
 * Observable permission state.
 *
 * The value is re-read explicitly (after a permission dialog result and on resume), never
 * polled in a loop, so the UI always reflects the real grant.
 */
interface MediaPermissionMonitor {
    val status: StateFlow<MediaAccess>

    fun refresh()
}

class AndroidMediaPermissionChecker(private val context: Context) : MediaPermissionChecker {

    override fun check(): MediaAccess {
        val sdk = Build.VERSION.SDK_INT
        return if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
            val video = granted(Manifest.permission.READ_MEDIA_VIDEO)
            when {
                images && video -> MediaAccess.FULL
                sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.PARTIAL

                else -> MediaAccess.DENIED
            }
        } else {
            if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL
            else MediaAccess.DENIED
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

class AndroidMediaPermissionMonitor(
    checker: MediaPermissionChecker,
) : MediaPermissionMonitor {

    private val permissionChecker = checker

    private val _status = MutableStateFlow(permissionChecker.check())
    override val status: StateFlow<MediaAccess> = _status.asStateFlow()

    override fun refresh() {
        _status.value = permissionChecker.check()
    }
}

/** Permissions to request for the current platform, including selected-media access. */
fun mediaPermissionRequest(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )

    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
