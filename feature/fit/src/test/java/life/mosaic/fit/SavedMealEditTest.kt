package life.mosaic.fit

import life.mosaic.fit.data.MealAnalysis
import life.mosaic.fit.data.MealItem
import life.mosaic.fit.data.NutritionEstimate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedMealEditTest {
    @Test
    fun `editing keeps identity and timestamp while preserving original values`() {
        val original = MealAnalysis(
            analysisId = "meal-123",
            status = "confirmed",
            items = listOf(MealItem("Chicken", "200 g", 0.72)),
            nutrition = NutritionEstimate(400, 45.0, 10.0, 18.0),
            assumptions = listOf("estimated portion"),
            confirmationQuestions = emptyList(),
            createdAtEpochMillis = 123456789L
        )

        val edited = applySavedMealEdit(
            initial = original,
            items = listOf(SavedMealEditItem("Chicken thigh", "220 g")),
            nutrition = NutritionEstimate(520, 55.0, 8.0, 28.0)
        )

        assertEquals("meal-123", edited.analysisId)
        assertEquals(123456789L, edited.createdAtEpochMillis)
        assertEquals("confirmed", edited.status)
        assertEquals("Chicken thigh", edited.items.single().name)
        assertEquals("220 g", edited.items.single().estimatedQuantity)
        assertEquals(520, edited.nutrition.caloriesKcal)
        assertEquals(original.items, edited.originalItems)
        assertEquals(original.nutrition, edited.originalNutrition)
        assertTrue(edited.userEdited)
    }

    @Test
    fun `subsequent edit retains first recorded originals`() {
        val firstOriginalItems = listOf(MealItem("AI chicken", "180 g", 0.65))
        val firstOriginalNutrition = NutritionEstimate(360, 40.0, 4.0, 17.0)
        val alreadyEdited = MealAnalysis(
            analysisId = "meal-456",
            status = "confirmed",
            items = listOf(MealItem("Chicken", "200 g", 1.0)),
            nutrition = NutritionEstimate(430, 48.0, 5.0, 20.0),
            assumptions = emptyList(),
            confirmationQuestions = emptyList(),
            createdAtEpochMillis = 987654321L,
            originalItems = firstOriginalItems,
            originalNutrition = firstOriginalNutrition,
            userEdited = true
        )

        val editedAgain = applySavedMealEdit(
            initial = alreadyEdited,
            items = listOf(SavedMealEditItem("Chicken", "220 g")),
            nutrition = NutritionEstimate(480, 52.0, 5.0, 23.0)
        )

        assertEquals(firstOriginalItems, editedAgain.originalItems)
        assertEquals(firstOriginalNutrition, editedAgain.originalNutrition)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `edit requires at least one named item`() {
        val original = MealAnalysis(
            analysisId = "meal-789",
            status = "confirmed",
            items = listOf(MealItem("Meal", "", 1.0)),
            nutrition = NutritionEstimate(100, 10.0, 10.0, 2.0),
            assumptions = emptyList(),
            confirmationQuestions = emptyList()
        )

        applySavedMealEdit(
            initial = original,
            items = listOf(SavedMealEditItem("   ", "")),
            nutrition = original.nutrition
        )
    }
}
