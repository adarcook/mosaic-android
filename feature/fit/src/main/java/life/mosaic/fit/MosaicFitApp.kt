package life.mosaic.fit

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.mosaic.fit.data.MealAnalysis
import life.mosaic.fit.data.MealJournal
import life.mosaic.fit.data.NutritionEstimate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun MosaicFitRoot(
    palette: ThemePalette,
    journal: MealJournal,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit,
    dailyCalorieGoal: Int,
    onDailyCalorieGoalChanged: (Int) -> Unit,
    dailyProteinGoalG: Int,
    onDailyProteinGoalChanged: (Int) -> Unit
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
            Modifier
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
                    allMeals = meals,
                    loading = loadingHistory,
                    dailyCalorieGoal = dailyCalorieGoal,
                    dailyProteinGoalG = dailyProteinGoalG,
                    onAddMeal = { destination = AppDestination.Analyze }
                )
                AppDestination.Analyze -> AnalyzeMealScreen(
                    palette = palette,
                    serverUrl = serverUrl,
                    onServerUrlChanged = onServerUrlChanged,
                    onSave = ::saveMeal
                )
                AppDestination.Insights -> InsightsScreen(palette, meals)
                AppDestination.Settings -> SettingsScreen(
                    palette = palette,
                    selectedThemeId = selectedThemeId,
                    onThemeSelected = onThemeSelected,
                    dailyCalorieGoal = dailyCalorieGoal,
                    onDailyCalorieGoalChanged = onDailyCalorieGoalChanged,
                    dailyProteinGoalG = dailyProteinGoalG,
                    onDailyProteinGoalChanged = onDailyProteinGoalChanged
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
    allMeals: List<MealAnalysis>,
    loading: Boolean,
    dailyCalorieGoal: Int,
    dailyProteinGoalG: Int,
    onAddMeal: () -> Unit
) {
    val meals = allMeals.filter { it.localDate() == LocalDate.now() }
    val nutrition = meals.fold(NutritionEstimate(0, 0.0, 0.0, 0.0)) { total, meal ->
        total + meal.nutrition
    }

    ScreenColumn {
        Header(palette, "MOSAIC FIT", "היום שלך", "הארוחות והחישובים נשמרים במכשיר באופן מקומי")
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
        GlowCard(palette) {
            Text("התקדמות ליעדים", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            GoalProgressRow(
                palette = palette,
                label = "קלוריות",
                progress = calculateGoalProgress(nutrition.caloriesKcal.toDouble(), dailyCalorieGoal.toDouble()),
                unit = "קק״ל"
            )
            Spacer(Modifier.height(16.dp))
            GoalProgressRow(
                palette = palette,
                label = "חלבון",
                progress = calculateGoalProgress(nutrition.proteinG, dailyProteinGoalG.toDouble()),
                unit = "גרם"
            )
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
private fun GoalProgressRow(
    palette: ThemePalette,
    label: String,
    progress: GoalProgress,
    unit: String
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.Bold)
        Text(
            "${formatNumber(progress.consumed)} / ${formatNumber(progress.goal)} $unit",
            color = palette.muted
        )
    }
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { progress.fraction },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = palette.primary,
        trackColor = palette.surfaceHighlight
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (progress.remaining > 0.0) {
            "נשארו ${formatNumber(progress.remaining)} $unit"
        } else {
            "הגעת ליעד היומי"
        },
        color = if (progress.remaining > 0.0) palette.muted else palette.success,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun InsightsScreen(palette: ThemePalette, meals: List<MealAnalysis>) {
    val lastSevenDays = (0L..6L).map { LocalDate.now().minusDays(it) }
    val recent = meals.filter { it.localDate() in lastSevenDays }
    val days = recent.map { it.localDate() }.distinct().size
    val averageCalories = if (days == 0) 0 else recent.sumOf { it.nutrition.caloriesKcal } / days
    val averageProtein = if (days == 0) 0.0 else recent.sumOf { it.nutrition.proteinG } / days

    ScreenColumn {
        Header(palette, "MOSAIC INSIGHTS", "מגמות ותובנות", "סיכום בסיסי מתוך הנתונים המקומיים")
        GlowCard(palette) {
            Text("7 הימים האחרונים", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            NutritionRow(palette, "ימים עם מעקב", "$days מתוך 7")
            NutritionRow(palette, "ממוצע קלוריות", "$averageCalories קק״ל")
            NutritionRow(palette, "ממוצע חלבון", "${formatNumber(averageProtein)} גרם")
            NutritionRow(palette, "ארוחות שנשמרו", recent.size.toString())
        }
    }
}

@Composable
private fun SettingsScreen(
    palette: ThemePalette,
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit,
    dailyCalorieGoal: Int,
    onDailyCalorieGoalChanged: (Int) -> Unit,
    dailyProteinGoalG: Int,
    onDailyProteinGoalChanged: (Int) -> Unit
) {
    var calorieGoalText by remember(dailyCalorieGoal) { mutableStateOf(dailyCalorieGoal.toString()) }
    var proteinGoalText by remember(dailyProteinGoalG) { mutableStateOf(dailyProteinGoalG.toString()) }
    val calorieValue = calorieGoalText.toIntOrNull()
    val proteinValue = proteinGoalText.toIntOrNull()
    val validGoals = calorieValue != null && calorieValue > 0 && proteinValue != null && proteinValue > 0

    ScreenColumn {
        Header(palette, "PERSONALIZE", "הגדרות", "התאם את Mosaic Fit לטעם וליעדים שלך")
        SectionTitle("יעדים יומיים")
        GlowCard(palette) {
            Text("היעדים נשמרים מקומית במכשיר ומשמשים לחישוב ההתקדמות במסך היום.", color = palette.muted)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = calorieGoalText,
                onValueChange = { calorieGoalText = it.filter(Char::isDigit) },
                label = { Text("יעד קלוריות יומי") },
                suffix = { Text("קק״ל") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = proteinGoalText,
                onValueChange = { proteinGoalText = it.filter(Char::isDigit) },
                label = { Text("יעד חלבון יומי") },
                suffix = { Text("גרם") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = validGoals,
                onClick = {
                    onDailyCalorieGoalChanged(requireNotNull(calorieValue))
                    onDailyProteinGoalChanged(requireNotNull(proteinValue))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.primary,
                    contentColor = palette.background
                )
            ) {
                Text("שמירת יעדים", fontWeight = FontWeight.Bold)
            }
        }

        SectionTitle("ערכת צבעים")
        Themes.forEach { option ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (option.id == selectedThemeId) {
                            Modifier.border(2.dp, palette.primary, RoundedCornerShape(22.dp))
                        } else Modifier
                    )
                    .clickable { onThemeSelected(option.id) },
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(option.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(option.description, color = palette.muted)
                        if (option.id == selectedThemeId) {
                            Text("נבחרה", color = palette.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeDot(option.background)
                        ThemeDot(option.primary)
                        ThemeDot(option.secondary)
                    }
                }
            }
        }
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
            "חלבון ${formatNumber(meal.nutrition.proteinG)} ג׳  •  " +
                "פחמימות ${formatNumber(meal.nutrition.carbohydratesG)} ג׳  •  " +
                "שומן ${formatNumber(meal.nutrition.fatG)} ג׳",
            color = palette.muted
        )
        if (meal.userEdited) {
            Text("נערכה ידנית", color = palette.success, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content
    )
}

@Composable
internal fun Header(palette: ThemePalette, eyebrow: String, title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, color = palette.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = palette.muted)
    }
}

@Composable
internal fun GlowCard(palette: ThemePalette, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(palette.surfaceHighlight, palette.surface, palette.surface)
                    )
                )
                .padding(20.dp),
            content = content
        )
    }
}

@Composable
internal fun NutritionRow(palette: ThemePalette, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = palette.muted)
        Text(value, fontWeight = FontWeight.Bold)
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
internal fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun PrimaryButton(palette: ThemePalette, label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.primary,
            contentColor = palette.background
        )
    ) {
        Text(label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyMealCard(palette: ThemePalette, onAddMeal: () -> Unit) {
    GlowCard(palette) {
        Text("עדיין לא נוספה ארוחה היום", fontWeight = FontWeight.Bold)
        Text("צלם ארוחה ראשונה כדי להתחיל לבנות את הסיכום היומי.", color = palette.muted)
        PrimaryButton(palette, "צילום ארוחה", onAddMeal)
    }
}

@Composable
private fun ThemeDot(color: Color) {
    Box(
        Modifier
            .size(24.dp)
            .background(color, CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
    )
}

internal data class GoalProgress(
    val consumed: Double,
    val goal: Double,
    val remaining: Double,
    val fraction: Float
)

internal fun calculateGoalProgress(consumed: Double, goal: Double): GoalProgress {
    val safeConsumed = consumed.coerceAtLeast(0.0)
    val safeGoal = goal.coerceAtLeast(1.0)
    return GoalProgress(
        consumed = safeConsumed,
        goal = safeGoal,
        remaining = (safeGoal - safeConsumed).coerceAtLeast(0.0),
        fraction = (safeConsumed / safeGoal).coerceIn(0.0, 1.0).toFloat()
    )
}

internal fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

private operator fun NutritionEstimate.plus(other: NutritionEstimate) = NutritionEstimate(
    caloriesKcal + other.caloriesKcal,
    proteinG + other.proteinG,
    carbohydratesG + other.carbohydratesG,
    fatG + other.fatG
)

private fun MealAnalysis.localDate(): LocalDate =
    Instant.ofEpochMilli(createdAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun MealAnalysis.formattedTime(): String =
    Instant.ofEpochMilli(createdAtEpochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private enum class AppDestination(val label: String, val symbol: String) {
    Today("היום", "◉"),
    Analyze("ניתוח", "✦"),
    Insights("מגמות", "⌁"),
    Settings("הגדרות", "⚙")
}
