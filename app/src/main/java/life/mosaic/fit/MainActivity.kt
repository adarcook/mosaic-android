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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.mosaic.fit.data.MealAnalysis
import life.mosaic.fit.data.MealItem
import life.mosaic.fit.data.MealJournal
import life.mosaic.fit.data.NutritionEstimate
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val PreferencesName = "mosaic_fit_preferences"
private const val ThemePreferenceKey = "selected_theme"
private const val ServerUrlPreferenceKey = "server_url"
private const val DefaultServerUrl = "http://192.168.1.200:8000"

private data class ThemePalette(
    val id: String, val title: String, val description: String,
    val background: Color, val backgroundTop: Color, val backgroundBottom: Color,
    val surface: Color, val surfaceHighlight: Color, val primary: Color,
    val secondary: Color, val text: Color, val muted: Color, val success: Color,
    val isLight: Boolean = false
) {
    val colorScheme get() = if (isLight) lightColorScheme(
        primary = primary, secondary = secondary, background = background, surface = surface,
        onPrimary = background, onBackground = text, onSurface = text
    ) else darkColorScheme(
        primary = primary, secondary = secondary, background = background, surface = surface,
        onPrimary = background, onBackground = text, onSurface = text
    )
}

private val Themes = listOf(
    ThemePalette("ocean", "אוקיינוס", "כחול עמוק וטורקיז", Color(0xFF07111F), Color(0xFF0A1628), Color(0xFF050B14), Color(0xFF101D30), Color(0xFF172842), Color(0xFF62E7FF), Color(0xFF9B8CFF), Color(0xFFF3F7FF), Color(0xFFA8B5C8), Color(0xFF6EF2B4)),
    ThemePalette("forest", "יער", "ירוק רגוע עם גווני טבע", Color(0xFF0C1712), Color(0xFF12251C), Color(0xFF07100B), Color(0xFF17271F), Color(0xFF21392D), Color(0xFF7BE6A8), Color(0xFFD5B86A), Color(0xFFF4FAF6), Color(0xFFA9BDB0), Color(0xFF8AF0C0)),
    ThemePalette("sunset", "שקיעה", "סגול, ורוד וכתום חם", Color(0xFF180D1B), Color(0xFF2A142E), Color(0xFF0E0911), Color(0xFF2B1930), Color(0xFF422247), Color(0xFFFF8DAA), Color(0xFFFFC56E), Color(0xFFFFF4F8), Color(0xFFC8ADBE), Color(0xFF8BE6B3)),
    ThemePalette("paper", "בהיר ונקי", "רקע בהיר וצבעים רגועים", Color(0xFFF4F2EC), Color(0xFFFAF9F5), Color(0xFFECE8DF), Color.White, Color(0xFFF0ECE4), Color(0xFF276D68), Color(0xFF8C5E3C), Color(0xFF1B2523), Color(0xFF66736F), Color(0xFF2E8B67), true)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val initialTheme = preferences.getString(ThemePreferenceKey, Themes.first().id) ?: Themes.first().id
        val initialServer = preferences.getString(ServerUrlPreferenceKey, DefaultServerUrl) ?: DefaultServerUrl
        val journal = MealJournal(this)
        setContent {
            var themeId by remember { mutableStateOf(initialTheme) }
            var serverUrl by remember { mutableStateOf(initialServer) }
            val palette = Themes.firstOrNull { it.id == themeId } ?: Themes.first()
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = palette.colorScheme) {
                    MosaicFitApp(palette, journal, serverUrl, {
                        serverUrl = it
                        preferences.edit().putString(ServerUrlPreferenceKey, it).apply()
                    }, themeId, {
                        themeId = it
                        preferences.edit().putString(ThemePreferenceKey, it).apply()
                    })
                }
            }
        }
    }

    @Composable
    private fun MosaicFitApp(
        palette: ThemePalette,
        journal: MealJournal,
        serverUrl: String,
        onServerUrlChanged: (String) -> Unit,
        selectedThemeId: String,
        onThemeSelected: (String) -> Unit
    ) {
        var destination by remember { mutableStateOf(AppDestination.Today) }
        val meals = remember { mutableStateListOf<MealAnalysis>() }
        var loadingHistory by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            meals.clear()
            meals.addAll(withContext(Dispatchers.IO) { journal.allMeals() })
            loadingHistory = false
        }

        fun saveMeal(meal: MealAnalysis) {
            scope.launch {
                withContext(Dispatchers.IO) { journal.save(meal) }
                meals.removeAll { it.analysisId == meal.analysisId }
                meals.add(0, meal)
                destination = AppDestination.Today
            }
        }

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = { MosaicNavigationBar(palette, destination) { destination = it } }
        ) { paddingValues ->
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(listOf(palette.backgroundTop, palette.background, palette.backgroundBottom)))
                    .padding(paddingValues)
            ) {
                when (destination) {
                    AppDestination.Today -> TodayScreen(palette, meals, loadingHistory) { destination = AppDestination.Analyze }
                    AppDestination.Analyze -> AnalyzeScreen(palette, serverUrl, onServerUrlChanged, ::saveMeal)
                    AppDestination.Insights -> InsightsScreen(palette, meals)
                    AppDestination.Settings -> SettingsScreen(palette, selectedThemeId, onThemeSelected)
                }
            }
        }
    }

    @Composable
    private fun MosaicNavigationBar(palette: ThemePalette, selected: AppDestination, onSelected: (AppDestination) -> Unit) {
        NavigationBar(containerColor = palette.surface.copy(alpha = 0.97f)) {
            AppDestination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelected(destination) },
                    icon = { Text(destination.symbol, fontSize = 20.sp, fontWeight = FontWeight.Bold) },
                    label = { Text(destination.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = palette.primary, selectedTextColor = palette.primary,
                        indicatorColor = palette.surfaceHighlight,
                        unselectedIconColor = palette.muted, unselectedTextColor = palette.muted
                    )
                )
            }
        }
    }

    @Composable
    private fun TodayScreen(palette: ThemePalette, allMeals: List<MealAnalysis>, loading: Boolean, onAddMeal: () -> Unit) {
        val meals = allMeals.filter { it.localDate() == LocalDate.now() }
        val nutrition = meals.fold(NutritionEstimate(0, 0.0, 0.0, 0.0)) { total, meal -> total + meal.nutrition }
        ScreenColumn {
            Header(palette, "MOSAIC FIT", "היום שלך", "הארוחות נשמרות במכשיר באופן מקומי")
            GlowCard(palette) {
                Text("סיכום יומי", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric(palette, "קלוריות", nutrition.caloriesKcal.toString(), "קק״ל")
                    Metric(palette, "חלבון", formatNumber(nutrition.proteinG), "גרם")
                    Metric(palette, "פחמימות", formatNumber(nutrition.carbohydratesG), "גרם")
                    Metric(palette, "שומן", formatNumber(nutrition.fatG), "גרם")
                }
            }
            SectionTitle("הארוחות שלי")
            when {
                loading -> CircularProgressIndicator(color = palette.primary)
                meals.isEmpty() -> EmptyMealCard(palette, onAddMeal)
                else -> {
                    meals.forEach { SavedMealCard(palette, it) }
                    PrimaryButton(palette, "הוספת ארוחה נוספת", onAddMeal)
                }
            }
        }
    }

    @Composable
    private fun AnalyzeScreen(
        palette: ThemePalette,
        serverUrl: String,
        onServerUrlChanged: (String) -> Unit,
        onSave: (MealAnalysis) -> Unit
    ) {
        var selectedImage by remember { mutableStateOf<Uri?>(null) }
        var message by remember { mutableStateOf("בחר תמונה של הארוחה כדי להתחיל") }
        var analysis by remember { mutableStateOf<MealAnalysis?>(null) }
        var editing by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            selectedImage = uri
            analysis = null
            editing = false
            message = if (uri == null) "לא נבחרה תמונה" else "התמונה מוכנה לניתוח"
        }

        ScreenColumn {
            Header(palette, "AI MEAL SCAN", "ניתוח ארוחה", "נתח, תקן במקרה הצורך ושמור ביומן")
            GlowCard(palette) {
                OutlinedTextField(
                    value = serverUrl, onValueChange = onServerUrlChanged,
                    label = { Text("כתובת השרת") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.primary, focusedLabelColor = palette.primary)
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Text("בחירת תמונת ארוחה")
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = selectedImage != null && !loading,
                    onClick = {
                        val uri = selectedImage ?: return@Button
                        loading = true
                        analysis = null
                        editing = false
                        message = "מנתח את הארוחה…"
                        scope.launch {
                            runCatching { uploadMeal(serverUrl, uri) }
                                .onSuccess { analysis = it; message = "הניתוח הושלם" }
                                .onFailure { message = "הניתוח נכשל: ${it.message}" }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary, contentColor = palette.background)
                ) { Text("ניתוח באמצעות AI", fontWeight = FontWeight.Bold) }
            }
            if (loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(color = palette.primary) }
            Text(message, color = palette.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

            analysis?.let { result ->
                if (editing) {
                    MealAnalysisEditor(palette, result, { editing = false }, onSave)
                } else {
                    MealAnalysisResult(palette, result)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                            Text("עריכת הניתוח")
                        }
                        Button(
                            onClick = { onSave(result) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.success, contentColor = palette.background)
                        ) { Text("שמירה למעקב", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    @Composable
    private fun MealAnalysisEditor(
        palette: ThemePalette,
        initial: MealAnalysis,
        onCancel: () -> Unit,
        onSave: (MealAnalysis) -> Unit
    ) {
        val items = remember(initial.analysisId) {
            mutableStateListOf<EditableItem>().apply {
                addAll(initial.items.map { EditableItem(it.name, it.estimatedQuantity) })
            }
        }
        var calories by remember(initial.analysisId) { mutableStateOf(initial.nutrition.caloriesKcal.toString()) }
        var protein by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.proteinG)) }
        var carbs by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.carbohydratesG)) }
        var fat by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.fatG)) }

        fun applyMultiplier(multiplier: Double) {
            calories = (initial.nutrition.caloriesKcal * multiplier).toInt().toString()
            protein = formatNumber(initial.nutrition.proteinG * multiplier)
            carbs = formatNumber(initial.nutrition.carbohydratesG * multiplier)
            fat = formatNumber(initial.nutrition.fatG * multiplier)
        }

        GlowCard(palette) {
            Text("עריכת הניתוח", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("הערכים המקוריים של ה-AI יישמרו לצד התיקון שלך.", color = palette.muted)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("חצי מנה" to 0.5, "מנה מלאה" to 1.0, "שתי מנות" to 2.0).forEach { (label, multiplier) ->
                    OutlinedButton(onClick = { applyMultiplier(multiplier) }, modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            NumericField("קלוריות", calories) { calories = it }
            NumericField("חלבון (גרם)", protein) { protein = it }
            NumericField("פחמימות (גרם)", carbs) { carbs = it }
            NumericField("שומן (גרם)", fat) { fat = it }
        }

        GlowCard(palette) {
            Text("מזונות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = palette.muted.copy(alpha = 0.25f))
                OutlinedTextField(
                    value = item.name, onValueChange = { items[index] = item.copy(name = it) },
                    label = { Text("שם המזון") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = item.quantity, onValueChange = { items[index] = item.copy(quantity = it) },
                    label = { Text("כמות") }, modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { items.removeAt(index) }) { Text("מחיקת מזון", color = palette.secondary) }
            }
            OutlinedButton(onClick = { items.add(EditableItem("", "")) }, modifier = Modifier.fillMaxWidth()) {
                Text("+ הוספת מזון")
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
                Text("ביטול")
            }
            Button(
                enabled = calories.toIntOrNull() != null && listOf(protein, carbs, fat).all { it.toDoubleOrNull() != null },
                onClick = {
                    val editedItems = items.filter { it.name.isNotBlank() }.map {
                        MealItem(it.name.trim(), it.quantity.trim(), 1.0)
                    }
                    onSave(initial.copy(
                        status = "confirmed",
                        items = editedItems,
                        nutrition = NutritionEstimate(calories.toInt(), protein.toDouble(), carbs.toDouble(), fat.toDouble()),
                        originalItems = initial.originalItems.ifEmpty { initial.items },
                        originalNutrition = initial.originalNutrition ?: initial.nutrition,
                        userEdited = true
                    ))
                },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.success, contentColor = palette.background)
            ) { Text("שמירת התיקון", fontWeight = FontWeight.Bold) }
        }
    }

    @Composable
    private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = { candidate ->
                if (candidate.isEmpty() || candidate.matches(Regex("\\d*(\\.\\d*)?"))) onValueChange(candidate)
            },
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
    }

    @Composable
    private fun InsightsScreen(palette: ThemePalette, meals: List<MealAnalysis>) {
        val lastSevenDays = (0L..6L).map { LocalDate.now().minusDays(it) }
        val recent = meals.filter { it.localDate() in lastSevenDays }
        val days = recent.map { it.localDate() }.distinct().size
        val avgCalories = if (days == 0) 0 else recent.sumOf { it.nutrition.caloriesKcal } / days
        val avgProtein = if (days == 0) 0.0 else recent.sumOf { it.nutrition.proteinG } / days
        ScreenColumn {
            Header(palette, "MOSAIC INSIGHTS", "מגמות ותובנות", "סיכום בסיסי מתוך הנתונים המקומיים")
            GlowCard(palette) {
                Text("7 הימים האחרונים", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                NutritionRow(palette, "ימים עם מעקב", "$days מתוך 7")
                NutritionRow(palette, "ממוצע קלוריות", "$avgCalories קק״ל")
                NutritionRow(palette, "ממוצע חלבון", "${formatNumber(avgProtein)} גרם")
                NutritionRow(palette, "ארוחות שנשמרו", recent.size.toString())
            }
        }
    }

    @Composable
    private fun SettingsScreen(palette: ThemePalette, selectedThemeId: String, onThemeSelected: (String) -> Unit) {
        ScreenColumn {
            Header(palette, "PERSONALIZE", "הגדרות", "התאם את Mosaic Fit לטעם שלך")
            SectionTitle("ערכת צבעים")
            Themes.forEach { option ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (option.id == selectedThemeId) Modifier.border(2.dp, palette.primary, RoundedCornerShape(22.dp)) else Modifier)
                        .clickable { onThemeSelected(option.id) },
                    shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = palette.surface)
                ) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(option.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(option.description, color = palette.muted)
                            if (option.id == selectedThemeId) Text("נבחרה", color = palette.primary, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeDot(option.background); ThemeDot(option.primary); ThemeDot(option.secondary)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MealAnalysisResult(palette: ThemePalette, analysis: MealAnalysis) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlowCard(palette) {
                Text("הערכה תזונתית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        }
    }

    @Composable
    private fun SavedMealCard(palette: ThemePalette, meal: MealAnalysis) {
        GlowCard(palette) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(meal.items.firstOrNull()?.name ?: "ארוחה שנותחה", fontWeight = FontWeight.Bold)
                    Text(meal.formattedTime(), color = palette.muted)
                }
                Text("${meal.nutrition.caloriesKcal} קק״ל", fontWeight = FontWeight.Bold)
            }
            Text(
                "חלבון ${formatNumber(meal.nutrition.proteinG)} ג׳  •  פחמימות ${formatNumber(meal.nutrition.carbohydratesG)} ג׳  •  שומן ${formatNumber(meal.nutrition.fatG)} ג׳",
                color = palette.muted
            )
            if (meal.userEdited) Text("נערכה ידנית", color = palette.success, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), content = content
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
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(palette.surfaceHighlight, palette.surface, palette.surface)))
                    .padding(20.dp), content = content
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

    @Composable private fun SectionTitle(title: String) = Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

    @Composable
    private fun PrimaryButton(palette: ThemePalette, label: String, onClick: () -> Unit) {
        Button(
            onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = palette.primary, contentColor = palette.background)
        ) { Text(label, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun EmptyMealCard(palette: ThemePalette, onAddMeal: () -> Unit) {
        GlowCard(palette) {
            Text("עדיין לא נוספה ארוחה היום", fontWeight = FontWeight.Bold)
            Text("הוסף צילום ראשון כדי להתחיל לבנות את הסיכום היומי.", color = palette.muted)
            PrimaryButton(palette, "הוספת ארוחה", onAddMeal)
        }
    }

    @Composable
    private fun NutritionRow(palette: ThemePalette, label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = palette.muted); Text(value, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun TextListCard(palette: ThemePalette, title: String, values: List<String>, emptyText: String) {
        GlowCard(palette) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (values.isEmpty()) Text(emptyText, color = palette.muted)
            else values.forEach { Text("• $it", color = palette.muted) }
        }
    }

    @Composable
    private fun ThemeDot(color: Color) {
        Box(Modifier.size(24.dp).background(color, CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
    }

    private suspend fun uploadMeal(serverUrl: String, uri: Uri): MealAnalysis = withContext(Dispatchers.IO) {
        val imageBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("לא ניתן לקרוא את התמונה שנבחרה")
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
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", body.size.toString())
        }
        try {
            connection.outputStream.use { it.write(body) }
            val responseText = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error("השרת החזיר ${connection.responseCode}: $responseText")
            parseMealAnalysis(JSONObject(responseText))
        } finally { connection.disconnect() }
    }

    private fun parseMealAnalysis(json: JSONObject): MealAnalysis {
        val n = json.getJSONObject("nutrition")
        val itemsJson = json.getJSONArray("items")
        val assumptionsJson = json.getJSONArray("assumptions")
        val questionsJson = json.getJSONArray("confirmation_questions")
        val items = List(itemsJson.length()) { index ->
            itemsJson.getJSONObject(index).let { MealItem(it.getString("name"), it.getString("estimated_quantity"), it.getDouble("confidence")) }
        }
        val nutrition = NutritionEstimate(n.getInt("calories_kcal"), n.getDouble("protein_g"), n.getDouble("carbohydrates_g"), n.getDouble("fat_g"))
        return MealAnalysis(
            analysisId = json.getString("analysis_id"), status = json.getString("status"), items = items, nutrition = nutrition,
            assumptions = List(assumptionsJson.length()) { assumptionsJson.getString(it) },
            confirmationQuestions = List(questionsJson.length()) { questionsJson.getString(it) },
            originalItems = items, originalNutrition = nutrition
        )
    }
}

private data class EditableItem(val name: String, val quantity: String)
private operator fun NutritionEstimate.plus(other: NutritionEstimate) = NutritionEstimate(caloriesKcal + other.caloriesKcal, proteinG + other.proteinG, carbohydratesG + other.carbohydratesG, fatG + other.fatG)
private fun MealAnalysis.localDate(): LocalDate = Instant.ofEpochMilli(createdAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
private fun MealAnalysis.formattedTime(): String = Instant.ofEpochMilli(createdAtEpochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
private enum class AppDestination(val label: String, val symbol: String) { Today("היום", "◉"), Analyze("ניתוח", "✦"), Insights("מגמות", "⌁"), Settings("הגדרות", "⚙") }
