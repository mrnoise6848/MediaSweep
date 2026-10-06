package com.noise.mediasweep.core.media

import android.Manifest
import android.app.Application
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaPermissionTest {

    private lateinit var application: Application
    private lateinit var checker: AndroidMediaPermissionChecker

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        checker = AndroidMediaPermissionChecker(application)
    }

    private fun grant(vararg permissions: String) {
        shadowOf(application).grantPermissions(*permissions)
    }

    private fun deny(vararg permissions: String) {
        shadowOf(application).denyPermissions(*permissions)
    }

    @Test
    fun `no grant at all is denied`() {
        deny(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        assertEquals(MediaAccess.DENIED, checker.check())
    }

    @Test
    fun `full access to images and video is FULL`() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        assertEquals(MediaAccess.FULL, checker.check())
    }

    @Test
    fun `selected media access only is PARTIAL`() {
        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(MediaAccess.PARTIAL, checker.check())
    }

    @Test
    fun `images granted but video denied with selected access is PARTIAL`() {
        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        assertEquals(MediaAccess.PARTIAL, checker.check())
    }

    @Test
    fun `partial access is not reported as a full scan of the library`() {
        grant(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val access = checker.check()
        assertEquals(MediaAccess.PARTIAL, access)
        assertEquals(true, access.isGranted)
        assertEquals(false, MediaAccess.DENIED.isGranted)
    }

    @Test
    fun `monitor exposes and refreshes the real grant`() {
        val monitor = AndroidMediaPermissionMonitor(checker)
        assertEquals(MediaAccess.DENIED, monitor.status.value)

        grant(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        // Nothing changes until the grant is actually re-read.
        assertEquals(MediaAccess.DENIED, monitor.status.value)

        monitor.refresh()
        assertEquals(MediaAccess.FULL, monitor.status.value)
    }

    @Test
    fun `permission request includes selected-media access on modern platforms`() {
        val request = mediaPermissionRequest().toList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertEquals(
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ),
                request,
            )
        } else {
            assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO), request)
        }
        assertEquals(true, request.isNotEmpty())
    }
}
