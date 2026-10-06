package com.noise.mediasweep.data.repository

import com.noise.mediasweep.core.database.MediaSweepDatabase
import com.noise.mediasweep.domain.model.ScanStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanSessionStoreTest {

    private lateinit var database: MediaSweepDatabase
    private lateinit var store: ScanSessionStore
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = MediaSweepDatabase.inMemory(RuntimeEnvironment.getApplication())
            .allowMainThreadQueries()
            .build()
        store = ScanSessionStore(
            sessionDao = database.scanSessionDao(),
            scanStateDao = database.scanStateDao(),
            clock = { now },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a fresh install has not been scanned`() = runBlocking {
        assertNull(store.latestSession())
        assertEquals(ScanStatus.NOT_SCANNED, database.scanStateDao().get()?.let { ScanStatus.valueOf(it.status) } ?: ScanStatus.NOT_SCANNED)
    }

    @Test
    fun `progress is persisted while scanning`() = runBlocking {
        val id = store.startSession(partialAccess = false, totalCount = 100L)
        store.updateProgress(id, processed = 40L, total = 100L)

        val session = store.latestSession()
        assertNotNull(session)
        assertEquals(ScanStatus.SCANNING, ScanStatus.valueOf(session!!.status))
        assertEquals(40L, session.processedCount)
        assertEquals(100L, session.totalCount)
        assertNull(session.finishedAt)
        // A running scan is published as SCANNING — never as complete.
        assertEquals(ScanStatus.SCANNING, statusOf(store))
    }

    @Test
    fun `a completed full scan records COMPLETE state`() = runBlocking {
        val id = store.startSession(partialAccess = false, totalCount = 10L)
        now += 5_000
        store.complete(id, mediaCount = 10L, mediaBytes = 12_345L, partialAccess = false)

        val state = database.scanStateDao().get()
        assertNotNull(state)
        assertEquals("COMPLETE", state!!.status)
        assertEquals(now, state.lastSuccessfulScanAt)
        assertEquals(10L, state.lastMediaCount)
        assertEquals(12_345L, state.lastMediaBytes)
        assertEquals(false, state.partialAccess)
        assertEquals(now, store.latestSession()?.finishedAt)
    }

    @Test
    fun `selected media access completes as PARTIAL, never as a full scan`() = runBlocking {
        val id = store.startSession(partialAccess = true, totalCount = 4L)
        store.complete(id, mediaCount = 4L, mediaBytes = 99L, partialAccess = true)

        assertEquals("PARTIAL", database.scanStateDao().get()!!.status)
        assertTrue(database.scanStateDao().get()!!.partialAccess)
    }

    @Test
    fun `a failed scan records the failure without claiming completion`() = runBlocking {
        val id = store.startSession(partialAccess = false, totalCount = 10L)
        store.updateProgress(id, processed = 3L, total = 10L)
        store.fail(id, "provider error")

        val session = store.latestSession()
        assertEquals("FAILED", session!!.status)
        assertEquals("provider error", session.errorMessage)
        assertNotNull(session.finishedAt)
        // No completion claim — but the UI must not stay stuck on SCANNING either.
        assertEquals(ScanStatus.FAILED, statusOf(store))
    }

    @Test
    fun `an interrupted session survives process death as interrupted, not complete`() = runBlocking {
        store.startSession(partialAccess = false, totalCount = 10L)

        // Simulate the process dying and a new AppContainer starting up.
        val recovered = store.recoverInterruptedSessions()

        assertEquals(1, recovered)
        val session = store.latestSession()
        assertEquals("FAILED", session!!.status)
        assertEquals("Interrupted", session.errorMessage)
        assertNotNull(session.finishedAt)
        // The library is still reported as never successfully scanned — never SCANNING.
        assertEquals(ScanStatus.NOT_SCANNED, statusOf(store))
    }

    @Test
    fun `recovery only affects sessions that were actually running`() = runBlocking {
        val finished = store.startSession(partialAccess = false, totalCount = 1L)
        store.complete(finished, mediaCount = 1L, mediaBytes = 1L, partialAccess = false)
        now += 1_000
        val interrupted = store.startSession(partialAccess = false, totalCount = 1L)

        assertEquals(1, store.recoverInterruptedSessions())

        val latest = store.latestSession()
        assertEquals(interrupted, latest!!.id)
        assertEquals("FAILED", latest.status)
        // The completed scan's numbers survive; the interrupted run downgrades the state
        // to STALE (results exist, but the index was mid-update when the process died).
        val state = database.scanStateDao().get()!!
        assertEquals("STALE", state.status)
        assertEquals(finished, state.lastSessionId)
        assertEquals(1L, state.lastMediaCount)
    }

    @Test
    fun `scan state survives being read through a fresh store instance`() = runBlocking {
        val id = store.startSession(partialAccess = false, totalCount = 1L)
        store.complete(id, mediaCount = 1L, mediaBytes = 1L, partialAccess = false)

        // Same database, new instances: equivalent to process recreation.
        val recreated = ScanSessionStore(database.scanSessionDao(), database.scanStateDao()) { now }
        assertEquals(ScanStatus.COMPLETE, statusOf(recreated))
        assertEquals(id, recreated.latestSession()?.id)
    }

    private suspend fun statusOf(store: ScanSessionStore): ScanStatus {
        val state = database.scanStateDao().get() ?: return ScanStatus.NOT_SCANNED
        return ScanStatus.valueOf(state.status)
    }
}
