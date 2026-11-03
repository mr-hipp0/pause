package com.pause.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.ColumnInfo
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "protected_apps")
internal data class ProtectedAppEntity(
    @PrimaryKey val packageName: String,
    val enabled: Boolean,
    val configJson: String?,
    val categoryOverride: String? = null,
)

@Entity(tableName = "interventions")
internal data class InterventionEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val appName: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long?,
    val outcome: String?,
    val waitSeconds: Int,
    val completedAtMillis: Long?,
    @ColumnInfo(defaultValue = "'LEGACY_WAIT'") val kind: String = "LEGACY_WAIT",
    @ColumnInfo(defaultValue = "0") val allocatedSeconds: Int = 0,
    @ColumnInfo(defaultValue = "0") val usedMillis: Long = 0,
    val usageStartedAtMillis: Long? = null,
)

@Dao
internal interface ProtectionDao {
    @Query("SELECT * FROM protected_apps ORDER BY packageName")
    fun observe(): Flow<List<ProtectedAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: ProtectedAppEntity)

    @Query("DELETE FROM protected_apps WHERE packageName = :packageName")
    suspend fun remove(packageName: String)

    @Query("DELETE FROM protected_apps")
    suspend fun clear()
}

@Dao
internal interface InterventionDao {
    @Query("SELECT * FROM interventions ORDER BY startedAtMillis DESC")
    fun observe(): Flow<List<InterventionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: InterventionEntity)

    @Query("DELETE FROM interventions")
    suspend fun clear()
}

/** Keeps historic waiting records separate from observed app-use sessions. */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE interventions ADD COLUMN kind TEXT NOT NULL DEFAULT 'LEGACY_WAIT'")
        db.execSQL("ALTER TABLE interventions ADD COLUMN allocatedSeconds INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE interventions ADD COLUMN usedMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE interventions ADD COLUMN usageStartedAtMillis INTEGER")
    }
}

@Database(entities = [ProtectedAppEntity::class, InterventionEntity::class], version = 2, exportSchema = true)
internal abstract class PauseDatabase : RoomDatabase() {
    abstract fun protectionDao(): ProtectionDao
    abstract fun interventionDao(): InterventionDao
}
