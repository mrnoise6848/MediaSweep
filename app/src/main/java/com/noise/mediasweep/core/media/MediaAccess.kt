package com.noise.mediasweep.core.media

/** Result of checking the current media access grant. */
enum class MediaAccess {
    /** Every photo and video is visible. */
    FULL,

    /** The user granted access to selected media only; the library is partial. */
    PARTIAL,

    /** No usable media access. */
    DENIED,
    ;

    val isGranted: Boolean get() = this != DENIED
}
