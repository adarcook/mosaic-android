package life.mosaic.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualMealEntryTest {
    @Test
    fun `manual meal keeps entered nutrition and description`() {
        val meal = createManualMeal(
            name = "220 גרם פרגית עם ירקות",
            quantity = "220 גרם אחרי בישול",
            caloriesKcal = 520,
            proteinG = 55.0,
            carbohydratesG = 12.0,
            fatG = 26.0
        )

        assertTrue(meal.analysisId.startsWith("manual-"))
        assertEquals("confirmed", meal.status)
        assertEquals("220 גרם פרגית עם ירקות", meal.items.single().name)
        assertEquals("220 גרם אחרי בישול", meal.items.single().estimatedQuantity)
        assertEquals(520, meal.nutrition.caloriesKcal)
        assertEquals(55.0, meal.nutrition.proteinG, 0.001)
        assertEquals(12.0, meal.nutrition.carbohydratesG, 0.001)
        assertEquals(26.0, meal.nutrition.fatG, 0.001)
    }

    @Test
    fun `manual meal trims text fields`() {
        val meal = createManualMeal(
            name = "  קפה ומעדן חלבון  ",
            quantity = "  יחידה אחת  ",
            caloriesKcal = 200,
            proteinG = 20.0,
            carbohydratesG = 0.0,
            fatG = 0.0
        )

        assertEquals("קפה ומעדן חלבון", meal.items.single().name)
        assertEquals("יחידה אחת", meal.items.single().estimatedQuantity)
    }
}
