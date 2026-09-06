package life.mosaic.fit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import life.mosaic.fit.data.MealAnalysis
import life.mosaic.fit.data.MealItem
import life.mosaic.fit.data.NutritionEstimate

internal data class SavedMealEditItem(
    val name: String,
    val quantity: String
)

internal fun applySavedMealEdit(
    initial: MealAnalysis,
    items: List<SavedMealEditItem>,
    nutrition: NutritionEstimate
): MealAnalysis {
    val editedItems = items
        .filter { it.name.isNotBlank() }
        .map { MealItem(it.name.trim(), it.quantity.trim(), 1.0) }

    require(editedItems.isNotEmpty()) { "At least one meal item is required" }

    return initial.copy(
        status = "confirmed",
        items = editedItems,
        nutrition = nutrition,
        originalItems = initial.originalItems.ifEmpty { initial.items },
        originalNutrition = initial.originalNutrition ?: initial.nutrition,
        userEdited = true
    )
}

@Composable
internal fun SavedMealCard(
    palette: ThemePalette,
    meal: MealAnalysis,
    onSave: (MealAnalysis) -> Unit,
    onDelete: (MealAnalysis) -> Unit
) {
    var editing by remember(meal.analysisId) { mutableStateOf(false) }
    var confirmingDelete by remember(meal.analysisId) { mutableStateOf(false) }

    if (editing) {
        SavedMealEditor(
            palette = palette,
            initial = meal,
            onCancel = { editing = false },
            onSave = {
                editing = false
                onSave(it)
            }
        )
    } else {
        GlowCard(palette) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(meal.items.firstOrNull()?.name ?: "ארוחה", fontWeight = FontWeight.Bold)
                    Text(meal.formattedMealTime(), color = palette.muted)
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
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { editing = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("עריכה")
                }
                TextButton(
                    onClick = { confirmingDelete = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("מחיקה", color = palette.secondary)
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("למחוק את הארוחה?") },
            text = {
                Text("הארוחה תוסר מהיומן המקומי ומהסיכום היומי. הפעולה הזו עדיין מקומית בלבד, לפני סנכרון Firebase.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete(meal)
                }) {
                    Text("מחיקה", color = palette.secondary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text("ביטול")
                }
            }
        )
    }
}

@Composable
private fun SavedMealEditor(
    palette: ThemePalette,
    initial: MealAnalysis,
    onCancel: () -> Unit,
    onSave: (MealAnalysis) -> Unit
) {
    val items = remember(initial.analysisId) {
        mutableStateListOf<SavedMealEditItem>().apply {
            addAll(initial.items.map { SavedMealEditItem(it.name, it.estimatedQuantity) })
            if (isEmpty()) add(SavedMealEditItem("", ""))
        }
    }
    var calories by remember(initial.analysisId) { mutableStateOf(initial.nutrition.caloriesKcal.toString()) }
    var protein by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.proteinG)) }
    var carbs by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.carbohydratesG)) }
    var fat by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.fatG)) }

    val caloriesValue = calories.toIntOrNull()
    val proteinValue = protein.toDoubleOrNull()
    val carbsValue = carbs.toDoubleOrNull()
    val fatValue = fat.toDoubleOrNull()
    val hasNamedItem = items.any { it.name.isNotBlank() }
    val valid = caloriesValue != null && caloriesValue >= 0 &&
        proteinValue != null && proteinValue >= 0 &&
        carbsValue != null && carbsValue >= 0 &&
        fatValue != null && fatValue >= 0 &&
        hasNamedItem

    GlowCard(palette) {
        Text("עריכת ארוחה", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("השינוי נשמר מקומית באותה ארוחה ומעדכן מיד את הסיכום היומי.", color = palette.muted)
        Spacer(Modifier.height(12.dp))
        SavedMealNumericField("קלוריות", calories) { calories = it }
        SavedMealNumericField("חלבון (גרם)", protein) { protein = it }
        SavedMealNumericField("פחמימות (גרם)", carbs) { carbs = it }
        SavedMealNumericField("שומן (גרם)", fat) { fat = it }
    }

    GlowCard(palette) {
        Text("מזונות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = palette.muted.copy(alpha = 0.25f))
            OutlinedTextField(
                value = item.name,
                onValueChange = { items[index] = item.copy(name = it) },
                label = { Text("שם המזון") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = item.quantity,
                onValueChange = { items[index] = item.copy(quantity = it) },
                label = { Text("כמות / הערה") },
                modifier = Modifier.fillMaxWidth()
            )
            if (items.size > 1) {
                TextButton(onClick = { items.removeAt(index) }) {
                    Text("מחיקת רכיב", color = palette.secondary)
                }
            }
        }
        OutlinedButton(
            onClick = { items.add(SavedMealEditItem("", "")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("+ הוספת רכיב")
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text("ביטול")
        }
        Button(
            enabled = valid,
            onClick = {
                onSave(
                    applySavedMealEdit(
                        initial = initial,
                        items = items,
                        nutrition = NutritionEstimate(
                            caloriesKcal = requireNotNull(caloriesValue),
                            proteinG = requireNotNull(proteinValue),
                            carbohydratesG = requireNotNull(carbsValue),
                            fatG = requireNotNull(fatValue)
                        )
                    )
                )
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.success,
                contentColor = palette.background
            )
        ) {
            Text("שמירת שינויים", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SavedMealNumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { candidate ->
            if (candidate.isEmpty() || candidate.matches(Regex("\\d*(\\.\\d*)?"))) {
                onValueChange(candidate)
            }
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}
