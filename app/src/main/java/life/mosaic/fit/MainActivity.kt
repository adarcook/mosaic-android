package life.mosaic.fit

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MealCaptureScreen()
                }
            }
        }
    }

    @Composable
    private fun MealCaptureScreen() {
        var serverUrl by remember { mutableStateOf("http://192.168.1.200:8000") }
        var selectedImage by remember { mutableStateOf<Uri?>(null) }
        var resultText by remember { mutableStateOf("Choose a meal photo to begin.") }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            selectedImage = uri
            resultText = if (uri == null) "No image selected." else "Image selected."
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mosaic Fit", style = MaterialTheme.typography.headlineMedium)
            Text("First vertical slice: upload a meal photo and inspect the server response.")

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(onClick = { picker.launch("image/*") }) {
                Text("Choose meal photo")
            }

            Button(
                enabled = selectedImage != null && !loading,
                onClick = {
                    val uri = selectedImage ?: return@Button
                    loading = true
                    resultText = "Uploading…"
                    scope.launch {
                        resultText = runCatching { uploadMeal(serverUrl, uri) }
                            .getOrElse { "Upload failed: ${it.message}" }
                        loading = false
                    }
                }
            ) {
                Text("Analyze meal")
            }

            if (loading) CircularProgressIndicator()
            Text(resultText)
        }
    }

    private suspend fun uploadMeal(serverUrl: String, uri: Uri): String = withContext(Dispatchers.IO) {
        val imageBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not read the selected image")
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val boundary = "MosaicBoundary-${UUID.randomUUID()}"
        val endpoint = URL("${serverUrl.trimEnd('/')}/v1/meals/analyze")
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write("Content-Disposition: form-data; name=\"image\"; filename=\"meal.jpg\"\r\n".toByteArray())
            write("Content-Type: $mimeType\r\n\r\n".toByteArray())
            write(imageBytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", body.size.toString())
        }

        connection.outputStream.use { it.write(body) }
        val responseText = (if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }).bufferedReader().use { it.readText() }

        if (connection.responseCode !in 200..299) {
            error("Server returned ${connection.responseCode}: $responseText")
        }

        val json = JSONObject(responseText)
        val questions = json.getJSONArray("confirmation_questions")
        buildString {
            appendLine("Status: ${json.getString("status")}")
            appendLine("Analysis: ${json.getString("analysis_id")}")
            appendLine()
            appendLine("Questions:")
            for (index in 0 until questions.length()) {
                appendLine("• ${questions.getString(index)}")
            }
        }.trim()
    }
}
