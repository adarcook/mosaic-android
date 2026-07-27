package life.mosaic.feature.training

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private const val SamsungHealthPackage = "com.sec.android.app.shealth"
private const val GoalsPreferences = "training_goals"
private const val SwimGoalKey = "weekly_swim_goal"
private const val StrengthGoalKey = "weekly_strength_goal"
private const val ManualPreferences = "manual_training_sessions"
private const val ManualSessionsKey = "sessions"
private const val RecordSeparator = "\u001e"
private const val FieldSeparator = "\u001f"

private val RequiredPermissions = setOf(
    HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class)
)

private enum class SessionSource { HEALTH_CONNECT, MANUAL }
private enum class ManualTrainingType { SWIM, PUSH, PULL, OTHER }

private data class TrainingSession(
    val id: String,
    val title: String,
    val exerciseType: Int,
    val startTime: Instant,
    val durationMinutes: Long,
    val sourcePackage: String,
    val source: SessionSource = SessionSource.HEALTH_CONNECT,
    val manualType: ManualTrainingType? = null,
    val notes: String = ""
)

private data class TrainingGoals(
    val swimsPerWeek: Int = 3,
    val pushPerWeek: Int = 1,
    val pullPerWeek: Int = 1
) {
    val strengthPerWeek: Int get() = pushPerWeek + pullPerWeek
}

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
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }

    var goals by remember { mutableStateOf(loadTrainingGoals(context)) }
    var manualSessions by remember { mutableStateOf(loadManualSessions(context)) }
    var importedSessions by remember { mutableStateOf<List<TrainingSession>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<TrainingSession?>(null) }
    var showSessionEditor by remember { mutableStateOf(false) }
    var hasPermissions by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var diagnostic by remember { mutableStateOf("טרם בוצעה קריאה") }

    val sessions = remember(importedSessions, manualSessions) {
        (importedSessions + manualSessions).sortedByDescending { it.startTime }
    }

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
            if (!hasPermissions) TrainingReadResult(emptyList(), 0, 0, granted)
            else readTrainingSessions(healthClient, granted)
        }.onSuccess { result ->
            importedSessions = result.sessions
            diagnostic = "הרשאות: ${result.grantedPermissions.size} · כל המקורות: ${result.allRecordCount} · Samsung Health: ${result.samsungRecordCount}"
        }.onFailure {
            error = it.message ?: "קריאת האימונים נכשלה"
            diagnostic = "שגיאה: ${it::class.simpleName}"
        }
        loading = false
    }

    LaunchedEffect(client, hasPermissions) {
        if (client != null) refresh()
    }

    if (showSessionEditor) {
        SessionEditorDialog(
            session = editingSession,
            onDismiss = { showSessionEditor = false },
            onSave = { saved ->
                manualSessions = if (editingSession == null) {
                    (manualSessions + saved).sortedByDescending { it.startTime }
                } else {
                    manualSessions.map { if (it.id == saved.id) saved else it }
                }
                saveManualSessions(context, manualSessions)
                showSessionEditor = false
            }
        )
    }

    MaterialTheme {
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("השבוע שלי", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(currentWeekLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        onGoalsChanged = {
                            goals = it
                            saveTrainingGoals(context, it)
                        }
                    )
                }
            }

            item { WeeklyDashboard(sessions, goals) }

            item {
                Button(
                    onClick = {
                        editingSession = null
                        showSessionEditor = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("＋ הוספת אימון ידני") }
            }

            when {
                sdkStatus != HealthConnectClient.SDK_AVAILABLE -> item { StatusCard("Health Connect אינו זמין במכשיר") }
                !hasPermissions -> item {
                    StatusCard("כדי לייבא אימונים, יש לאשר גישה ל-Health Connect")
                    Button(
                        onClick = { permissionLauncher.launch(RequiredPermissions) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("אישור גישה לאימונים") }
                }
                loading -> item { StatusCard("טוען את נתוני האימונים…") }
                error != null -> item { StatusCard(error.orEmpty()) }
            }

            item {
                Text("אימונים אחרונים", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            if (sessions.isEmpty()) {
                item { StatusCard("עדיין לא נמצאו אימונים. אפשר להוסיף אימון ידני או לרענן לאחר סנכרון.") }
            } else {
                items(sessions.take(20), key = { it.id }) { session ->
                    TrainingCard(
                        session = session,
                        onEdit = if (session.source == SessionSource.MANUAL) {
                            {
                                editingSession = session
                                showSessionEditor = true
                            }
                        } else null,
                        onDelete = if (session.source == SessionSource.MANUAL) {
                            {
                                manualSessions = manualSessions.filterNot { it.id == session.id }
                                saveManualSessions(context, manualSessions)
                            }
                        } else null
                    )
                }
            }

            if (client != null && hasPermissions) {
                item {
                    Button(onClick = { scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) {
                        Text("רענון מ-Health Connect")
                    }
                }
            }

            item {
                Text("פרטי פיתוח", style = MaterialTheme.typography.labelLarge)
                Text(diagnostic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun WeeklyDashboard(sessions: List<TrainingSession>, goals: TrainingGoals) {
    val weekStart = startOfCurrentWeek()
    val recent = sessions.filter { it.startTime >= weekStart }
    val swims = recent.count(::isSwim)
    val push = recent.count { it.manualType == ManualTrainingType.PUSH }
    val pull = recent.count { it.manualType == ManualTrainingType.PULL }
    val genericStrength = recent.count { isStrength(it) && it.manualType == null }
    val strength = push + pull + genericStrength
    val totalCompleted = swims + strength
    val totalGoal = goals.swimsPerWeek + goals.strengthPerWeek
    val lastSession = sessions.maxByOrNull { it.startTime }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("התקדמות שבועית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GoalProgressRow("🏊 שחייה", swims, goals.swimsPerWeek)
            GoalProgressRow("💪 כוח", strength, goals.strengthPerWeek)
            if (goals.pushPerWeek > 0) GoalProgressRow("דחיפה", push, goals.pushPerWeek)
            if (goals.pullPerWeek > 0) GoalProgressRow("משיכה", pull, goals.pullPerWeek)
            HorizontalDivider()
            Text("$totalCompleted מתוך $totalGoal אימונים הושלמו", fontWeight = FontWeight.SemiBold)
            Text(remainingGoalText(swims, strength, goals), color = MaterialTheme.colorScheme.onPrimaryContainer)
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text("$completed / $goal")
        }
        LinearProgressIndicator(
            progress = { if (goal == 0) 0f else (completed.toFloat() / safeGoal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GoalsSettingsCard(goals: TrainingGoals, onGoalsChanged: (TrainingGoals) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("מטרות שבועיות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            GoalStepper("אימוני שחייה", goals.swimsPerWeek) { onGoalsChanged(goals.copy(swimsPerWeek = it)) }
            GoalStepper("אימוני דחיפה", goals.pushPerWeek) { onGoalsChanged(goals.copy(pushPerWeek = it)) }
            GoalStepper("אימוני משיכה", goals.pullPerWeek) { onGoalsChanged(goals.copy(pullPerWeek = it)) }
            Text("אפשר להגדיר יעד אפס כדי להסיר אותו מהתוכנית השבועית.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GoalStepper(label: String, value: Int, onValueChanged: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.padding(top = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onValueChanged((value - 1).coerceAtLeast(0)) }) { Text("−") }
            Text(value.toString(), modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp), fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { onValueChanged((value + 1).coerceAtMost(14)) }) { Text("+") }
        }
    }
}

@Composable
private fun SessionEditorDialog(session: TrainingSession?, onDismiss: () -> Unit, onSave: (TrainingSession) -> Unit) {
    val initialDate = session?.startTime?.atZone(ZoneId.systemDefault())?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        ?: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    var type by remember(session) { mutableStateOf(session?.manualType ?: ManualTrainingType.SWIM) }
    var dateTimeText by remember(session) { mutableStateOf(initialDate) }
    var durationText by remember(session) { mutableStateOf(session?.durationMinutes?.toString() ?: "34") }
    var notes by remember(session) { mutableStateOf(session?.notes.orEmpty()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (session == null) "הוספת אימון" else "עריכת אימון") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("סוג אימון", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ManualTrainingType.entries.forEach { option ->
                        OutlinedButton(onClick = { type = option }) {
                            Text(if (type == option) "✓ ${manualTypeTitle(option)}" else manualTypeTitle(option))
                        }
                    }
                }
                OutlinedTextField(value = dateTimeText, onValueChange = { dateTimeText = it }, label = { Text("תאריך ושעה (yyyy-MM-dd HH:mm)") }, singleLine = true)
                OutlinedTextField(value = durationText, onValueChange = { durationText = it.filter(Char::isDigit) }, label = { Text("משך בדקות") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("הערות") })
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val localDateTime = runCatching { LocalDateTime.parse(dateTimeText, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
                val duration = durationText.toLongOrNull()
                if (localDateTime == null || duration == null || duration <= 0) {
                    validationError = "יש להזין תאריך תקין ומשך גדול מאפס"
                } else {
                    onSave(
                        TrainingSession(
                            id = session?.id ?: "manual:${UUID.randomUUID()}",
                            title = manualTypeTitle(type),
                            exerciseType = manualExerciseType(type),
                            startTime = localDateTime.atZone(ZoneId.systemDefault()).toInstant(),
                            durationMinutes = duration,
                            sourcePackage = "Mosaic",
                            source = SessionSource.MANUAL,
                            manualType = type,
                            notes = notes.trim()
                        )
                    )
                }
            }) { Text("שמירה") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}

@Composable
private fun TrainingCard(session: TrainingSession, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${formatSessionDate(session.startTime)} · ${session.durationMinutes} דקות")
            if (isSwim(session)) {
                Text("מרחק, מספר בריכות, קצב ודופק יופיעו כאן כאשר מקור הנתונים יספק אותם.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session.notes.isNotBlank()) Text(session.notes)
            Text(if (session.source == SessionSource.MANUAL) "הוזן ידנית ב-Mosaic" else "מקור: ${friendlySource(session.sourcePackage)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onEdit != null || onDelete != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onEdit?.let { OutlinedButton(onClick = it) { Text("עריכה") } }
                    onDelete?.let { TextButton(onClick = it) { Text("מחיקה") } }
                }
            }
        }
    }
}

private suspend fun readTrainingSessions(client: HealthConnectClient, grantedPermissions: Set<String>): TrainingReadResult {
    val end = Instant.now().plus(Duration.ofMinutes(5))
    val start = end.minus(Duration.ofDays(365))
    val timeRange = TimeRangeFilter.between(start, end)
    val allResponse = client.readRecords(ReadRecordsRequest(recordType = ExerciseSessionRecord::class, timeRangeFilter = timeRange, ascendingOrder = false))
    val samsungResponse = client.readRecords(
        ReadRecordsRequest(recordType = ExerciseSessionRecord::class, timeRangeFilter = timeRange, dataOriginFilter = setOf(DataOrigin(SamsungHealthPackage)), ascendingOrder = false)
    )
    val visibleRecords = allResponse.records.filterNot { it.metadata.dataOrigin.packageName.contains("healthconnectlab", ignoreCase = true) }
    return TrainingReadResult(
        sessions = visibleRecords.map { record ->
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

private fun isSwim(session: TrainingSession): Boolean = session.manualType == ManualTrainingType.SWIM || session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL || session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER

private fun isStrength(session: TrainingSession): Boolean = session.manualType == ManualTrainingType.PUSH || session.manualType == ManualTrainingType.PULL || session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS || session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING || session.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING

private fun exerciseTitle(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "שחייה בבריכה"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "שחייה במים פתוחים"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "קליסטניקס"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "אימון כוח"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "הרמת משקולות"
    else -> "אימון"
}

private fun manualTypeTitle(type: ManualTrainingType): String = when (type) {
    ManualTrainingType.SWIM -> "שחייה"
    ManualTrainingType.PUSH -> "דחיפה"
    ManualTrainingType.PULL -> "משיכה"
    ManualTrainingType.OTHER -> "אחר"
}

private fun manualExerciseType(type: ManualTrainingType): Int = when (type) {
    ManualTrainingType.SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    ManualTrainingType.PUSH, ManualTrainingType.PULL -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
    ManualTrainingType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}

private fun friendlySource(packageName: String): String = when (packageName) {
    SamsungHealthPackage -> "Samsung Health"
    else -> packageName
}

private fun startOfCurrentWeek(): Instant {
    val now = ZonedDateTime.now()
    return now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).toLocalDate().atStartOfDay(now.zone).toInstant()
}

private fun currentWeekLabel(): String {
    val zone = ZoneId.systemDefault()
    val start = startOfCurrentWeek().atZone(zone)
    val end = start.plusDays(6)
    val formatter = DateTimeFormatter.ofPattern("dd/MM")
    return "${start.format(formatter)}–${end.format(formatter)}"
}

private fun formatSessionDate(instant: Instant): String = instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

private fun remainingGoalText(swims: Int, strength: Int, goals: TrainingGoals): String {
    val remainingSwims = (goals.swimsPerWeek - swims).coerceAtLeast(0)
    val remainingStrength = (goals.strengthPerWeek - strength).coerceAtLeast(0)
    return if (remainingSwims == 0 && remainingStrength == 0) "כל הכבוד — השלמת את מטרות השבוע." else "נותרו השבוע: $remainingSwims שחייה · $remainingStrength כוח"
}

private fun loadTrainingGoals(context: Context): TrainingGoals {
    val preferences = context.getSharedPreferences(GoalsPreferences, Context.MODE_PRIVATE)
    val legacyStrength = preferences.getInt(StrengthGoalKey, 2)
    return TrainingGoals(
        swimsPerWeek = preferences.getInt(SwimGoalKey, 3),
        pushPerWeek = preferences.getInt("weekly_push_goal", legacyStrength.coerceAtLeast(1) / 2 + legacyStrength % 2),
        pullPerWeek = preferences.getInt("weekly_pull_goal", legacyStrength / 2)
    )
}

private fun saveTrainingGoals(context: Context, goals: TrainingGoals) {
    context.getSharedPreferences(GoalsPreferences, Context.MODE_PRIVATE).edit()
        .putInt(SwimGoalKey, goals.swimsPerWeek)
        .putInt(StrengthGoalKey, goals.strengthPerWeek)
        .putInt("weekly_push_goal", goals.pushPerWeek)
        .putInt("weekly_pull_goal", goals.pullPerWeek)
        .apply()
}

private fun loadManualSessions(context: Context): List<TrainingSession> {
    val raw = context.getSharedPreferences(ManualPreferences, Context.MODE_PRIVATE).getString(ManualSessionsKey, "").orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split(RecordSeparator).mapNotNull { record ->
        val fields = record.split(FieldSeparator)
        if (fields.size < 6) return@mapNotNull null
        runCatching {
            val type = ManualTrainingType.valueOf(fields[1])
            TrainingSession(
                id = fields[0],
                title = manualTypeTitle(type),
                exerciseType = manualExerciseType(type),
                startTime = Instant.ofEpochMilli(fields[2].toLong()),
                durationMinutes = fields[3].toLong(),
                sourcePackage = "Mosaic",
                source = SessionSource.MANUAL,
                manualType = type,
                notes = fields[5].replace("\\n", "\n")
            )
        }.getOrNull()
    }.sortedByDescending { it.startTime }
}

private fun saveManualSessions(context: Context, sessions: List<TrainingSession>) {
    val raw = sessions.joinToString(RecordSeparator) { session ->
        listOf(
            session.id,
            session.manualType?.name ?: ManualTrainingType.OTHER.name,
            session.startTime.toEpochMilli().toString(),
            session.durationMinutes.toString(),
            session.sourcePackage,
            session.notes.replace("\n", "\\n").replace(RecordSeparator, " ").replace(FieldSeparator, " ")
        ).joinToString(FieldSeparator)
    }
    context.getSharedPreferences(ManualPreferences, Context.MODE_PRIVATE).edit().putString(ManualSessionsKey, raw).apply()
}

@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Text(message, modifier = Modifier.padding(18.dp))
    }
}
