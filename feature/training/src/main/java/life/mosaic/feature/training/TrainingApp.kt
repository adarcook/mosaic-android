package life.mosaic.feature.training

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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SamsungHealthPackage = "com.sec.android.app.shealth"

private val RequiredPermissions = setOf(
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class)
)

data class TrainingSession(
    val id: String,
    val title: String,
    val exerciseType: Int,
    val startTime: Instant,
    val durationMinutes: Long,
    val sourcePackage: String
)

private data class TrainingReadResult(
    val sessions: List<TrainingSession>,
    val allRecordCount: Int,
    val samsungRecordCount: Int,
    val grantedPermissions: Set<String>
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
    var diagnostic by remember { mutableStateOf("טרם בוצעה קריאה") }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hasPermissions = granted.containsAll(RequiredPermissions)
        diagnostic = "הרשאות שהוחזרו ממסך האישור: ${granted.size}"
        if (!hasPermissions) error = "נדרשת הרשאה לקריאת אימונים ודופק"
    }

    suspend fun refresh() {
        val healthClient = client ?: return
        loading = true
        error = null
        runCatching {
            val granted = healthClient.permissionController.getGrantedPermissions()
            hasPermissions = granted.containsAll(RequiredPermissions)
            if (!hasPermissions) {
                TrainingReadResult(emptyList(), 0, 0, granted)
            } else {
                readTrainingSessions(healthClient, granted)
            }
        }.onSuccess { result ->
            sessions = result.sessions
            diagnostic = buildString {
                append("הרשאות: ${result.grantedPermissions.size}")
                append(" · כל המקורות: ${result.allRecordCount}")
                append(" · Samsung Health: ${result.samsungRecordCount}")
            }
        }.onFailure {
            error = it.message ?: "קריאת האימונים נכשלה"
            diagnostic = "שגיאה: ${it::class.simpleName}"
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
                    "בדיקת Health Connect: כל האימונים וכל מקורות הנתונים",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                DiagnosticCard(diagnostic)
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
                sessions.isEmpty() -> item {
                    StatusCard("Health Connect החזיר אפס ExerciseSessionRecord גם ללא סינון")
                    Button(
                        onClick = { scope.launch { refresh() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("בדיקה מחדש") }
                }
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

private suspend fun readTrainingSessions(
    client: HealthConnectClient,
    grantedPermissions: Set<String>
): TrainingReadResult {
    val end = Instant.now().plus(Duration.ofMinutes(5))
    val start = end.minus(Duration.ofDays(365))
    val timeRange = TimeRangeFilter.between(start, end)

    val allResponse = client.readRecords(
        ReadRecordsRequest(
            recordType = ExerciseSessionRecord::class,
            timeRangeFilter = timeRange,
            ascendingOrder = false
        )
    )

    val samsungResponse = client.readRecords(
        ReadRecordsRequest(
            recordType = ExerciseSessionRecord::class,
            timeRangeFilter = timeRange,
            dataOriginFilter = setOf(DataOrigin(SamsungHealthPackage)),
            ascendingOrder = false
        )
    )

    val records = if (allResponse.records.isNotEmpty()) {
        allResponse.records
    } else {
        samsungResponse.records
    }

    return TrainingReadResult(
        sessions = records.map { record ->
            TrainingSession(
                id = record.metadata.id,
                title = exerciseTitle(record.exerciseType),
                exerciseType = record.exerciseType,
                startTime = record.startTime,
                durationMinutes = Duration.between(record.startTime, record.endTime).toMinutes(),
                sourcePackage = record.metadata.dataOrigin.packageName
            )
        },
        allRecordCount = allResponse.records.size,
        samsungRecordCount = samsungResponse.records.size,
        grantedPermissions = grantedPermissions
    )
}

private fun exerciseTitle(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "שחייה בבריכה"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "שחייה במים פתוחים"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "קליסטניקס"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "אימון כוח"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "הרמת משקולות"
    else -> "אימון מסוג $type"
}

private fun isSwim(type: Int): Boolean = type == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL ||
    type == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER

private fun isStrength(type: Int): Boolean = type == ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS ||
    type == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ||
    type == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING

@Composable
private fun WeeklySummary(sessions: List<TrainingSession>) {
    val weekAgo = Instant.now().minus(Duration.ofDays(7))
    val recent = sessions.filter { it.startTime >= weekAgo }
    val swims = recent.count { isSwim(it.exerciseType) }
    val strength = recent.count { isStrength(it.exerciseType) }
    val uncategorized = recent.size - swims - strength
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
            if (uncategorized > 0) {
                Text(
                    "אימונים שעדיין לא סווגו: $uncategorized",
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
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
            Text("exerciseType = ${session.exerciseType}")
            Text(session.sourcePackage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("אבחון", fontWeight = FontWeight.Bold)
            Text(message)
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Text(message, modifier = Modifier.padding(18.dp))
    }
}
