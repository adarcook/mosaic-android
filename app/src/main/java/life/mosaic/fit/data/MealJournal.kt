package life.mosaic.fit.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Entity(tableName = "meals")
data class MealEntity(
    @androidx.room.PrimaryKey val analysisId: String,
    val createdAtEpochMillis: Long,
    val status: String,
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbohydratesG: Double,
    val fatG: Double,
    val itemsJson: String,
    val assumptionsJson: String,
    val questionsJson: String,
    val answersJson: String
)

@Dao
interface MealDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meal: MealEntity)

    @Query("SELECT * FROM meals ORDER BY createdAtEpochMillis DESC")
    suspend fun all(): List<MealEntity>

    @Query("DELETE FROM meals WHERE analysisId = :analysisId")
    suspend fun delete(analysisId: String)
}

@Database(entities = [MealEntity::class], version = 1, exportSchema = false)
abstract class MosaicFitDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        @Volatile private var instance: MosaicFitDatabase? = null

        fun get(context: Context): MosaicFitDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MosaicFitDatabase::class.java,
                "mosaic-fit.db"
            ).build().also { instance = it }
        }
    }
}

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
    val createdAtEpochMillis: Long = System.currentTimeMillis()
) {
    val isConfirmed: Boolean
        get() = confirmationQuestions.isEmpty() || confirmationQuestions.all { answers[it].isNullOrBlank().not() }
}

class MealJournal(context: Context) {
    private val dao = MosaicFitDatabase.get(context).mealDao()

    suspend fun save(analysis: MealAnalysis) = dao.upsert(analysis.toEntity())

    suspend fun allMeals(): List<MealAnalysis> = dao.all().map { it.toModel() }

    suspend fun mealsFor(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<MealAnalysis> =
        allMeals().filter {
            Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zoneId).toLocalDate() == date
        }

    suspend fun delete(analysisId: String) = dao.delete(analysisId)
}

private fun MealAnalysis.toEntity() = MealEntity(
    analysisId = analysisId,
    createdAtEpochMillis = createdAtEpochMillis,
    status = if (isConfirmed) "confirmed" else status,
    caloriesKcal = nutrition.caloriesKcal,
    proteinG = nutrition.proteinG,
    carbohydratesG = nutrition.carbohydratesG,
    fatG = nutrition.fatG,
    itemsJson = JSONArray().apply {
        items.forEach { item ->
            put(JSONObject().apply {
                put("name", item.name)
                put("estimated_quantity", item.estimatedQuantity)
                put("confidence", item.confidence)
            })
        }
    }.toString(),
    assumptionsJson = JSONArray(assumptions).toString(),
    questionsJson = JSONArray(confirmationQuestions).toString(),
    answersJson = JSONObject(answers).toString()
)

private fun MealEntity.toModel(): MealAnalysis {
    val itemsArray = JSONArray(itemsJson)
    val questionsArray = JSONArray(questionsJson)
    val assumptionsArray = JSONArray(assumptionsJson)
    val answersObject = JSONObject(answersJson)

    return MealAnalysis(
        analysisId = analysisId,
        status = status,
        items = List(itemsArray.length()) { index ->
            val item = itemsArray.getJSONObject(index)
            MealItem(
                name = item.getString("name"),
                estimatedQuantity = item.getString("estimated_quantity"),
                confidence = item.getDouble("confidence")
            )
        },
        nutrition = NutritionEstimate(caloriesKcal, proteinG, carbohydratesG, fatG),
        assumptions = List(assumptionsArray.length()) { assumptionsArray.getString(it) },
        confirmationQuestions = List(questionsArray.length()) { questionsArray.getString(it) },
        answers = answersObject.keys().asSequence().associateWith { answersObject.getString(it) },
        createdAtEpochMillis = createdAtEpochMillis
    )
}
