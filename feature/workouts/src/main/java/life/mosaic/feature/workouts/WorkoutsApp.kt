package life.mosaic.feature.workouts

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val requiredPermissions = setOf(
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class)
)

data class WorkoutSummary(
    val id: String,
    val title: String,
    val startTime: Instant,
    val durationMinutes: Long,
    val averageHeartRate: Long?,
    val sourcePackage: String
)

class HealthConnectWorkoutRepository(private val context: Context) {
    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(requiredPermissions)

    suspend fun readRecentWorkouts(days: Long = 30): List<WorkoutSummary> {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(days))
        val healthClient = client()
        val sessions = healthClient.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                ascendingOrder = false
            )
        ).records

        return sessions.map { session ->
            val heartRates = healthClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
                )
            ).records.flatMap { it.samples }
            WorkoutSummary(
                id = session.metadata.id,
                title = session.title?.takeIf { it.isNotBlank() } ?: "אימון מהשעון",
                startTime = session.startTime,
                durationMinutes = Duration.between(session.startTime, session.endTime).toMinutes(),
                averageHeartRate = heartRates.map { it.beatsPerMinute }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
                sourcePackage = session.metadata.dataOrigin.packageName
            )
        }
    }
}

@Composable
fun WorkoutsApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { HealthConnectWorkoutRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var permissionGranted by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var workouts by remember { mutableStateOf(emptyList<WorkoutSummary>()) }

    fun refresh() {
        scope.launch {
            loading = true
            message = null
            runCatching {
                permissionGranted = repository.hasPermissions()
                if (permissionGranted) repository.readRecentWorkouts() else emptyList()
            }.onSuccess {
                workouts = it
                if (permissionGranted && it.isEmpty()) message = "לא נמצאו אימונים ב־30 הימים האחרונים"
            }.onFailure { message = "קריאת האימונים נכשלה: ${it.message}" }
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        permissionGranted = granted.containsAll(requiredPermissions)
        refresh()
    }

    LaunchedEffect(Unit) {
        if (repository.availability() == HealthConnectClient.SDK_AVAILABLE) refresh()
    }

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("אימונים", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("נתונים מ־Health Connect ומהשעון החכם", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))

                when (repository.availability()) {
                    HealthConnectClient.SDK_UNAVAILABLE -> Text("Health Connect אינו זמין במכשיר הזה")
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Text("נדרש להתקין או לעדכן את Health Connect")
                    else -> {
                        if (!permissionGranted) {
                            Button(onClick = { permissionLauncher.launch(requiredPermissions) }) {
                                Text("חיבור ל־Health Connect")
                            }
                        } else {
                            Button(onClick = ::refresh, enabled = !loading) {
                                Text(if (loading) "טוען…" else "רענון אימונים")
                            }
                        }
                    }
                }

                message?.let {
                    Spacer(Modifier.height(14.dp))
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(workouts, key = { it.id }) { workout -> WorkoutCard(workout) }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(workout: WorkoutSummary) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(workout.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(formatter.format(workout.startTime.atZone(ZoneId.systemDefault())))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${workout.durationMinutes} דקות")
                Text(workout.averageHeartRate?.let { "דופק ממוצע $it" } ?: "ללא נתוני דופק")
            }
            Text(workout.sourcePackage, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
