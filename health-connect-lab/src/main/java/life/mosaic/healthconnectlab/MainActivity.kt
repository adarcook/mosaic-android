package life.mosaic.healthconnectlab

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var output: TextView
    private var client: HealthConnectClient? = null

    private val readExercisePermission =
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    private val writeExercisePermission =
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    private val requiredPermissions = setOf(readExercisePermission, writeExercisePermission)

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        append("Permission result: $granted")
        lifecycleScope.launch { inspectAndRead() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sdkStatus = HealthConnectClient.getSdkStatus(this)
        if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
            client = HealthConnectClient.getOrCreate(this)
        }

        val requestButton = Button(this).apply {
            text = "1. Request read + write permissions"
            setOnClickListener { permissionLauncher.launch(requiredPermissions) }
        }
        val insertButton = Button(this).apply {
            text = "2. Insert test exercise"
            setOnClickListener { lifecycleScope.launch { insertTestExercise() } }
        }
        val readButton = Button(this).apply {
            text = "3. Read exercise sessions (last 7 days)"
            setOnClickListener { lifecycleScope.launch { inspectAndRead() } }
        }
        output = TextView(this).apply {
            textSize = 16f
            setPadding(24, 24, 24, 24)
            text = "Health Connect Lab v2\nSDK status: $sdkStatus\n"
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(requestButton, matchWidth())
            addView(insertButton, matchWidth())
            addView(readButton, matchWidth())
            addView(output, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private suspend fun insertTestExercise() {
        val healthClient = client
        if (healthClient == null) {
            append("Health Connect client unavailable")
            return
        }

        runCatching {
            val granted = healthClient.permissionController.getGrantedPermissions()
            append("Granted permissions before insert (${granted.size}): $granted")
            if (writeExercisePermission !in granted) {
                append("STOP: write exercise permission is missing")
                return
            }

            val end = Instant.now().minus(Duration.ofMinutes(1))
            val start = end.minus(Duration.ofMinutes(20))
            val zoneRules = ZoneId.systemDefault().rules
            val record = ExerciseSessionRecord(
                startTime = start,
                startZoneOffset = zoneRules.getOffset(start),
                endTime = end,
                endZoneOffset = zoneRules.getOffset(end),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
                title = "Health Connect Lab test swim",
                metadata = Metadata.manualEntry()
            )

            val response = healthClient.insertRecords(listOf(record))
            append("INSERT SUCCESS: recordIds=${response.recordIdsList}")
            inspectAndRead()
        }.onFailure { throwable ->
            Log.e(TAG, "Health Connect insert failed", throwable)
            append("INSERT ERROR: ${throwable::class.java.name}: ${throwable.message}")
        }
    }

    private suspend fun inspectAndRead() {
        val healthClient = client
        if (healthClient == null) {
            append("Health Connect client unavailable")
            return
        }

        runCatching {
            val granted = healthClient.permissionController.getGrantedPermissions()
            append("Granted permissions (${granted.size}): $granted")
            append("Read exercise granted: ${readExercisePermission in granted}")
            append("Write exercise granted: ${writeExercisePermission in granted}")

            if (readExercisePermission !in granted) {
                append("STOP: read exercise permission is missing")
                return
            }

            val end = Instant.now().plus(Duration.ofMinutes(1))
            val start = end.minus(Duration.ofDays(7))
            append("Reading ExerciseSessionRecord from $start to $end")

            val response = healthClient.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = false,
                    pageSize = 1000
                )
            )

            append("RESULT COUNT: ${response.records.size}")
            append("PAGE TOKEN: ${response.pageToken}")

            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())

            response.records.forEachIndexed { index, record ->
                val line = buildString {
                    append("#${index + 1} ")
                    append("type=${record.exerciseType}, ")
                    append("title=${record.title}, ")
                    append("start=${formatter.format(record.startTime)}, ")
                    append("end=${formatter.format(record.endTime)}, ")
                    append("source=${record.metadata.dataOrigin.packageName}, ")
                    append("id=${record.metadata.id}")
                }
                append(line)
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Health Connect read failed", throwable)
            append("READ ERROR: ${throwable::class.java.name}: ${throwable.message}")
        }
    }

    private fun append(message: String) {
        Log.d(TAG, message)
        output.append("\n$message")
    }

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val TAG = "HealthConnectLab"
    }
}
