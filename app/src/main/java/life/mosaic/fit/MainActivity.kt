package life.mosaic.fit

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private val MosaicBackground = Color(0xFF07111F)
private val MosaicSurface = Color(0xFF101D30)
private val MosaicSurfaceBright = Color(0xFF172842)
private val MosaicCyan = Color(0xFF62E7FF)
private val MosaicViolet = Color(0xFF9B8CFF)
private val MosaicText = Color(0xFFF3F7FF)
private val MosaicMuted = Color(0xFFA8B5C8)
private val MosaicSuccess = Color(0xFF6EF2B4)

private val MosaicColors = darkColorScheme(
    primary = MosaicCyan,
    secondary = MosaicViolet,
    background = MosaicBackground,
    surface = MosaicSurface,
    onPrimary = MosaicBackground,
    onBackground = MosaicText,
    onSurface = MosaicText
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = MosaicColors) {
                    MosaicFitApp()
                }
            }
        }
    }

    @Composable
    private fun MosaicFitApp() {
        var destination by remember { mutableStateOf(AppDestination.Today) }
        var latestAnalysis by remember { mutableStateOf<MealAnalysis?>(null) }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                MosaicNavigationBar(
                    selected = destination,
                    onSelected = { destination = it }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF0A1628), MosaicBackground, Color(0xFF050B14))
                        )
                    )
                    .padding(paddingValues)
            ) {
                when (destination) {
                    AppDestination.Today -> TodayScreen(
                        latestAnalysis = latestAnalysis,
                        onAddMeal = { destination = AppDestination.Analyze }
                    )
                    AppDestination.Analyze -> AnalyzeScreen(
                        onAnalysisReady = {
                            latestAnalysis = it
                            destination = AppDestination.Today
                        }
                    )
                    AppDestination.Insights -> InsightsScreen()
                }
            }
        }
    }

    @Composable
    private fun MosaicNavigationBar(
        selected: AppDestination,
        onSelected: (AppDestination) -> Unit
    ) {
        NavigationBar(containerColor = Color(0xF20B1626)) {
            AppDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelected(destination) },
                    icon = {
                        Text(
                            destination.symbol,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    label = { Text(destination.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MosaicCyan,
                        selectedTextColor = MosaicCyan,
                        indicatorColor = MosaicSurfaceBright,
                        unselectedIconColor = MosaicMuted,
                        unselectedTextColor = MosaicMuted
                    )
                )
            }
        }
    }

    @Composable
    private fun TodayScreen(latestAnalysis: MealAnalysis?, onAddMeal: () -> Unit) {
        val nutrition = latestAnalysis?.nutrition ?: NutritionEstimate(0, 0.0, 0.0, 0.0)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(
                eyebrow = "MOSAIC FIT",
                title = "היום שלך",
                subtitle = "מעקב תזונתי חכם, פשוט וברור"
            )

            GlowCard {
                Text("סיכום יומי", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Metric("קלוריות", nutrition.caloriesKcal.toString(), "קק״ל")
                    Metric("חלבון", formatNumber(nutrition.proteinG), "גרם")
                    Metric("פחמימות", formatNumber(nutrition.carbohydratesG), "גרם")
                    Metric("שומן", formatNumber(nutrition.fatG), "גרם")
                }
            }

            SectionTitle("הארוחות שלי")
            if (latestAnalysis == null) {
                EmptyMealCard(onAddMeal)
            } else {
                LatestMealCard(latestAnalysis)
                Button(
                    onClick = onAddMeal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MosaicCyan,
                        contentColor = MosaicBackground
                    )
                ) {
                    Text("הוספת ארוחה נוספת", fontWeight = FontWeight.Bold)
                }
            }

            GlowCard {
                Text("המבט הבא", color = MosaicCyan, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "התשתית מוכנה לצבירת ארוחות לאורך היום. בהמשך נחבר שמירה מקומית, יעדים אישיים וסיכומים שבועיים וחודשיים.",
                    color = MosaicMuted
                )
            }
        }
    }

    @Composable
    private fun AnalyzeScreen(onAnalysisReady: (MealAnalysis) -> Unit) {
        var serverUrl by remember { mutableStateOf("http://192.168.1.200:8000") }
        var selectedImage by remember { mutableStateOf<Uri?>(null) }
        var message by remember { mutableStateOf("בחר תמונה של הארוחה כדי להתחיל") }
        var analysis by remember { mutableStateOf<MealAnalysis?>(null) }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val picker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            selectedImage = uri
            analysis = null
            message = if (uri == null) "לא נבחרה תמונה" else "התמונה מוכנה לניתוח"
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(
                eyebrow = "AI MEAL SCAN",
                title = "ניתוח ארוחה",
                subtitle = "צלם או בחר תמונה וקבל הערכה תזונתית"
            )

            GlowCard {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("כתובת השרת") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MosaicCyan,
                        unfocusedBorderColor = Color(0xFF38506E),
                        focusedLabelColor = MosaicCyan,
                        cursorColor = MosaicCyan
                    )
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("בחירת תמונת ארוחה")
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = selectedImage != null && !loading,
                    onClick = {
                        val uri = selectedImage ?: return@Button
                        loading = true
                        analysis = null
                        message = "מנתח את הארוחה…"
                        scope.launch {
                            runCatching { uploadMeal(serverUrl, uri) }
                                .onSuccess {
                                    analysis = it
                                    message = "הניתוח הושלם"
                                }
                                .onFailure {
                                    message = "הניתוח נכשל: ${it.message}"
                                }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MosaicCyan,
                        contentColor = MosaicBackground
                    )
                ) {
                    Text("ניתוח באמצעות AI", fontWeight = FontWeight.Bold)
                }
            }

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(color = MosaicCyan) }
            }
            if (message.isNotBlank()) {
                Text(message, color = MosaicMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            analysis?.let {
                MealAnalysisResult(it)
                Button(
                    onClick = { onAnalysisReady(it) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MosaicSuccess,
                        contentColor = MosaicBackground
                    )
                ) {
                    Text("הוספה למעקב היומי", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun InsightsScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header("MOSAIC INSIGHTS", "מגמות ותובנות", "השבוע והחודש שלך במקום אחד")
            PlaceholderInsightCard("שבועי", "ממוצעי קלוריות ומאקרו, עקביות וימי אימון")
            PlaceholderInsightCard("חודשי", "מגמות משקל, צריכת חלבון והתקדמות לעבר היעד")
            GlowCard {
                Text("בקרוב", color = MosaicViolet, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "המסך כבר משולב בניווט כדי שנוכל להוסיף את יכולות המעקב בהדרגה בלי לבנות מחדש את מבנה האפליקציה.",
                    color = MosaicMuted
                )
            }
        }
    }

    @Composable
    private fun Header(eyebrow: String, title: String, subtitle: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(eyebrow, color = MosaicCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MosaicMuted)
        }
    }

    @Composable
    private fun GlowCard(content: @Composable Column.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MosaicSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF172842), Color(0xFF101D30), Color(0xFF0D1929))
                        )
                    )
                    .padding(20.dp),
                content = content
            )
        }
    }

    @Composable
    private fun Metric(label: String, value: String, unit: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = MosaicText, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = MosaicCyan, fontSize = 11.sp)
            Text(label, color = MosaicMuted, fontSize = 12.sp)
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun EmptyMealCard(onAddMeal: () -> Unit) {
        GlowCard {
            Text("עדיין לא נוספה ארוחה היום", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("הוסף צילום ראשון כדי להתחיל לבנות את הסיכום היומי.", color = MosaicMuted)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onAddMeal,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MosaicCyan,
                    contentColor = MosaicBackground
                )
            ) { Text("הוספת ארוחה", fontWeight = FontWeight.Bold) }
        }
    }

    @Composable
    private fun LatestMealCard(analysis: MealAnalysis) {
        GlowCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("הארוחה האחרונה", color = MosaicCyan, fontWeight = FontWeight.Bold)
                    Text(
                        analysis.items.firstOrNull()?.name ?: "ארוחה שנותחה",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text("${analysis.nutrition.caloriesKcal} קק״ל", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "חלבון ${formatNumber(analysis.nutrition.proteinG)} ג׳  •  פחמימות ${formatNumber(analysis.nutrition.carbohydratesG)} ג׳  •  שומן ${formatNumber(analysis.nutrition.fatG)} ג׳",
                color = MosaicMuted
            )
        }
    }

    @Composable
    private fun PlaceholderInsightCard(title: String, description: String) {
        GlowCard {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(description, color = MosaicMuted)
            Spacer(Modifier.height(14.dp))
            Text("הנתונים יופיעו לאחר שמירת ארוחות לאורך זמן", color = MosaicViolet)
        }
    }

    @Composable
    private fun MealAnalysisResult(analysis: MealAnalysis) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlowCard {
                Text("הערכה תזונתית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                NutritionRow("קלוריות", "${analysis.nutrition.caloriesKcal} קק״ל")
                NutritionRow("חלבון", "${formatNumber(analysis.nutrition.proteinG)} גרם")
                NutritionRow("פחמימות", "${formatNumber(analysis.nutrition.carbohydratesG)} גרם")
                NutritionRow("שומן", "${formatNumber(analysis.nutrition.fatG)} גרם")
            }

            GlowCard {
                Text("מזונות שזוהו", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (analysis.items.isEmpty()) {
                    Text("לא זוהו פריטי מזון", color = MosaicMuted)
                } else {
                    analysis.items.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = Color(0xFF2B405C))
                        Column(
                            modifier = Modifier.padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(item.name, fontWeight = FontWeight.Bold)
                            Text(item.estimatedQuantity, color = MosaicMuted)
                            Text("רמת ביטחון: ${(item.confidence * 100).toInt()}%", color = MosaicCyan)
                        }
                    }
                }
            }

            TextListCard("הנחות", analysis.assumptions, "לא דווחו הנחות")
            TextListCard("שאלות לאישור", analysis.confirmationQuestions, "לא נדרש אישור נוסף")
        }
    }

    @Composable
    private fun NutritionRow(label: String, value: String) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MosaicMuted)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun TextListCard(title: String, values: List<String>, emptyText: String) {
        GlowCard {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (values.isEmpty()) {
                Text(emptyText, color = MosaicMuted)
            } else {
                values.forEach { Text("• $it", color = MosaicMuted, modifier = Modifier.padding(vertical = 3.dp)) }
            }
        }
    }

    private suspend fun uploadMeal(serverUrl: String, uri: Uri): MealAnalysis =
        withContext(Dispatchers.IO) {
            val imageBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("לא ניתן לקרוא את התמונה שנבחרה")
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
                    error("השרת החזיר ${connection.responseCode}: $responseText")
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
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

private enum class AppDestination(val label: String, val symbol: String) {
    Today("היום", "◉"),
    Analyze("ניתוח", "✦"),
    Insights("מגמות", "⌁")
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
