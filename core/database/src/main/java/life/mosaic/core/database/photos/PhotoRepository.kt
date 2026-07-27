package life.mosaic.core.database.photos

import kotlinx.coroutines.flow.Flow

class PhotoRepository(private val photoDao: PhotoDao) {
    fun observePhotos(): Flow<List<PhotoEntity>> = photoDao.observeAll()

    suspend fun replaceMediaStoreSnapshot(photos: List<PhotoEntity>) {
        photoDao.upsertAll(photos)
        val ids = photos.map(PhotoEntity::mediaId)
        if (ids.isEmpty()) photoDao.deleteAll() else photoDao.deleteMissing(ids)
    }
}
