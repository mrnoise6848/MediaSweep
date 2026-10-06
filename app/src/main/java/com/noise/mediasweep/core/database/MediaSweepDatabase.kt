package com.noise.mediasweep.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.noise.mediasweep.core.database.dao.CandidateGroupDao
import com.noise.mediasweep.core.database.dao.FingerprintDao
import com.noise.mediasweep.core.database.dao.MediaItemDao
import com.noise.mediasweep.core.database.dao.ScanSessionDao
import com.noise.mediasweep.core.database.dao.ScanStateDao
import com.noise.mediasweep.core.database.entity.CandidateGroupEntity
import com.noise.mediasweep.core.database.entity.CandidateGroupMemberEntity
import com.noise.mediasweep.core.database.entity.FingerprintEntity
import com.noise.mediasweep.core.database.entity.MediaItemEntity
import com.noise.mediasweep.core.database.entity.ScanSessionEntity
import com.noise.mediasweep.core.database.entity.ScanStateEntity

/**
 * Local index of the media library.
 *
 * The database is a cache: it never decides whether a file exists. MediaStore does.
 */
@Database(
    entities = [
        MediaItemEntity::class,
        FingerprintEntity::class,
        CandidateGroupEntity::class,
        CandidateGroupMemberEntity::class,
        ScanStateEntity::class,
        ScanSessionEntity::class,
    ],
    version = MediaSweepDatabase.VERSION,
    exportSchema = true,
)
abstract class MediaSweepDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun fingerprintDao(): FingerprintDao
    abstract fun candidateGroupDao(): CandidateGroupDao
    abstract fun scanStateDao(): ScanStateDao
    abstract fun scanSessionDao(): ScanSessionDao

    companion object {
        const val VERSION = 1
        const val NAME = "mediasweep.db"

        fun builder(context: Context): RoomDatabase.Builder<MediaSweepDatabase> =
            Room.databaseBuilder(context, MediaSweepDatabase::class.java, NAME)

        fun inMemory(context: Context): RoomDatabase.Builder<MediaSweepDatabase> =
            Room.inMemoryDatabaseBuilder(context, MediaSweepDatabase::class.java)
    }
}
