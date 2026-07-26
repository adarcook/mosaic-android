package life.mosaic.feature.training

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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

private val RequiredPermissions = setOf(
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class)
)

data class TrainingSession(
    val id: String,
    val title: String,
    val startTime: Instant,
    val durationMinutes: Long,
    val sourcePackage: String
)

@Composable
fun TrainingApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sdkStatus = remember { HealthConnectClient.getSdkStatus(context) }
    val client = remember(sdkStatus) {
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            HealthConnectClient.getOrCreate(context)
        } else null
    }

    var hasPermissions by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf<List<TrainingSession>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hasPermissions = granted.containsAll(RequiredPermissions)
        if (!hasPermissions) error = "נדרשת הרשאה לקריאת אימונים ודופק"
    }

    suspend fun refresh() {
        val healthClient = client ?: return
        loading = true
        error = null
        runCatching {
            val granted = healthClient.permissionController.getGrantedPermissions()
            hasPermissions = granted.containsAll(RequiredPermissions)
            if (!hasPermissions) return@runCatching emptyList()
            readTrainingSessions(healthClient)
        }.onSuccess {
            sessions = it
        }.onFailure {
            error = it.message ?: "קריאת האימונים נכשלה"
        }
        loading = false
    }

    LaunchedEffect(client, hasPermissions) {
        if (client != null) refresh()
    }

    MaterialTheme {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("אימונים", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "מעקב אחר 3 אימוני שחייה ו-2 אימוני קליסטניקס בשבוע",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            when {
                sdkStatus != HealthConnectClient.SDK_AVAILABLE -> item {
                    StatusCard("Health Connect אינו זמין במכשיר")
                }

                !hasPermissions -> item {
                    StatusCard("כדי לייבא אימונים מהשעון, יש לאשר גישה ל-Health Connect")
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { permissionLauncher.launch(RequiredPermissions) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("אישור גישה לאימונים") }
                }

                loading -> item { StatusCard("טוען אימונים…") }
                error != null -> item { StatusCard(error.orEmpty()) }
                sessions.isEmpty() -> item { StatusCard("לא נמצאו אימוני שחייה או כוח ב-30 הימים האחרונים") }
                else -> {
                    item {
                        WeeklySummary(sessions)
                        Button(
                            onClick = { scope.launch { refresh() } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("רענון מ-Health Connect") }
                    }
                    items(sessions, key = { it.id }) { session -> TrainingCard(session) }
                }
            }
        }
    }
}

private suspend fun readTrainingSessions(client: HealthConnectClient): List<TrainingSession> {
    val end = Instant.now()
    val start = end.minus(Duration.ofDays(30))
    val response = client.readRecords(
        ReadRecordsRequest(
            recordType = ExerciseSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end),
            ascendingOrder = false
        )
    )

    return response.records
        .filter { it.exerciseType in RelevantExerciseTypes }
        .map { record ->
            TrainingSession(
                id = record.metadata.id,
                title = exerciseTitle(record.exerciseType),
                startTime = record.startTime,
                durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes(),
                sourcePackage = record.metadata.dataOrigin.packageName
            )
        }
}

private val RelevantExerciseTypes = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING
)

private fun exerciseTitle(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "שחייה בבריכה"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "שחייה במים פתוחים"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "קליסטניקס"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "אימון כוח"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "הרמת משקולות"
    else -> "אימון"
}

@Composable
private fun WeeklySummary(sessions: List<TrainingSession>) {
    val weekAgo = Instant.now().minus(Duration.ofDays(7))
    val recent = sessions.filter { it.startTime >= weekAgo }
    val swims = recent.count { it.title.startsWith("שחייה") }
    val strength = recent.size - swims
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("השבוע", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("שחייה: $swims / 3")
                Text("כוח: $strength / 2")
            }
        }
    }
}

@Composable
private fun TrainingCard(session: TrainingSession) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM HH:mm") }
    val localTime = session.startTime.atZone(ZoneId.systemDefault()).format(formatter)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$localTime · ${session.durationMinutes} דקות")
            Text(session.sourcePackage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Text(message, modifier = Modifier.padding(18.dp))
    }
}
