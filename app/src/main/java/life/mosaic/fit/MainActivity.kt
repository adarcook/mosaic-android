package life.mosaic.fit

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val PreferencesName = "mosaic_fit_preferences"
private const val ThemePreferenceKey = "selected_theme"

private data class ThemePalette(
    val id: String,
    val title: String,
    val description: String,
    val background: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val surfaceHighlight: Color,
    val primary: Color,
    val secondary: Color,
    val text: Color,
    val muted: Color,
    val success: Color,
    val isLight: Boolean = false
) {
    val colorScheme
        get() = if (isLight) {
            lightColorScheme(
                primary = primary,
                secondary = secondary,
                background = background,
                surface = surface,
                onPrimary = background,
                onBackground = text,
                onSurface = text
            )
        } else {
            darkColorScheme(
                primary = primary,
                secondary = secondary,
                background = background,
                surface = surface,
                onPrimary = background,
                onBackground = text,
                onSurface = text
            )
        }
}

private val Themes = listOf(
    ThemePalette(
        id = "ocean",
        title = "אוקיינוס",
        description = "כחול עמוק וטורקיז",
        background = Color(0xFF07111F),
        backgroundTop = Color(0xFF0A1628),
        backgroundBottom = Color(0xFF050B14),
        surface = Color(0xFF101D30),
        surfaceHighlight = Color(0xFF172842),
        primary = Color(0xFF62E7FF),
        secondary = Color(0xFF9B8CFF),
        text = Color(0xFFF3F7FF),
        muted = Color(0xFFA8B5C8),
        success = Color(0xFF6EF2B4)
    ),
    ThemePalette(
        id = "forest",
        title = "יער",
        description = "ירוק רגוע עם גווני טבע",
        background = Color(0xFF0C1712),
        backgroundTop = Color(0xFF12251C),
        backgroundBottom = Color(0xFF07100B),
        surface = Color(0xFF17271F),
        surfaceHighlight = Color(0xFF21392D),
        primary = Color(0xFF7BE6A8),
        secondary = Color(0xFFD5B86A),
        text = Color(0xFFF4FAF6),
        muted = Color(0xFFA9BDB0),
        success = Color(0xFF8AF0C0)
    ),
    ThemePalette(
        id = "sunset",
        title = "שקיעה",
        description = "סגול, ורוד וכתום חם",
        background = Color(0xFF180D1B),
        backgroundTop = Color(0xFF2A142E),
        backgroundBottom = Color(0xFF0E0911),
        surface = Color(0xFF2B1930),
        surfaceHighlight = Color(0xFF422247),
        primary = Color(0xFFFF8DAA),
        secondary = Color(0xFFFFC56E),
        text = Color(0xFFFFF4F8),
        muted = Color(0xFFC8ADBE),
        success = Color(0xFF8BE6B3)
    ),
    ThemePalette(
        id = "paper",
        title = "בהיר ונקי",
        description = "רקע בהיר וצבעים רגועים",
        background = Color(0xFFF4F2EC),
        backgroundTop = Color(0xFFFAF9F5),
        backgroundBottom = Color(0xFFECE8DF),
        surface = Color(0xFFFFFFFF),
        surfaceHighlight = Color(0xFFF0ECE4),
        primary = Color(0xFF276D68),
        secondary = Color(0xFF8C5E3C),
        text = Color(0xFF1B2523),
        muted = Color(0xFF66736F),
        success = Color(0xFF2E8B67),
        isLight = true
    )
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val initialThemeId = preferences.getString(ThemePreferenceKey, Themes.first().id)
            ?: Themes.first().id

        setContent {
            var selectedThemeId by remember { mutableStateOf(initialThemeId) }
            val palette = Themes.firstOrNull { it.id == selectedThemeId } ?: Themes.first()

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = palette.colorScheme) {
                    MosaicFitApp(
                        palette = palette,
                        selectedThemeId = selectedThemeId,
                        onThemeSelected = { themeId ->
                            selectedThemeId = themeId
                            preferences.edit().putString(ThemePreferenceKey, themeId).apply()
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun MosaicFitApp(
        palette: ThemePalette,
        selectedThemeId: String,
        onThemeSelected: (String) -> Unit
    ) {
        var destination by remember { mutableStateOf(AppDestination.Today) }
        var latestAnalysis by remember { mutableStateOf<MealAnalysis?>(null) }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                MosaicNavigationBar(
                    palette = palette,
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
                            listOf(palette.backgroundTop, palette.background, palette.backgroundBottom)
                        )
                    )
                    .padding(paddingValues)
            ) {
                when (destination) {
                    AppDestination.Today -> TodayScreen(
                        palette = palette,
                        latestAnalysis = latestAnalysis,
                        onAddMeal = { destination = AppDestination.Analyze }
                    )
                    AppDestination.Analyze -> AnalyzeScreen(
                        palette = palette,
                        onAnalysisReady = {
                            latestAnalysis = it
                            destination = AppDestination.Today
                        }
                    )
                    AppDestination.Insights -> InsightsScreen(palette)
                    AppDestination.Settings -> SettingsScreen(
                        palette = palette,
                        selectedThemeId = selectedThemeId,
                        onThemeSelected = onThemeSelected
                    )
                }
            }
        }
    }

    @Composable
    private fun MosaicNavigationBar(
        palette: ThemePalette,
        selected: AppDestination,
        onSelected: (AppDestination) -> Unit
    ) {
        NavigationBar(containerColor = palette.surface.copy(alpha = 0.97f)) {
            AppDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelected(destination) },
                    icon = { Text(destination.symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                    label = { Text(destination.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = palette.primary,
                        selectedTextColor = palette.primary,
                        indicatorColor = palette.surfaceHighlight,
                        unselectedIconColor = palette.muted,
                        unselectedTextColor = palette.muted
                    )
                )
            }
        }
    }

    @Composable
    private fun TodayScreen(
        palette: ThemePalette,
        latestAnalysis: MealAnalysis?,
        onAddMeal: () -> Unit
    ) {
        val nutrition = latestAnalysis?.nutrition ?: NutritionEstimate(0, 0.0, 0.0, 0.0)
        ScreenColumn {
            Header(palette, "MOSAIC FIT", "היום שלך", "מעקב תזונתי חכם, פשוט וברור")
            GlowCard(palette) {
                Text("סיכום יומי", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(palette, "קלוריות", nutrition.caloriesKcal.toString(), "קק״ל")
                    Metric(palette, "חלבון", formatNumber(nutrition.proteinG), "גרם")
                    Metric(palette, "פחמימות", formatNumber(nutrition.carbohydratesG), "גרם")
                    Metric(palette, "שומן", formatNumber(nutrition.fatG), "גרם")
                }
            }
            SectionTitle("הארוחות שלי")
            if (latestAnalysis == null) {
                EmptyMealCard(palette, onAddMeal)
            } else {
                LatestMealCard(palette, latestAnalysis)
                PrimaryButton(palette, "הוספת ארוחה נוספת", onAddMeal)
            }
            GlowCard(palette) {
                Text("המבט הבא", color = palette.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "בהמשך נחבר שמירה מקומית, יעדים אישיים וסיכומים שבועיים וחודשיים.",
                    color = palette.muted
                )
            }
        }
    }

    @Composable
    private fun AnalyzeScreen(palette: ThemePalette, onAnalysisReady: (MealAnalysis) -> Unit) {
        var serverUrl by remember { mutableStateOf("http://192.168.1.200:8000") }
        var selectedImage by remember { mutableStateOf<Uri?>(null) }
        var message by remember { mutableStateOf("בחר תמונה של הארוחה כדי להתחיל") }
        var analysis by remember { mutableStateOf<MealAnalysis?>(null) }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            selectedImage = uri
            analysis = null
            message = if (uri == null) "לא נבחרה תמונה" else "התמונה מוכנה לניתוח"
        }

        ScreenColumn {
            Header(palette, "AI MEAL SCAN", "ניתוח ארוחה", "בחר תמונה וקבל הערכה תזונתית")
            GlowCard(palette) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("כתובת השרת") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.primary,
                        focusedLabelColor = palette.primary,
                        cursorColor = palette.primary
                    )
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) { Text("בחירת תמונת ארוחה") }
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
                                .onSuccess { analysis = it; message = "הניתוח הושלם" }
                                .onFailure { message = "הניתוח נכשל: ${it.message}" }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.primary,
                        contentColor = palette.background
                    )
                ) { Text("ניתוח באמצעות AI", fontWeight = FontWeight.Bold) }
            }
            if (loading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = palette.primary)
                }
            }
            Text(message, color = palette.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            analysis?.let {
                MealAnalysisResult(palette, it)
                Button(
                    onClick = { onAnalysisReady(it) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.success,
                        contentColor = palette.background
                    )
                ) { Text("הוספה למעקב היומי", fontWeight = FontWeight.Bold) }
            }
        }
    }

    @Composable
    private fun InsightsScreen(palette: ThemePalette) {
        ScreenColumn {
            Header(palette, "MOSAIC INSIGHTS", "מגמות ותובנות", "השבוע והחודש שלך במקום אחד")
            PlaceholderInsightCard(palette, "שבועי", "ממוצעי קלוריות ומאקרו, עקביות וימי אימון")
            PlaceholderInsightCard(palette, "חודשי", "מגמות משקל, צריכת חלבון והתקדמות לעבר היעד")
        }
    }

    @Composable
    private fun SettingsScreen(
        palette: ThemePalette,
        selectedThemeId: String,
        onThemeSelected: (String) -> Unit
    ) {
        ScreenColumn {
            Header(palette, "PERSONALIZE", "הגדרות", "התאם את Mosaic Fit לטעם שלך")
            SectionTitle("ערכת צבעים")
            Text(
                "הבחירה נשמרת במכשיר ותיטען אוטומטית בפעם הבאה.",
                color = palette.muted
            )
            Themes.forEach { option ->
                ThemeOptionCard(
                    currentPalette = palette,
                    option = option,
                    selected = option.id == selectedThemeId,
                    onClick = { onThemeSelected(option.id) }
                )
            }
        }
    }

    @Composable
    private fun ThemeOptionCard(
        currentPalette: ThemePalette,
        option: ThemePalette,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selected) Modifier.border(2.dp, currentPalette.primary, RoundedCornerShape(22.dp))
                    else Modifier
                )
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = currentPalette.surface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(option.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(option.description, color = currentPalette.muted)
                    if (selected) Text("נבחרה", color = currentPalette.primary, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeDot(option.background)
                    ThemeDot(option.primary)
                    ThemeDot(option.secondary)
                }
            }
        }
    }

    @Composable
    private fun ThemeDot(color: Color) {
        Box(Modifier.size(24.dp).background(color, CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
    }

    @Composable
    private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content
        )
    }

    @Composable
    private fun Header(palette: ThemePalette, eyebrow: String, title: String, subtitle: String) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(eyebrow, color = palette.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, color = palette.muted)
        }
    }

    @Composable
    private fun GlowCard(palette: ThemePalette, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().background(
                    Brush.linearGradient(listOf(palette.surfaceHighlight, palette.surface, palette.surface))
                ).padding(20.dp),
                content = content
            )
        }
    }

    @Composable
    private fun Metric(palette: ThemePalette, label: String, value: String, unit: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(unit, color = palette.primary, fontSize = 11.sp)
            Text(label, color = palette.muted, fontSize = 12.sp)
        }
    }

    @Composable
    private fun SectionTitle(title: String) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }

    @Composable
    private fun PrimaryButton(palette: ThemePalette, label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.primary, contentColor = palette.background)
        ) { Text(label, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun EmptyMealCard(palette: ThemePalette, onAddMeal: () -> Unit) {
        GlowCard(palette) {
            Text("עדיין לא נוספה ארוחה היום", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("הוסף צילום ראשון כדי להתחיל לבנות את הסיכום היומי.", color = palette.muted)
            Spacer(Modifier.height(16.dp))
            PrimaryButton(palette, "הוספת ארוחה", onAddMeal)
        }
    }

    @Composable
    private fun LatestMealCard(palette: ThemePalette, analysis: MealAnalysis) {
        GlowCard(palette) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("הארוחה האחרונה", color = palette.primary, fontWeight = FontWeight.Bold)
                    Text(analysis.items.firstOrNull()?.name ?: "ארוחה שנותחה", fontWeight = FontWeight.Bold)
                }
                Text("${analysis.nutrition.caloriesKcal} קק״ל", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "חלבון ${formatNumber(analysis.nutrition.proteinG)} ג׳  •  פחמימות ${formatNumber(analysis.nutrition.carbohydratesG)} ג׳  •  שומן ${formatNumber(analysis.nutrition.fatG)} ג׳",
                color = palette.muted
            )
        }
    }

    @Composable
    private fun PlaceholderInsightCard(palette: ThemePalette, title: String, description: String) {
        GlowCard(palette) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(description, color = palette.muted)
            Spacer(Modifier.height(14.dp))
            Text("הנתונים יופיעו לאחר שמירת ארוחות לאורך זמן", color = palette.secondary)
        }
    }

    @Composable
    private fun MealAnalysisResult(palette: ThemePalette, analysis: MealAnalysis) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlowCard(palette) {
                Text("הערכה תזונתית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                NutritionRow(palette, "קלוריות", "${analysis.nutrition.caloriesKcal} קק״ל")
                NutritionRow(palette, "חלבון", "${formatNumber(analysis.nutrition.proteinG)} גרם")
                NutritionRow(palette, "פחמימות", "${formatNumber(analysis.nutrition.carbohydratesG)} גרם")
                NutritionRow(palette, "שומן", "${formatNumber(analysis.nutrition.fatG)} גרם")
            }
            GlowCard(palette) {
                Text("מזונות שזוהו", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                analysis.items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = palette.muted.copy(alpha = 0.25f))
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(item.name, fontWeight = FontWeight.Bold)
                        Text(item.estimatedQuantity, color = palette.muted)
                        Text("רמת ביטחון: ${(item.confidence * 100).toInt()}%", color = palette.primary)
                    }
                }
            }
            TextListCard(palette, "הנחות", analysis.assumptions, "לא דווחו הנחות")
            TextListCard(palette, "שאלות לאישור", analysis.confirmationQuestions, "לא נדרש אישור נוסף")
        }
    }

    @Composable
    private fun NutritionRow(palette: ThemePalette, label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = palette.muted)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun TextListCard(palette: ThemePalette, title: String, values: List<String>, emptyText: String) {
        GlowCard(palette) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (values.isEmpty()) Text(emptyText, color = palette.muted)
            else values.forEach { Text("• $it", color = palette.muted, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }

    private suspend fun uploadMeal(serverUrl: String, uri: Uri): MealAnalysis = withContext(Dispatchers.IO) {
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
            val responseText = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error("השרת החזיר ${connection.responseCode}: $responseText")
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
                MealItem(item.getString("name"), item.getString("estimated_quantity"), item.getDouble("confidence"))
            },
            nutrition = NutritionEstimate(
                nutritionJson.getInt("calories_kcal"),
                nutritionJson.getDouble("protein_g"),
                nutritionJson.getDouble("carbohydrates_g"),
                nutritionJson.getDouble("fat_g")
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
    Insights("מגמות", "⌁"),
    Settings("הגדרות", "⚙")
}

data class MealAnalysis(
    val analysisId: String,
    val status: String,
    val items: List<MealItem>,
    val nutrition: NutritionEstimate,
    val assumptions: List<String>,
    val confirmationQuestions: List<String>
)

data class MealItem(val name: String, val estimatedQuantity: String, val confidence: Double)

data class NutritionEstimate(
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbohydratesG: Double,
    val fatG: Double
)
