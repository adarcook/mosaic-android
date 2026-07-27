package life.mosaic.core.database.photos

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey val mediaId: Long,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAddedEpochSeconds: Long,
    val automaticCategory: String,
    val userDecision: String? = null,
    val importanceScore: Double = 0.5,
    val classificationReasons: String = "",
    val imageEmbeddingStatus: String = "NOT_STARTED",
    val faceEmbeddingStatus: String = "NOT_STARTED",
    val syncStatus: String = "NOT_READY",
    val scannedAtEpochMillis: Long
)
