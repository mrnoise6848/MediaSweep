package com.noise.mediasweep.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Host-side tests for the system trash request seam (specification §10, §46).
 *
 * Constraint 0.1 forbids running on a device or emulator, so the real confirmation UI can
 * never appear here: what is asserted is that Android 10 takes the unsupported path without
 * ever reaching the API 30 call, and that Android 11 attempts (or honestly fails) the request
 * instead of silently deleting anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidTrashRequestFactoryTest {

    private fun factory() =
        AndroidTrashRequestFactory(RuntimeEnvironment.getApplication().contentResolver)

    @Test
    @Config(sdk = [29])
    fun `android 10 has no system trash request`() {
        val factory = factory()

        assertFalse(factory.isSupported)
        assertTrue(factory.create(listOf("content://media/1")) is TrashRequest.Unsupported)
    }

    @Test
    @Config(sdk = [29])
    fun `android 10 never reaches the api 30 call even for many uris`() {
        val uris = (1L..5L).map { "content://media/$it" }

        assertTrue(factory().create(uris) is TrashRequest.Unsupported)
    }

    @Test
    fun `android 11 attempts the system confirmation and never claims success locally`() {
        val factory = factory()

        assertTrue(factory.isSupported)
        val request = factory.create(listOf("content://media/1"))

        // Robolectric cannot answer the MediaProvider call, so the result is either a real
        // confirmation payload or an honest failure — never an implicit deletion.
        assertTrue(request is TrashRequest.SystemConfirmation || request is TrashRequest.Failed)
        assertTrue(request !is TrashRequest.Unsupported)
    }

    @Test
    fun `an empty request is a failure, not a confirmation`() {
        assertTrue(factory().create(emptyList()) is TrashRequest.Failed)
    }
}
