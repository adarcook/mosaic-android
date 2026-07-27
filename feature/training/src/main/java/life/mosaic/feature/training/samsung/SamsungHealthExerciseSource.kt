package life.mosaic.feature.training.samsung

import android.app.Activity
import android.content.Context
import java.time.Instant

data class SamsungExerciseSession(
    val id: String,
    val title: String,
    val exerciseType: String,
    val startTime: Instant,
    val endTime: Instant,
    val durationMinutes: Long,
    val distanceMeters: Float?,
    val caloriesKcal: Float?,
    val sourceDeviceId: String?
)

interface SamsungHealthExerciseSource {
    val sdkAvailable: Boolean
    val unavailableReason: String?

    suspend fun hasReadPermission(): Boolean
    suspend fun requestReadPermission(activity: Activity): Boolean
    suspend fun readExercises(start: Instant, end: Instant): List<SamsungExerciseSession>
}

fun createSamsungHealthExerciseSource(context: Context): SamsungHealthExerciseSource =
    createPlatformSamsungHealthExerciseSource(context.applicationContext)

internal fun createPlatformSamsungHealthExerciseSource(context: Context): SamsungHealthExerciseSource
