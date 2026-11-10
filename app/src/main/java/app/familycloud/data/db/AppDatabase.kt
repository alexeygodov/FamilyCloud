package app.familycloud.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(primaryKeys = ["pairId", "mediaStoreId"])
data class UploadedEntity(
    val pairId: Long,
    val mediaStoreId: Long,
    val remotePath: String,
    val size: Long,
    val dateModified: Long,
    val partPath: String?,
)

@Entity
data class SyncPairEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val albumName: String,
    val bucketId: String,
    val remoteFolder: String,
    val includePhotos: Boolean = true,
    val includeVideos: Boolean = true,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val enabled: Boolean = true,
    val onlyNewSinceEpochMs: Long? = null,
    val lastSyncEpochMs: Long? = null,
    val lastError: String? = null,
)

@Entity
data class OrphanPartEntity(
    @androidx.room.PrimaryKey val remotePath: String,
)

@Entity
data class PendingUploadEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val displayName: String,
    val size: Long,
    val destinationFolder: String,
)

@Dao
interface UploadedDao {
    @Query("SELECT * FROM UploadedEntity WHERE pairId = :pairId")
    suspend fun forPair(pairId: Long): List<UploadedEntity>

    @Query("SELECT * FROM UploadedEntity WHERE partPath IS NOT NULL")
    suspend fun withParts(): List<UploadedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UploadedEntity)

    @Query("DELETE FROM UploadedEntity WHERE pairId = :pairId AND mediaStoreId = :mediaStoreId")
    suspend fun deleteOne(pairId: Long, mediaStoreId: Long)
}

@Dao
interface SyncPairDao {
    @Query("SELECT * FROM SyncPairEntity ORDER BY albumName")
    suspend fun all(): List<SyncPairEntity>

    @Query("SELECT * FROM SyncPairEntity WHERE id = :id")
    suspend fun byId(id: Long): SyncPairEntity?

    @Insert
    suspend fun insert(entity: SyncPairEntity): Long

    @androidx.room.Update
    suspend fun update(entity: SyncPairEntity)

    @Query("DELETE FROM SyncPairEntity WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM UploadedEntity WHERE pairId = :pairId")
    suspend fun deleteUploads(pairId: Long)
}

@Dao
interface PendingUploadDao {
    @Query("SELECT * FROM PendingUploadEntity ORDER BY id LIMIT 1")
    suspend fun next(): PendingUploadEntity?

    @Query("SELECT COUNT(*) FROM PendingUploadEntity")
    suspend fun count(): Int

    @Insert
    suspend fun insert(items: List<PendingUploadEntity>)

    @Query("DELETE FROM PendingUploadEntity WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OrphanPartDao {
    @Query("SELECT * FROM OrphanPartEntity")
    suspend fun all(): List<OrphanPartEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OrphanPartEntity)

    @Query("DELETE FROM OrphanPartEntity WHERE remotePath = :remotePath")
    suspend fun delete(remotePath: String)
}

@Database(
    entities = [UploadedEntity::class, SyncPairEntity::class, PendingUploadEntity::class, OrphanPartEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadedDao(): UploadedDao
    abstract fun syncPairDao(): SyncPairDao
    abstract fun pendingUploadDao(): PendingUploadDao
    abstract fun orphanPartDao(): OrphanPartDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `OrphanPartEntity` (`remotePath` TEXT NOT NULL, PRIMARY KEY(`remotePath`))",
                )
            }
        }
    }
}
