package life.mosaic.feature.training.samsung

import android.app.Activity
import android.content.Context
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.permission.AccessType
import com.samsung.android.sdk.health.data.permission.Permission
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

internal object SamsungHealthExerciseSourceFactory {
    fun create(context: Context): SamsungHealthExerciseSource =
        SamsungHealthDataExerciseSource(HealthDataService.getStore(context))
}

private class SamsungHealthDataExerciseSource(
    private val store: HealthDataStore
) : SamsungHealthExerciseSource {
    private val readPermission = Permission.of(DataTypes.EXERCISE, AccessType.READ)
    private val requiredPermissions = setOf(readPermission)

    override val sdkAvailable: Boolean = true
    override val unavailableReason: String? = null

    override suspend fun hasReadPermission(): Boolean =
        store.getGrantedPermissions(requiredPermissions).containsAll(requiredPermissions)

    override suspend fun requestReadPermission(activity: Activity): Boolean =
        store.requestPermissions(requiredPermissions, activity).containsAll(requiredPermissions)

    override suspend fun readExercises(
        start: Instant,
        end: Instant
    ): List<SamsungExerciseSession> {
        check(hasReadPermission()) { "Samsung Health exercise read permission is missing" }

        val zone = ZoneId.systemDefault()
        val filter = LocalTimeFilter.of(
            LocalDateTime.ofInstant(start, zone),
            LocalDateTime.ofInstant(end, zone)
        )
        val request = DataTypes.EXERCISE.readDataRequestBuilder
            .setLocalTimeFilter(filter)
            .setOrdering(Ordering.DESC)
            .build()

        return store.readData(request).dataList.flatMap { dataPoint ->
            val sessions = dataPoint.getValue(DataType.ExerciseType.SESSIONS).orEmpty()
            sessions.mapIndexed { index, session ->
                SamsungExerciseSession(
                    id = buildString {
                        append(dataPoint.startTime.toEpochMilli())
                        append('-')
                        append(index)
                        append('-')
                        append(session.exerciseType.name)
                    },
                    title = session.customTitle?.takeIf { it.isNotBlank() }
                        ?: session.exerciseType.name.replace('_', ' '),
                    exerciseType = session.exerciseType.name,
                    startTime = session.startTime,
                    endTime = session.endTime,
                    durationMinutes = session.duration.toMinutes(),
                    distanceMeters = session.distance,
                    caloriesKcal = session.calories,
                    sourceDeviceId = dataPoint.dataSource?.deviceId
                )
            }
        }
    }
}
