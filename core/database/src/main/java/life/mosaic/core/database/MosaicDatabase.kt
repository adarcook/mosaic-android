package life.mosaic.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import life.mosaic.core.database.photos.PhotoDao
import life.mosaic.core.database.photos.PhotoEntity

@Database(
    entities = [PhotoEntity::class],
    version = 1,
    exportSchema = true
)
abstract class MosaicDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: MosaicDatabase? = null

        fun getInstance(context: Context): MosaicDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MosaicDatabase::class.java,
                    "mosaic.db"
                ).build().also { instance = it }
            }
    }
}
