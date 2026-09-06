package life.mosaic.feature.fit

import android.content.Context
import life.mosaic.core.database.MealJournal
import life.mosaic.core.model.MealAnalysis
import java.time.LocalDate
import java.time.ZoneId

/**
 * Public data boundary for the Fit feature.
 *
 * UI code depends on this feature-level API instead of depending directly on Room.
 * A future sync-aware implementation can replace the local journal behind this class
 * without changing the Fit screens.
 */
class FitMealJournal(context: Context) {
    private val localJournal = MealJournal(context)

    suspend fun save(analysis: MealAnalysis): MealAnalysis = localJournal.save(analysis)

    suspend fun allMeals(): List<MealAnalysis> = localJournal.allMeals()

    suspend fun mealsFor(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<MealAnalysis> = localJournal.mealsFor(date, zoneId)

    suspend fun revisionsFor(mealId: String): List<MealAnalysis> =
        localJournal.revisionsFor(mealId)

    suspend fun delete(analysis: MealAnalysis) = localJournal.delete(analysis)
}
