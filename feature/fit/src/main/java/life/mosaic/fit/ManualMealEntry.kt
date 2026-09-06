package life.mosaic.fit

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import java.util.UUID

@Composable
internal fun ManualMealEntryCard(
    palette: ThemePalette,
    onSave: (MealAnalysis) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    val caloriesValue = calories.toIntOrNull()
    val proteinValue = protein.toDoubleOrNull()
    val carbsValue = carbs.ifBlank { "0" }.toDoubleOrNull()
    val fatValue = fat.ifBlank { "0" }.toDoubleOrNull()
    val valid = name.isNotBlank() &&
        caloriesValue != null && caloriesValue >= 0 &&
        proteinValue != null && proteinValue >= 0.0 &&
        carbsValue != null && carbsValue >= 0.0 &&
        fatValue != null && fatValue >= 0.0

    GlowCard(palette) {
        Text("הזנה ידנית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "עובד לגמרי על המכשיר, גם בלי אינטרנט ובלי שהמחשב הביתי דלוק.",
            color = palette.muted
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("מה אכלת?") },
            placeholder = { Text("לדוגמה: 220 גרם פרגית עם ירקות") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = quantity,
            onValueChange = { quantity = it },
            label = { Text("כמות / הערה (אופציונלי)") },
            placeholder = { Text("לדוגמה: 220 גרם אחרי בישול") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        ManualNumericField("קלוריות", calories) { calories = it }
        Spacer(Modifier.height(10.dp))
        ManualNumericField("חלבון (גרם)", protein) { protein = it }
        Spacer(Modifier.height(10.dp))
        ManualNumericField("פחמימות (גרם, אופציונלי)", carbs) { carbs = it }
        Spacer(Modifier.height(10.dp))
        ManualNumericField("שומן (גרם, אופציונלי)", fat) { fat = it }
        Spacer(Modifier.height(14.dp))

        Button(
            enabled = valid,
            onClick = {
                onSave(
                    createManualMeal(
                        name = name,
                        quantity = quantity,
                        caloriesKcal = requireNotNull(caloriesValue),
                        proteinG = requireNotNull(proteinValue),
                        carbohydratesG = requireNotNull(carbsValue),
                        fatG = requireNotNull(fatValue)
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.success,
                contentColor = palette.background
            )
        ) {
            Text("שמירת ארוחה", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ManualNumericField(
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
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

internal fun createManualMeal(
    name: String,
    quantity: String,
    caloriesKcal: Int,
    proteinG: Double,
    carbohydratesG: Double,
    fatG: Double
): MealAnalysis = MealAnalysis(
    analysisId = "manual-${UUID.randomUUID()}",
    status = "confirmed",
    items = listOf(
        MealItem(
            name = name.trim(),
            estimatedQuantity = quantity.trim(),
            confidence = 1.0
        )
    ),
    nutrition = NutritionEstimate(
        caloriesKcal = caloriesKcal,
        proteinG = proteinG,
        carbohydratesG = carbohydratesG,
        fatG = fatG
    ),
    assumptions = emptyList(),
    confirmationQuestions = emptyList()
)
