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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private const val SamsungHealthPackage = "com.sec.android.app.shealth"
private const val GoalsPreferences = "training_goals"
private const val SwimGoalKey = "weekly_swim_goal"
private const val StrengthGoalKey = "weekly_strength_goal"

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

private data class TrainingGoals(
    val swimsPerWeek: Int = 3,
    val strengthPerWeek: Int = 2
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

    var goals by remember { mutableStateOf(loadTrainingGoals(context)) }
    var showSettings by remember { mutableStateOf(false) }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "השבוע שלי",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            currentWeekLabel(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "סגירה" else "מטרות")
                    }
                }
            }

            if (showSettings) {
                item {
                    GoalsSettingsCard(
                        goals = goals,
                        onSwimGoalChanged = { value ->
                            goals = goals.copy(swimsPerWeek = value)
                            saveTrainingGoals(context, goals)
                        },
                        onStrengthGoalChanged = { value ->
                            goals = goals.copy(strengthPerWeek = value)
                            saveTrainingGoals(context, goals)
                        }
                    )
                }
            }

            when {
                sdkStatus != HealthConnectClient.SDK_AVAILABLE -> item {
                    StatusCard("Health Connect אינו זמין במכשיר")
                }

                !hasPermissions -> item {
                    StatusCard("כדי להציג אימונים, יש לאשר גישה ל-Health Connect")
                    Button(
                        onClick = { permissionLauncher.launch(RequiredPermissions) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("אישור גישה לאימונים") }
                }

                loading -> item { StatusCard("טוען את נתוני האימונים…") }
                error != null -> item { StatusCard(error.orEmpty()) }
                else -> {
                    item { WeeklyDashboard(sessions, goals) }

                    item {
                        Text(
                            "אימונים אחרונים",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (sessions.isEmpty()) {
                        item {
                            StatusCard("עדיין לא נמצאו אימונים. אפשר לרענן לאחר סנכרון נתונים.")
                        }
                    } else {
                        items(sessions.take(12), key = { it.id }) { session ->
                            TrainingCard(session)
                        }
                    }

                    item {
                        Button(
                            onClick = { scope.launch { refresh() } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("רענון נתונים") }
                    }

                    item { DiagnosticCard(diagnostic) }
                }
            }
        }
    }
}

@Composable
private fun WeeklyDashboard(sessions: List<TrainingSession>, goals: TrainingGoals) {
    val weekStart = startOfCurrentWeek()
    val recent = sessions.filter { it.startTime >= weekStart }
    val swims = recent.count { isSwim(it.exerciseType) }
    val strength = recent.count { isStrength(it.exerciseType) }
    val totalCompleted = swims + strength
    val totalGoal = goals.swimsPerWeek + goals.strengthPerWeek
    val lastSession = sessions.maxByOrNull { it.startTime }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("התקדמות שבועית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GoalProgressRow("🏊 שחייה", swims, goals.swimsPerWeek)
            GoalProgressRow("💪 כוח", strength, goals.strengthPerWeek)
            HorizontalDivider()
            Text(
                "$totalCompleted מתוך $totalGoal אימונים הושלמו",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                remainingGoalText(swims, strength, goals),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            lastSession?.let {
                HorizontalDivider()
                Text("האימון האחרון", fontWeight = FontWeight.Bold)
                Text("${it.title} · ${formatSessionDate(it.startTime)} · ${it.durationMinutes} דקות")
            }
        }
    }
}

@Composable
private fun GoalProgressRow(label: String, completed: Int, goal: Int) {
    val safeGoal = goal.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text("$completed / $goal")
        }
        LinearProgressIndicator(
            progress = { (completed.toFloat() / safeGoal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GoalsSettingsCard(
    goals: TrainingGoals,
    onSwimGoalChanged: (Int) -> Unit,
    onStrengthGoalChanged: (Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("מטרות שבועיות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GoalStepper("אימוני שחייה", goals.swimsPerWeek, onSwimGoalChanged)
            GoalStepper("אימוני כוח", goals.strengthPerWeek, onStrengthGoalChanged)
            Text(
                "המטרות נשמרות במכשיר ומתעדכנות מיד בלוח השבועי.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun GoalStepper(label: String, value: Int, onValueChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onValueChanged((value - 1).coerceAtLeast(0)) }) {
                Text("−")
            }
            Text(
                value.toString(),
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = { onValueChanged((value + 1).coerceAtMost(14)) }) {
                Text("+")
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

    val records = if (allResponse.records.isNotEmpty()) allResponse.records else samsungResponse.records

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

private fun startOfCurrentWeek(): Instant = ZonedDateTime.now()
    .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
    .toLocalDate()
    .atStartOfDay(ZoneId.systemDefault())
    .toInstant()

private fun currentWeekLabel(): String {
    val start = startOfCurrentWeek().atZone(ZoneId.systemDefault()).toLocalDate()
    val end = start.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern("dd/MM")
    return "${start.format(formatter)}–${end.format(formatter)}"
}

private fun remainingGoalText(swims: Int, strength: Int, goals: TrainingGoals): String {
    val remainingSwims = (goals.swimsPerWeek - swims).coerceAtLeast(0)
    val remainingStrength = (goals.strengthPerWeek - strength).coerceAtLeast(0)
    return when {
        remainingSwims == 0 && remainingStrength == 0 -> "כל הכבוד — השלמת את יעדי השבוע"
        else -> "נותרו השבוע: $remainingSwims שחייה · $remainingStrength כוח"
    }
}

private fun loadTrainingGoals(context: Context): TrainingGoals {
    val preferences = context.getSharedPreferences(GoalsPreferences, Context.MODE_PRIVATE)
    return TrainingGoals(
        swimsPerWeek = preferences.getInt(SwimGoalKey, 3),
        strengthPerWeek = preferences.getInt(StrengthGoalKey, 2)
    )
}

private fun saveTrainingGoals(context: Context, goals: TrainingGoals) {
    context.getSharedPreferences(GoalsPreferences, Context.MODE_PRIVATE)
        .edit()
        .putInt(SwimGoalKey, goals.swimsPerWeek)
        .putInt(StrengthGoalKey, goals.strengthPerWeek)
        .apply()
}

@Composable
private fun TrainingCard(session: TrainingSession) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${formatSessionDate(session.startTime)} · ${session.durationMinutes} דקות")
            Text(session.sourcePackage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatSessionDate(startTime: Instant): String {
    val formatter = DateTimeFormatter.ofPattern("dd/MM HH:mm")
    return startTime.atZone(ZoneId.systemDefault()).format(formatter)
}

@Composable
private fun DiagnosticCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("אבחון נתונים", fontWeight = FontWeight.Bold)
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
