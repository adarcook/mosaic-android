package life.mosaic.core.database.photos

import kotlinx.coroutines.flow.Flow

class PhotoRepository(private val photoDao: PhotoDao) {
    fun observePhotos(): Flow<List<PhotoEntity>> = photoDao.observeAll()

    /**
     * Applies a complete MediaStore snapshot while preserving user decisions and
     * future processing state already stored locally. Only new or changed rows
     * are written back to Room.
     */
    suspend fun replaceMediaStoreSnapshot(scannedPhotos: List<PhotoEntity>) {
        val existingById = photoDao.getAll().associateBy(PhotoEntity::mediaId)

        val changedOrNew = scannedPhotos.mapNotNull { scanned ->
            val existing = existingById[scanned.mediaId]
            if (existing == null) {
                scanned
            } else {
                val merged = scanned.copy(
                    userDecision = existing.userDecision,
                    importanceScore = existing.importanceScore,
                    classificationReasons = scanned.classificationReasons,
                    imageEmbeddingStatus = existing.imageEmbeddingStatus,
                    faceEmbeddingStatus = existing.faceEmbeddingStatus,
                    syncStatus = existing.syncStatus,
                    scannedAtEpochMillis = scanned.scannedAtEpochMillis
                )

                if (existing.sameMediaSnapshotAs(merged)) null else merged
            }
        }

        if (changedOrNew.isNotEmpty()) photoDao.upsertAll(changedOrNew)

        val activeIds = scannedPhotos.map(PhotoEntity::mediaId)
        if (activeIds.isEmpty()) photoDao.deleteAll() else photoDao.deleteMissing(activeIds)
    }
}

private fun PhotoEntity.sameMediaSnapshotAs(other: PhotoEntity): Boolean =
    contentUri == other.contentUri &&
        displayName == other.displayName &&
        relativePath == other.relativePath &&
        mimeType == other.mimeType &&
        width == other.width &&
        height == other.height &&
        sizeBytes == other.sizeBytes &&
        dateAddedEpochSeconds == other.dateAddedEpochSeconds &&
        automaticCategory == other.automaticCategory &&
        userDecision == other.userDecision &&
        importanceScore == other.importanceScore &&
        classificationReasons == other.classificationReasons &&
        imageEmbeddingStatus == other.imageEmbeddingStatus &&
        faceEmbeddingStatus == other.faceEmbeddingStatus &&
        syncStatus == other.syncStatus
