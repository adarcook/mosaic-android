package life.mosaic.core.data.photos

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import life.mosaic.core.database.photos.PhotoDao
import life.mosaic.core.database.photos.PhotoEntity
import life.mosaic.core.database.photos.photoDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PhotoRepository(
    private val context: Context,
    private val dao: PhotoDao = photoDao(context)
) {
    fun observePhotos(): Flow<List<PhotoEntity>> = dao.observeAll()

    suspend fun refresh() {
        val scanned = scanDevicePhotos(context)
        val existing = dao.getAll().associateBy(PhotoEntity::mediaId)
        val merged = scanned.map { fresh ->
            existing[fresh.mediaId]?.let { old ->
                fresh.copy(
                    userDecision = old.userDecision,
                    imageEmbeddingStatus = old.imageEmbeddingStatus,
                    faceEmbeddingStatus = old.faceEmbeddingStatus,
                    syncStatus = old.syncStatus
                )
            } ?: fresh
        }
        dao.upsertAll(merged)
        val ids = merged.map(PhotoEntity::mediaId)
        if (ids.isEmpty()) dao.deleteAll() else dao.deleteMissing(ids)
    }
}

private suspend fun scanDevicePhotos(context: Context): List<PhotoEntity> = withContext(Dispatchers.IO) {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = mutableListOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
        else add(MediaStore.Images.Media.DATA)
    }.toTypedArray()

    val result = mutableListOf<PhotoEntity>()
    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        }

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val name = cursor.getString(nameColumn).orEmpty()
            val path = cursor.getString(pathColumn).orEmpty()
            val category = classifyPhoto(name, path)
            result += PhotoEntity(
                mediaId = id,
                contentUri = ContentUris.withAppendedId(collection, id).toString(),
                displayName = name,
                relativePath = path,
                mimeType = cursor.getString(mimeColumn).orEmpty(),
                width = cursor.getInt(widthColumn),
                height = cursor.getInt(heightColumn),
                sizeBytes = cursor.getLong(sizeColumn),
                dateAddedEpochSeconds = cursor.getLong(dateColumn),
                automaticCategory = category,
                importanceScore = if (category == CLEANUP) 0.2 else 0.7,
                classificationReasons = if (category == CLEANUP) "folder-or-name-cleanup-signal" else "default-important-candidate",
                scannedAtEpochMillis = System.currentTimeMillis()
            )
        }
    }
    result
}

private const val IMPORTANT = "IMPORTANT_CANDIDATE"
private const val CLEANUP = "CLEANUP_CANDIDATE"

private fun classifyPhoto(displayName: String, relativePath: String): String {
    val text = "$displayName $relativePath".lowercase()
    val cleanupSignals = listOf(
        "screenshot", "screenshots", "screen_record", "screenrecord",
        "download", "downloads", "whatsapp images/sent", "telegram/telegram images"
    )
    return if (cleanupSignals.any(text::contains)) CLEANUP else IMPORTANT
}
