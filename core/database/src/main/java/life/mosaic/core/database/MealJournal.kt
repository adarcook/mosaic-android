package life.mosaic.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import life.mosaic.core.model.MealAnalysis
import life.mosaic.core.model.MealItem
import life.mosaic.core.model.NutritionEstimate
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Entity(tableName = "meals")
data class MealEntity(
    @PrimaryKey val analysisId: String,
    val createdAtEpochMillis: Long,
    val status: String,
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbohydratesG: Double,
    val fatG: Double,
    val itemsJson: String,
    val assumptionsJson: String,
    val questionsJson: String,
    val answersJson: String,
    val originalItemsJson: String,
    val originalNutritionJson: String,
    val userEdited: Boolean
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

@Database(entities = [MealEntity::class], version = 2, exportSchema = false)
abstract class MosaicDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao

    companion object {
        @Volatile private var instance: MosaicDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE meals ADD COLUMN originalItemsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE meals ADD COLUMN originalNutritionJson TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE meals ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): MosaicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MosaicDatabase::class.java,
                "mosaic-fit.db"
            ).addMigrations(migration1To2).build().also { instance = it }
        }
    }
}

class MealJournal(context: Context) {
    private val dao = MosaicDatabase.get(context).mealDao()

    suspend fun save(analysis: MealAnalysis) = dao.upsert(analysis.toEntity())
    suspend fun allMeals(): List<MealAnalysis> = dao.all().map { it.toModel() }
    suspend fun mealsFor(date: LocalDate, zoneId: ZoneId = ZoneId.systemDefault()): List<MealAnalysis> =
        allMeals().filter {
            Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zoneId).toLocalDate() == date
        }
    suspend fun delete(analysisId: String) = dao.delete(analysisId)
}

private fun itemsToJson(items: List<MealItem>) = JSONArray().apply {
    items.forEach { item ->
        put(JSONObject().apply {
            put("name", item.name)
            put("estimated_quantity", item.estimatedQuantity)
            put("confidence", item.confidence)
        })
    }
}.toString()

private fun nutritionToJson(nutrition: NutritionEstimate) = JSONObject().apply {
    put("calories_kcal", nutrition.caloriesKcal)
    put("protein_g", nutrition.proteinG)
    put("carbohydrates_g", nutrition.carbohydratesG)
    put("fat_g", nutrition.fatG)
}.toString()

private fun MealAnalysis.toEntity(): MealEntity {
    val sourceItems = originalItems.ifEmpty { items }
    val sourceNutrition = originalNutrition ?: nutrition
    return MealEntity(
        analysisId, createdAtEpochMillis, status,
        nutrition.caloriesKcal, nutrition.proteinG, nutrition.carbohydratesG, nutrition.fatG,
        itemsToJson(items), JSONArray(assumptions).toString(),
        JSONArray(confirmationQuestions).toString(), JSONObject(answers).toString(),
        itemsToJson(sourceItems), nutritionToJson(sourceNutrition), userEdited
    )
}

private fun parseItems(json: String): List<MealItem> {
    val array = JSONArray(json)
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        MealItem(item.getString("name"), item.getString("estimated_quantity"), item.getDouble("confidence"))
    }
}

private fun parseNutrition(json: String, fallback: NutritionEstimate): NutritionEstimate {
    if (json.isBlank() || json == "{}") return fallback
    return runCatching {
        val value = JSONObject(json)
        NutritionEstimate(
            value.getInt("calories_kcal"), value.getDouble("protein_g"),
            value.getDouble("carbohydrates_g"), value.getDouble("fat_g")
        )
    }.getOrDefault(fallback)
}

private fun MealEntity.toModel(): MealAnalysis {
    val questions = JSONArray(questionsJson)
    val assumptions = JSONArray(assumptionsJson)
    val answersObject = JSONObject(answersJson)
    val finalNutrition = NutritionEstimate(caloriesKcal, proteinG, carbohydratesG, fatG)
    val finalItems = parseItems(itemsJson)
    val sourceItems = runCatching { parseItems(originalItemsJson) }.getOrDefault(finalItems)
    return MealAnalysis(
        analysisId, status, finalItems, finalNutrition,
        List(assumptions.length()) { assumptions.getString(it) },
        List(questions.length()) { questions.getString(it) },
        answersObject.keys().asSequence().associateWith { answersObject.getString(it) },
        createdAtEpochMillis, sourceItems,
        parseNutrition(originalNutritionJson, finalNutrition), userEdited
    )
}
