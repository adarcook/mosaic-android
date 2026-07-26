package life.mosaic.fit

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
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
        var message by remember { mutableStateOf("Choose a meal photo to begin.") }
        var analysis by remember { mutableStateOf<MealAnalysis?>(null) }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            selectedImage = uri
            analysis = null
            message = if (uri == null) "No image selected." else "Image selected."
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mosaic Fit", style = MaterialTheme.typography.headlineMedium)
            Text("Upload a meal photo and review the estimated nutrition.")

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
                    analysis = null
                    message = "Analyzing meal…"
                    scope.launch {
                        runCatching { uploadMeal(serverUrl, uri) }
                            .onSuccess {
                                analysis = it
                                message = ""
                            }
                            .onFailure {
                                message = "Upload failed: ${it.message}"
                            }
                        loading = false
                    }
                }
            ) {
                Text("Analyze meal")
            }

            if (loading) CircularProgressIndicator()
            if (message.isNotBlank()) Text(message)
            analysis?.let { MealAnalysisResult(it) }
        }
    }

    @Composable
    private fun MealAnalysisResult(analysis: MealAnalysis) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Meal analysis", style = MaterialTheme.typography.titleLarge)
                    Text("Status: ${analysis.status}")
                    Text(
                        "Analysis ID: ${analysis.analysisId}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Estimated nutrition", style = MaterialTheme.typography.titleMedium)
                    NutritionRow("Calories", "${analysis.nutrition.caloriesKcal} kcal")
                    NutritionRow("Protein", "${formatNumber(analysis.nutrition.proteinG)} g")
                    NutritionRow("Carbohydrates", "${formatNumber(analysis.nutrition.carbohydratesG)} g")
                    NutritionRow("Fat", "${formatNumber(analysis.nutrition.fatG)} g")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Foods detected", style = MaterialTheme.typography.titleMedium)
                    if (analysis.items.isEmpty()) {
                        Text("No food items were identified.")
                    } else {
                        analysis.items.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider()
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold)
                                Text(item.estimatedQuantity)
                                Text(
                                    "Confidence: ${(item.confidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            TextListCard("Assumptions", analysis.assumptions, "No assumptions were reported.")
            TextListCard(
                "Questions to confirm",
                analysis.confirmationQuestions,
                "No confirmation is required."
            )
        }
    }

    @Composable
    private fun NutritionRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun TextListCard(title: String, values: List<String>, emptyText: String) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (values.isEmpty()) {
                    Text(emptyText)
                } else {
                    values.forEach { Text("• $it") }
                }
            }
        }
    }

    private suspend fun uploadMeal(serverUrl: String, uri: Uri): MealAnalysis =
        withContext(Dispatchers.IO) {
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
                readTimeout = 120_000
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Content-Length", body.size.toString())
            }

            try {
                connection.outputStream.use { it.write(body) }
                val responseText = (if (connection.responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }).bufferedReader().use { it.readText() }

                if (connection.responseCode !in 200..299) {
                    error("Server returned ${connection.responseCode}: $responseText")
                }

                parseMealAnalysis(JSONObject(responseText))
            } finally {
                connection.disconnect()
            }
        }

    private fun parseMealAnalysis(json: JSONObject): MealAnalysis {
        val nutritionJson = json.getJSONObject("nutrition")
        val itemsJson = json.getJSONArray("items")
        val assumptionsJson = json.getJSONArray("assumptions")
        val questionsJson = json.getJSONArray("confirmation_questions")

        return MealAnalysis(
            analysisId = json.getString("analysis_id"),
            status = json.getString("status"),
            items = List(itemsJson.length()) { index ->
                val item = itemsJson.getJSONObject(index)
                MealItem(
                    name = item.getString("name"),
                    estimatedQuantity = item.getString("estimated_quantity"),
                    confidence = item.getDouble("confidence")
                )
            },
            nutrition = NutritionEstimate(
                caloriesKcal = nutritionJson.getInt("calories_kcal"),
                proteinG = nutritionJson.getDouble("protein_g"),
                carbohydratesG = nutritionJson.getDouble("carbohydrates_g"),
                fatG = nutritionJson.getDouble("fat_g")
            ),
            assumptions = List(assumptionsJson.length()) { assumptionsJson.getString(it) },
            confirmationQuestions = List(questionsJson.length()) { questionsJson.getString(it) }
        )
    }

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
}

data class MealAnalysis(
    val analysisId: String,
    val status: String,
    val items: List<MealItem>,
    val nutrition: NutritionEstimate,
    val assumptions: List<String>,
    val confirmationQuestions: List<String>
)

data class MealItem(
    val name: String,
    val estimatedQuantity: String,
    val confidence: Double
)

data class NutritionEstimate(
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbohydratesG: Double,
    val fatG: Double
)
