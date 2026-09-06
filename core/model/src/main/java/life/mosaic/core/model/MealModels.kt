package life.mosaic.core.model

data class MealItem(
    val name: String,
    val estimatedQuantity: String,
    val confidence: Double,
    val componentId: String? = null
)

data class NutritionEstimate(
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbohydratesG: Double,
    val fatG: Double
)

/**
 * Canonical identity metadata for a confirmed local meal record.
 *
 * `MealAnalysis` remains the temporary analysis/provenance payload while Stage 3
 * migrates storage toward the canonical MealRecord contract. The business identity
 * and revision are deliberately separate from `analysisId`.
 */
data class LocalMealRecordIdentity(
    val mealId: String,
    val revision: Int,
    val supersedesRevision: Int? = null
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
    val userEdited: Boolean = false,
    val localRecord: LocalMealRecordIdentity? = null
)
