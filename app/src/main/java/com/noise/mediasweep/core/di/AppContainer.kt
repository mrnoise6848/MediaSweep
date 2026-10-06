package com.noise.mediasweep.core.di

import android.content.Context
import com.noise.mediasweep.core.database.MediaSweepDatabase
import com.noise.mediasweep.core.media.AndroidMediaPermissionChecker
import com.noise.mediasweep.core.media.AndroidMediaPermissionMonitor
import com.noise.mediasweep.core.media.AndroidTrashRequestFactory
import com.noise.mediasweep.core.media.MediaPermissionMonitor
import com.noise.mediasweep.core.media.TrashRequestFactory
import com.noise.mediasweep.data.media.AndroidMediaStoreDataSource
import com.noise.mediasweep.data.media.ContentResolverMediaStoreQuery
import com.noise.mediasweep.data.media.ContentResolverStreamHasher
import com.noise.mediasweep.data.media.MediaStoreDataSource
import com.noise.mediasweep.data.repository.MediaIndexSynchronizer
import com.noise.mediasweep.data.repository.MediaStoreTrashReconciler
import com.noise.mediasweep.data.repository.RoomCandidateRepository
import com.noise.mediasweep.data.repository.RoomMediaIndexRepository
import com.noise.mediasweep.data.repository.RoomScanStateRepository
import com.noise.mediasweep.data.repository.ScanController
import com.noise.mediasweep.data.repository.ScanPipeline
import com.noise.mediasweep.data.repository.ScanSessionStore
import com.noise.mediasweep.domain.repository.CandidateRepository
import com.noise.mediasweep.domain.repository.MediaIndexRepository
import com.noise.mediasweep.domain.repository.ScanStateRepository
import com.noise.mediasweep.domain.repository.TrashReconciler
import com.noise.mediasweep.domain.usecase.ObserveStorageSummaryUseCase
import com.noise.mediasweep.feature.review.ReviewSelectionStore
import com.noise.mediasweep.scanner.classification.CandidateClassifier
import com.noise.mediasweep.scanner.classification.ExactDuplicateAnalyzer
import com.noise.mediasweep.scanner.classification.NearDuplicateAnalyzer
import com.noise.mediasweep.scanner.image.AndroidReducedImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manual dependency container.
 *
 * Hilt was deliberately not used: it adds a compiler plugin with marginal benefit for a
 * single-module MVP, and the container below keeps the same dependency direction
 * (UI -> ViewModel -> UseCase -> Repository -> Room/MediaStore) with no reflection.
 * See docs/decisions/001.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: MediaSweepDatabase = MediaSweepDatabase.builder(appContext).build()

    val scanStateRepository: ScanStateRepository =
        RoomScanStateRepository(database.scanStateDao(), database.scanSessionDao())

    val mediaIndexRepository: MediaIndexRepository =
        RoomMediaIndexRepository(database.mediaItemDao())

    val candidateRepository: CandidateRepository =
        RoomCandidateRepository(database.candidateGroupDao())

    /**
     * Review selection survives navigation between the category, group detail and review
     * screens; it is application-scoped state, not screen state.
     */
    val reviewSelection: ReviewSelectionStore = ReviewSelectionStore()

    val observeStorageSummary: ObserveStorageSummaryUseCase =
        ObserveStorageSummaryUseCase(scanStateRepository, mediaIndexRepository, candidateRepository)

    val mediaStoreDataSource: MediaStoreDataSource = AndroidMediaStoreDataSource(
        ContentResolverMediaStoreQuery(appContext.contentResolver),
    )

    val permissionMonitor: MediaPermissionMonitor =
        AndroidMediaPermissionMonitor(AndroidMediaPermissionChecker(appContext))

    /** System trash confirmation builder (Android 11+; see core/media/TrashRequest.kt). */
    val trashRequestFactory: TrashRequestFactory =
        AndroidTrashRequestFactory(appContext.contentResolver)

    /** Re-queries MediaStore after a trash confirmation and aligns the local index. */
    val trashReconciler: TrashReconciler = MediaStoreTrashReconciler(
        mediaStore = mediaStoreDataSource,
        mediaItemDao = database.mediaItemDao(),
        candidateGroupDao = database.candidateGroupDao(),
    )

    val scanSessionStore: ScanSessionStore = ScanSessionStore(
        sessionDao = database.scanSessionDao(),
        scanStateDao = database.scanStateDao(),
    )

    val mediaIndexSynchronizer: MediaIndexSynchronizer = MediaIndexSynchronizer(
        mediaStore = mediaStoreDataSource,
        mediaItemDao = database.mediaItemDao(),
        fingerprintDao = database.fingerprintDao(),
        candidateGroupDao = database.candidateGroupDao(),
    )

    val scanPipeline: ScanPipeline = ScanPipeline(
        mediaStore = mediaStoreDataSource,
        synchronizer = mediaIndexSynchronizer,
        sessionStore = scanSessionStore,
        mediaItemDao = database.mediaItemDao(),
        fingerprintDao = database.fingerprintDao(),
        candidateGroupDao = database.candidateGroupDao(),
        exactDuplicateAnalyzer = ExactDuplicateAnalyzer(ContentResolverStreamHasher(appContext.contentResolver)),
        nearDuplicateAnalyzer = NearDuplicateAnalyzer(
            decoder = AndroidReducedImageDecoder(appContext.contentResolver),
        ),
        candidateClassifier = CandidateClassifier(),
    )

    /**
     * Application-scoped scan runner (specification §18, §19): one run at a time, started
     * from the scan screen, cancellable cooperatively and observable from any screen, so
     * leaving the screen never silently kills a scan.
     */
    val scanController: ScanController = ScanController(applicationScope, scanPipeline)

    init {
        // A scan interrupted by process death must never be reported as completed.
        applicationScope.launch {
            try {
                scanSessionStore.recoverInterruptedSessions()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Recovery is best effort; the next scan re-establishes the real state.
            }
        }
    }

    fun shutdown() {
        database.close()
        applicationScope.cancel()
    }
}
