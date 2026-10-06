package com.noise.mediasweep.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Insert
import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.FingerprintEntity
import com.noise.mediasweep.core.database.entity.MediaItemEntity
import com.noise.mediasweep.core.database.entity.ScanSessionEntity
import com.noise.mediasweep.core.database.entity.ScanStateEntity
import kotlinx.coroutines.flow.Flow

data class MediaTotals(
    @ColumnInfo(name = "count") val count: Long,
    @ColumnInfo(name = "bytes") val bytes: Long,
)

/** Lean projection for incremental scans: only the id and the hash are needed. */
data class FingerprintValue(
    @ColumnInfo(name = "mediaId") val mediaId: Long,
    @ColumnInfo(name = "value") val value: String,
)

/** Lean projection used for cheap incremental comparison during index synchronisation. */
data class MediaItemKey(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "dateModified") val dateModified: Long,
    @ColumnInfo(name = "sizeBytes") val sizeBytes: Long,
)

data class CandidateGroupWithMembers(
    @Embedded val group: CandidateGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId")
    val members: List<CandidateGroupMemberEntity>,
)

data class MemberWithMedia(
    @Embedded val member: CandidateGroupMemberEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "id")
    val media: MediaItemEntity,
)

data class CandidateGroupWithMedia(
    @Embedded val group: CandidateGroupEntity,
    @Relation(entity = CandidateGroupMemberEntity::class, parentColumn = "id", entityColumn = "groupId")
    val members: List<MemberWithMedia>,
)

data class TypeTotals(
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "groupCount") val groupCount: Long,
    @ColumnInfo(name = "itemCount") val itemCount: Long,
    @ColumnInfo(name = "bytes") val bytes: Long,
)

@Dao
interface MediaItemDao {
    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE stale = 0 AND isTrashed = 0 ORDER BY dateModified DESC")
    fun observeActive(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE stale = 0 AND isTrashed = 0 ORDER BY dateModified DESC")
    suspend fun getActive(): List<MediaItemEntity>

    @Query(
        """
        SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes
        FROM media_items WHERE stale = 0 AND isTrashed = 0
        """,
    )
    fun observeTotals(): Flow<MediaTotals>

    @Query(
        """
        SELECT COUNT(*) AS count, COALESCE(SUM(sizeBytes), 0) AS bytes
        FROM media_items WHERE stale = 0 AND isTrashed = 0
        """,
    )
    suspend fun totals(): MediaTotals

    @Query("SELECT id, dateModified, sizeBytes FROM media_items")
    suspend fun keys(): List<MediaItemKey>

    @Query("UPDATE media_items SET stale = 1, lastSeenSessionId = NULL WHERE id IN (:ids)")
    suspend fun markStale(ids: List<Long>)

    @Query("UPDATE media_items SET stale = 1")
    suspend fun markAllStale()

    @Query("UPDATE media_items SET stale = 0, lastSeenSessionId = :sessionId WHERE id = :id")
    suspend fun markActive(id: Long, sessionId: Long)

    @Query("UPDATE media_items SET isTrashed = :trashed WHERE id IN (:ids)")
    suspend fun setTrashedChunk(ids: List<Long>, trashed: Boolean)

    /** Marks rows as trashed in bounded chunks (specification §11). */
    @Transaction
    suspend fun setTrashed(ids: List<Long>, trashed: Boolean) {
        ids.chunked(IN_CHUNK).forEach { chunk -> setTrashedChunk(chunk, trashed) }
    }

    @Query("DELETE FROM media_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM media_items")
    suspend fun deleteAll()

    /**
     * Reconciles the local index with the identifiers currently present in MediaStore.
     * Rows that disappeared become stale (never silently reused as if they still existed).
     *
     * Identifiers are applied in bounded chunks so the statement stays within SQLite's
     * bound-parameter limit on every supported Android version.
     */
    @Transaction
    suspend fun reconcile(currentIds: Collection<Long>) {
        if (currentIds.isEmpty()) {
            markAllStale()
            return
        }
        val seen = currentIds.toSet()
        keys()
            .map { it.id }
            .filterNot { it in seen }
            .chunked(IN_CHUNK)
            .forEach { chunk -> markStale(chunk) }
    }

    /** Marks rows as belonging to the given scan session (and active again), in bounded chunks. */
    @Transaction
    suspend fun markSeen(ids: List<Long>, sessionId: Long) {
        ids.chunked(IN_CHUNK).forEach { chunk -> markSeenChunk(chunk, sessionId) }
    }

    /**
     * Records that these rows were observed in the current MediaStore query.
     *
     * A row seen again is active by definition, so any stale or trashed flag left over
     * from an earlier state (for example media restored from the system trash) is cleared
     * here instead of hiding the item forever (specification §11).
     */
    @Query("UPDATE media_items SET lastSeenSessionId = :sessionId, stale = 0, isTrashed = 0 WHERE id IN (:ids)")
    suspend fun markSeenChunk(ids: List<Long>, sessionId: Long)

    companion object {
        /** Well below SQLite's bound-parameter limit on all supported API levels. */
        const val IN_CHUNK = 400
    }
}

@Dao
interface FingerprintDao {
    @Upsert
    suspend fun upsertAll(fingerprints: List<FingerprintEntity>)

    @Query("SELECT * FROM fingerprints WHERE mediaId = :mediaId AND algorithm = :algorithm")
    suspend fun get(mediaId: Long, algorithm: String): FingerprintEntity?

    @Query("SELECT * FROM fingerprints WHERE algorithm = :algorithm")
    suspend fun getAll(algorithm: String): List<FingerprintEntity>

    /**
     * Lean read used by the incremental scan: it needs `mediaId → hash` only, so the
     * entity columns (algorithm, calculatedAt) are never copied into a large list.
     */
    @Query("SELECT mediaId, value FROM fingerprints WHERE algorithm = :algorithm")
    suspend fun getValues(algorithm: String): List<FingerprintValue>

    @Query("SELECT * FROM fingerprints WHERE algorithm = :algorithm")
    fun observeAll(algorithm: String): Flow<List<FingerprintEntity>>

    @Query("DELETE FROM fingerprints WHERE mediaId IN (:mediaIds)")
    suspend fun deleteForMediaChunk(mediaIds: List<Long>)

    @Transaction
    suspend fun deleteForMedia(mediaIds: List<Long>) {
        mediaIds.chunked(MediaItemDao.IN_CHUNK).forEach { chunk -> deleteForMediaChunk(chunk) }
    }

    @Query("DELETE FROM fingerprints")
    suspend fun deleteAll()
}

@Dao
interface CandidateGroupDao {
    @Insert
    suspend fun insertGroup(group: CandidateGroupEntity): Long

    @Insert
    suspend fun insertMembers(members: List<CandidateGroupMemberEntity>)

    @Query("SELECT * FROM candidate_groups WHERE id = :groupId")
    suspend fun getGroup(groupId: Long): CandidateGroupEntity?

    @Query("SELECT * FROM candidate_groups WHERE type = :type ORDER BY totalSizeBytes DESC")
    fun observeGroups(type: String): Flow<List<CandidateGroupEntity>>

    @Query("SELECT * FROM candidate_groups WHERE type = :type ORDER BY totalSizeBytes DESC")
    suspend fun getGroups(type: String): List<CandidateGroupEntity>

    @Query("SELECT * FROM candidate_groups ORDER BY totalSizeBytes DESC")
    fun observeAllGroups(): Flow<List<CandidateGroupEntity>>

    @Transaction
    @Query("SELECT * FROM candidate_groups WHERE id = :groupId")
    fun observeGroupWithMembers(groupId: Long): Flow<CandidateGroupWithMembers?>

    @Transaction
    @Query("SELECT * FROM candidate_groups WHERE type = :type ORDER BY totalSizeBytes DESC")
    fun observeGroupsWithMedia(type: String): Flow<List<CandidateGroupWithMedia>>

    @Transaction
    @Query("SELECT * FROM candidate_groups WHERE id = :groupId")
    fun observeGroupWithMedia(groupId: Long): Flow<CandidateGroupWithMedia?>

    @Transaction
    @Query("SELECT * FROM candidate_groups WHERE id = :groupId")
    suspend fun getGroupWithMedia(groupId: Long): CandidateGroupWithMedia?

    @Transaction
    @Query("SELECT * FROM candidate_groups WHERE id = :groupId")
    suspend fun getGroupWithMembers(groupId: Long): CandidateGroupWithMembers?

    @Query(
        """
        SELECT g.type AS type,
               COUNT(*) AS groupCount,
               COALESCE(SUM(m.itemCount), 0) AS itemCount,
               COALESCE(SUM(g.totalSizeBytes), 0) AS bytes
        FROM candidate_groups g
        LEFT JOIN (
            SELECT groupId, COUNT(*) AS itemCount
            FROM candidate_group_members GROUP BY groupId
        ) m ON m.groupId = g.id
        GROUP BY g.type
        """,
    )
    fun observeTypeTotals(): Flow<List<TypeTotals>>

    @Query(
        """
        SELECT g.type AS type,
               COUNT(*) AS groupCount,
               COALESCE(SUM(m.itemCount), 0) AS itemCount,
               COALESCE(SUM(g.totalSizeBytes), 0) AS bytes
        FROM candidate_groups g
        LEFT JOIN (
            SELECT groupId, COUNT(*) AS itemCount
            FROM candidate_group_members GROUP BY groupId
        ) m ON m.groupId = g.id
        GROUP BY g.type
        """,
    )
    suspend fun typeTotals(): List<TypeTotals>

    @Query("SELECT COUNT(*) FROM candidate_groups WHERE type = :type")
    fun observeGroupCount(type: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM candidate_group_members WHERE mediaId = :mediaId")
    suspend fun groupMembershipCount(mediaId: Long): Int

    @Query("DELETE FROM candidate_groups WHERE type = :type")
    suspend fun deleteByType(type: String)

    @Query("DELETE FROM candidate_groups WHERE id IN (SELECT groupId FROM candidate_group_members WHERE mediaId IN (:mediaIds))")
    suspend fun deleteGroupsContainingChunk(mediaIds: List<Long>)

    /** Drops groups whose membership is no longer accurate (member changed or disappeared). */
    @Transaction
    suspend fun deleteGroupsContaining(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        mediaIds.toList().chunked(MediaItemDao.IN_CHUNK).forEach { chunk ->
            deleteGroupsContainingChunk(chunk)
        }
    }

    @Query("DELETE FROM candidate_group_members WHERE mediaId IN (:mediaIds)")
    suspend fun removeMembersChunk(mediaIds: List<Long>)

    @Query("DELETE FROM candidate_groups WHERE id NOT IN (SELECT groupId FROM candidate_group_members)")
    suspend fun deleteGroupsWithoutMembers()

    @Query(
        """
        DELETE FROM candidate_groups
        WHERE type IN (:types)
          AND (SELECT COUNT(*) FROM candidate_group_members WHERE groupId = candidate_groups.id) < 2
        """,
    )
    suspend fun deleteSingleMemberGroups(types: List<String>)

    @Query(
        """
        UPDATE candidate_groups SET
            itemCount = (SELECT COUNT(*) FROM candidate_group_members WHERE groupId = candidate_groups.id),
            totalSizeBytes = COALESCE((
                SELECT SUM(mi.sizeBytes)
                FROM candidate_group_members mm
                INNER JOIN media_items mi ON mi.id = mm.mediaId
                WHERE mm.groupId = candidate_groups.id
            ), 0)
        """,
    )
    suspend fun recountGroups()

    /**
     * Removes [mediaIds] from every group once MediaStore confirmed they are gone
     * (specification §11: trashed items leave the active candidate lists).
     *
     * Surviving members keep their group with corrected counts and size; groups left with
     * no members vanish, and duplicate groups left with a single copy stop being a
     * duplicate candidate. Types in [duplicateTypes] are the ones that require at least
     * two members to still be meaningful.
     */
    @Transaction
    suspend fun removeMediaFromGroups(mediaIds: Collection<Long>, duplicateTypes: List<String>) {
        if (mediaIds.isEmpty()) return
        mediaIds.toList().chunked(MediaItemDao.IN_CHUNK).forEach { chunk ->
            removeMembersChunk(chunk)
        }
        deleteGroupsWithoutMembers()
        if (duplicateTypes.isNotEmpty()) deleteSingleMemberGroups(duplicateTypes)
        recountGroups()
    }

    @Query("DELETE FROM candidate_groups")
    suspend fun deleteAll()
}

@Dao
interface ScanStateDao {
    @Upsert
    suspend fun upsert(state: ScanStateEntity)

    @Query("SELECT * FROM scan_state WHERE id = 1")
    suspend fun get(): ScanStateEntity?

    @Query("SELECT * FROM scan_state WHERE id = 1")
    fun observe(): Flow<ScanStateEntity?>
}

@Dao
interface ScanSessionDao {
    @Insert
    suspend fun insert(session: ScanSessionEntity): Long

    @Upsert
    suspend fun update(session: ScanSessionEntity)

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getById(id: Long): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun latest(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE status = 'SCANNING' ORDER BY startedAt DESC")
    suspend fun unfinished(): List<ScanSessionEntity>

    @Query("UPDATE scan_sessions SET processedCount = :processed, totalCount = :total WHERE id = :id")
    suspend fun updateProgress(id: Long, processed: Long, total: Long)

    @Query(
        """
        UPDATE scan_sessions
        SET finishedAt = :finishedAt, status = :status, processedCount = :processed, errorMessage = :error
        WHERE id = :id
        """,
    )
    suspend fun finish(id: Long, finishedAt: Long, status: String, processed: Long, error: String?)

    /** Any session still marked as running after a restart was interrupted, not completed. */
    @Query("UPDATE scan_sessions SET status = 'FAILED', finishedAt = :now, errorMessage = 'Interrupted' WHERE status = 'SCANNING'")
    suspend fun markInterrupted(now: Long): Int

    @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<ScanSessionEntity>>
}
