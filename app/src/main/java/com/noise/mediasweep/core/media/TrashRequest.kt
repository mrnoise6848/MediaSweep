package com.noise.mediasweep.core.media

import android.content.ContentResolver
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/**
 * The next step of a trash operation (specification §10).
 *
 * MediaSweep never deletes media itself: every destructive change goes through the
 * system confirmation UI, and the local index is only updated after MediaStore confirms
 * the real outcome (specification §11, §46).
 */
sealed interface TrashRequest {

    /**
     * The system confirmation must be launched with this intent sender; the user's answer
     * arrives back as an activity result (RESULT_OK or cancelled).
     */
    data class SystemConfirmation(val intentSender: IntentSender) : TrashRequest

    /**
     * This platform cannot ask the system to trash media (Android 10 and lower have no
     * `MediaStore.createTrashRequest`). Nothing may be deleted in its place.
     */
    data object Unsupported : TrashRequest

    /** The system request could not be created; nothing was changed locally. */
    data object Failed : TrashRequest
}

/** Builds the system confirmation for a set of content URIs. */
interface TrashRequestFactory {

    /** True when this platform offers a system trash request at all (Android 11+). */
    val isSupported: Boolean

    /** Creates the confirmation payload; never mutates any media by itself. */
    fun create(contentUris: List<String>): TrashRequest
}

/**
 * Real `MediaStore.createTrashRequest` seam.
 *
 * The API level guard is checked before the API 30 call is reached so Android 10 devices
 * take the [TrashRequest.Unsupported] path instead of failing at runtime.
 */
class AndroidTrashRequestFactory(
    private val contentResolver: ContentResolver,
) : TrashRequestFactory {

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override fun create(contentUris: List<String>): TrashRequest {
        if (contentUris.isEmpty()) return TrashRequest.Failed
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isSupported) {
            return TrashRequest.Unsupported
        }
        return try {
            val uris = contentUris.map(Uri::parse)
            // value = true -> the system prompt sets IS_TRASHED = 1 on approval.
            val pendingIntent = MediaStore.createTrashRequest(contentResolver, uris, true)
            TrashRequest.SystemConfirmation(pendingIntent.intentSender)
        } catch (e: Exception) {
            // No access, malformed URI or an unavailable provider: report failure and
            // change nothing locally.
            TrashRequest.Failed
        }
    }
}
