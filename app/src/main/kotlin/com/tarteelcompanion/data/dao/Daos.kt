package com.tarteelcompanion.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.tarteelcompanion.data.entity.ImportScreenshotEntity
import com.tarteelcompanion.data.entity.OccurrenceEntity
import com.tarteelcompanion.data.entity.ReviewLogEntity
import com.tarteelcompanion.data.entity.SpotEntity
import com.tarteelcompanion.data.model.SpotState
import kotlinx.coroutines.flow.Flow

@Dao
interface SpotDao {
    @Query("SELECT * FROM spots WHERE id = :id")
    suspend fun byId(id: Long): SpotEntity?

    @Query("SELECT * FROM spots WHERE state = :state ORDER BY surah, ayah, word")
    suspend fun byState(state: SpotState): List<SpotEntity>

    @Query("SELECT * FROM spots ORDER BY surah, ayah, word")
    fun observeAll(): Flow<List<SpotEntity>>

    @Query(
        "SELECT * FROM spots WHERE state = 'ACTIVE' AND (dueEpochDay IS NULL OR dueEpochDay <= :epochDay) " +
            "ORDER BY surah, ayah, word",
    )
    suspend fun dueOn(epochDay: Long): List<SpotEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(spot: SpotEntity): Long

    @Update
    suspend fun update(spot: SpotEntity)

    @Query("DELETE FROM spots WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM occurrences WHERE spotId = :spotId ORDER BY epochDay")
    suspend fun forSpot(spotId: Long): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences WHERE spotId = :spotId AND epochDay = :epochDay")
    suspend fun forSpotOnDay(spotId: Long, epochDay: Long): OccurrenceEntity?

    @Query("SELECT * FROM occurrences WHERE spotId = :spotId ORDER BY epochDay DESC, id DESC LIMIT 1")
    suspend fun latestForSpot(spotId: Long): OccurrenceEntity?

    @Insert
    suspend fun insert(occurrence: OccurrenceEntity): Long

    @Update
    suspend fun update(occurrence: OccurrenceEntity)

    @Query("DELETE FROM occurrences WHERE spotId = :spotId")
    suspend fun deleteForSpot(spotId: Long)
}

@Dao
interface ImportDao {
    @Query("SELECT * FROM imports WHERE contentHash = :hash")
    suspend fun byHash(hash: String): ImportScreenshotEntity?

    @Query("SELECT * FROM imports WHERE id = :id")
    suspend fun byId(id: Long): ImportScreenshotEntity?

    @Query("SELECT * FROM imports ORDER BY importedAtEpochMillis DESC LIMIT 1")
    suspend fun latest(): ImportScreenshotEntity?

    @Insert
    suspend fun insert(screenshot: ImportScreenshotEntity): Long
}

@Dao
interface ReviewLogDao {
    @Query("SELECT * FROM review_log WHERE spotId = :spotId ORDER BY reviewedAtEpochMillis")
    suspend fun forSpot(spotId: Long): List<ReviewLogEntity>

    @Query(
        "SELECT * FROM review_log WHERE spotId = :spotId AND kind = 'STUDY' " +
            "ORDER BY reviewedAtEpochMillis DESC LIMIT 1",
    )
    suspend fun latestStudy(spotId: Long): ReviewLogEntity?

    @Insert
    suspend fun insert(log: ReviewLogEntity): Long

    @Query("DELETE FROM review_log WHERE spotId = :spotId")
    suspend fun deleteForSpot(spotId: Long)
}
