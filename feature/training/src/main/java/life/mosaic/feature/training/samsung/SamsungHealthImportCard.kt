package life.mosaic.feature.training.samsung

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@Composable
fun SamsungHealthImportCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val source = remember(context) { createSamsungHealthExerciseSource(context) }
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var message by remember {
        mutableStateOf(
            source.unavailableReason ?: "Samsung Health Data SDK זמין. ניתן לבדוק קריאה ישירה של אימונים."
        )
    }
    var sessions by remember { mutableStateOf<List<SamsungExerciseSession>>(emptyList()) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Samsung Health ישיר", fontWeight = FontWeight.Bold)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (sessions.isNotEmpty()) {
                Text("נמצאו ${sessions.size} אימונים", fontWeight = FontWeight.SemiBold)
                sessions.take(5).forEach { session ->
                    Text(
                        "${session.title} · ${session.durationMinutes} דקות" +
                            (session.distanceMeters?.let { " · ${it.toInt()} מ׳" } ?: "")
                    )
                }
            }

            Button(
                enabled = source.sdkAvailable && activity != null && !loading,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val currentActivity = activity ?: return@Button
                    scope.launch {
                        loading = true
                        message = "מתחבר ל-Samsung Health…"
                        runCatching {
                            val granted = source.hasReadPermission() ||
                                source.requestReadPermission(currentActivity)
                            check(granted) { "לא ניתנה הרשאת קריאת אימונים מ-Samsung Health" }
                            source.readExercises(
                                start = Instant.now().minus(Duration.ofDays(365)),
                                end = Instant.now().plus(Duration.ofMinutes(1))
                            )
                        }.onSuccess {
                            sessions = it
                            message = if (it.isEmpty()) {
                                "ההרשאה התקבלה, אך Samsung Health לא החזירה אימונים."
                            } else {
                                "הייבוא הישיר הצליח."
                            }
                        }.onFailure {
                            message = "קריאת Samsung Health נכשלה: ${it.message ?: it::class.java.simpleName}"
                        }
                        loading = false
                    }
                }
            ) {
                Text(if (loading) "טוען…" else "בדיקת ייבוא ישיר")
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
