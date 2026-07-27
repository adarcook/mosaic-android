package life.mosaic.core.database.photos

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos ORDER BY dateAddedEpochSeconds DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos ORDER BY dateAddedEpochSeconds DESC")
    suspend fun getAll(): List<PhotoEntity>

    @Upsert
    suspend fun upsertAll(photos: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE mediaId NOT IN (:activeMediaIds)")
    suspend fun deleteMissing(activeMediaIds: List<Long>)

    @Query("DELETE FROM photos")
    suspend fun deleteAll()
}
