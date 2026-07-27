package life.mosaic.feature.training.samsung

import android.app.Activity
import android.content.Context
import java.time.Instant

internal object SamsungHealthExerciseSourceFactory {
    fun create(context: Context): SamsungHealthExerciseSource = MissingSamsungHealthExerciseSource
}

private object MissingSamsungHealthExerciseSource : SamsungHealthExerciseSource {
    override val sdkAvailable: Boolean = false
    override val unavailableReason: String =
        "Samsung Health Data SDK לא הותקן. יש להעתיק את קובץ ה-AAR הרשמי לתיקיית feature/training/libs."

    override suspend fun hasReadPermission(): Boolean = false

    override suspend fun requestReadPermission(activity: Activity): Boolean = false

    override suspend fun readExercises(
        start: Instant,
        end: Instant
    ): List<SamsungExerciseSession> = emptyList()
}
