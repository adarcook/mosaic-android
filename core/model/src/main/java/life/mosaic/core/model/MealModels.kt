package life.mosaic.core.model

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

data class MealAnalysis(
    val analysisId: String,
    val status: String,
    val items: List<MealItem>,
    val nutrition: NutritionEstimate,
    val assumptions: List<String>,
    val confirmationQuestions: List<String>,
    val answers: Map<String, String> = emptyMap(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val originalItems: List<MealItem> = emptyList(),
    val originalNutrition: NutritionEstimate? = null,
    val userEdited: Boolean = false
)
