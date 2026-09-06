package life.mosaic.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import life.mosaic.core.database.photos.PhotoDao
import life.mosaic.core.database.photos.PhotoEntity
import life.mosaic.core.model.LocalMealRecordIdentity
import life.mosaic.core.model.MealAnalysis
import life.mosaic.core.model.MealItem
import life.mosaic.core.model.NutritionEstimate
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Entity(
    tableName = "meals",
    primaryKeys = ["mealId", "revision"]
)
data class MealEntity(
    val mealId: String,
    val revision: Int,
    val supersedesRevision: Int?,
    val analysisId: String,
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
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(meal: MealEntity)

    @Query("SELECT * FROM meals ORDER BY createdAtEpochMillis DESC, revision DESC")
    suspend fun allRevisions(): List<MealEntity>

    @Query("SELECT * FROM meals WHERE mealId = :mealId ORDER BY revision DESC LIMIT 1")
    suspend fun latestForMeal(mealId: String): MealEntity?

    @Query("SELECT * FROM meals WHERE analysisId = :analysisId ORDER BY revision DESC LIMIT 1")
    suspend fun latestForAnalysis(analysisId: String): MealEntity?

    @Query("SELECT * FROM meals WHERE mealId = :mealId ORDER BY revision ASC")
    suspend fun revisionsFor(mealId: String): List<MealEntity>
}

@Database(
    entities = [MealEntity::class, PhotoEntity::class],
    version = 4,
    exportSchema = false
)
abstract class MosaicDatabase : RoomDatabase() {
    abstract fun mealDao(): MealDao
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile private var instance: MosaicDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE meals ADD COLUMN originalItemsJson TEXT NOT NULL DEFAULT '[]'")
                database.execSQL("ALTER TABLE meals ADD COLUMN originalNutritionJson TEXT NOT NULL DEFAULT '{}'")
                database.execSQL("ALTER TABLE meals ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photos (
                        mediaId INTEGER NOT NULL PRIMARY KEY,
                        contentUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        relativePath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        dateAddedEpochSeconds INTEGER NOT NULL,
                        automaticCategory TEXT NOT NULL,
                        userDecision TEXT,
                        importanceScore REAL NOT NULL,
                        classificationReasons TEXT NOT NULL,
                        imageEmbeddingStatus TEXT NOT NULL,
                        faceEmbeddingStatus TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        scannedAtEpochMillis INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS meals_v4 (
                        mealId TEXT NOT NULL,
                        revision INTEGER NOT NULL,
                        supersedesRevision INTEGER,
                        analysisId TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        caloriesKcal INTEGER NOT NULL,
                        proteinG REAL NOT NULL,
                        carbohydratesG REAL NOT NULL,
                        fatG REAL NOT NULL,
                        itemsJson TEXT NOT NULL,
                        assumptionsJson TEXT NOT NULL,
                        questionsJson TEXT NOT NULL,
                        answersJson TEXT NOT NULL,
                        originalItemsJson TEXT NOT NULL,
                        originalNutritionJson TEXT NOT NULL,
                        userEdited INTEGER NOT NULL,
                        PRIMARY KEY(mealId, revision)
                    )
                    """.trimIndent()
                )

                database.query("SELECT * FROM meals").use { cursor ->
                    val analysisIdIndex = cursor.getColumnIndexOrThrow("analysisId")
                    val createdAtIndex = cursor.getColumnIndexOrThrow("createdAtEpochMillis")
                    val statusIndex = cursor.getColumnIndexOrThrow("status")
                    val caloriesIndex = cursor.getColumnIndexOrThrow("caloriesKcal")
                    val proteinIndex = cursor.getColumnIndexOrThrow("proteinG")
                    val carbsIndex = cursor.getColumnIndexOrThrow("carbohydratesG")
                    val fatIndex = cursor.getColumnIndexOrThrow("fatG")
                    val itemsIndex = cursor.getColumnIndexOrThrow("itemsJson")
                    val assumptionsIndex = cursor.getColumnIndexOrThrow("assumptionsJson")
                    val questionsIndex = cursor.getColumnIndexOrThrow("questionsJson")
                    val answersIndex = cursor.getColumnIndexOrThrow("answersJson")
                    val originalItemsIndex = cursor.getColumnIndexOrThrow("originalItemsJson")
                    val originalNutritionIndex = cursor.getColumnIndexOrThrow("originalNutritionJson")
                    val userEditedIndex = cursor.getColumnIndexOrThrow("userEdited")

                    while (cursor.moveToNext()) {
                        val analysisId = cursor.getString(analysisIdIndex)
                        val mealId = deterministicLegacyUuid("meal:$analysisId")
                        val itemsJson = addLegacyComponentIds(
                            analysisId = analysisId,
                            itemsJson = cursor.getString(itemsIndex)
                        )

                        database.execSQL(
                            """
                            INSERT INTO meals_v4 (
                                mealId, revision, supersedesRevision, analysisId,
                                createdAtEpochMillis, status, caloriesKcal, proteinG,
                                carbohydratesG, fatG, itemsJson, assumptionsJson,
                                questionsJson, answersJson, originalItemsJson,
                                originalNutritionJson, userEdited
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                mealId,
                                1,
                                null,
                                analysisId,
                                cursor.getLong(createdAtIndex),
                                cursor.getString(statusIndex),
                                cursor.getInt(caloriesIndex),
                                cursor.getDouble(proteinIndex),
                                cursor.getDouble(carbsIndex),
                                cursor.getDouble(fatIndex),
                                itemsJson,
                                cursor.getString(assumptionsIndex),
                                cursor.getString(questionsIndex),
                                cursor.getString(answersIndex),
                                cursor.getString(originalItemsIndex),
                                cursor.getString(originalNutritionIndex),
                                cursor.getInt(userEditedIndex)
                            )
                        )
                    }
                }

                database.execSQL("DROP TABLE meals")
                database.execSQL("ALTER TABLE meals_v4 RENAME TO meals")
            }
        }

        fun get(context: Context): MosaicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MosaicDatabase::class.java,
                "mosaic-fit.db"
            ).addMigrations(
                migration1To2,
                migration2To3,
                migration3To4
            ).build().also { instance = it }
        }
    }
}

class MealJournal(context: Context) {
    private val dao = MosaicDatabase.get(context).mealDao()

    suspend fun save(analysis: MealAnalysis): MealAnalysis {
        val existing = findLatest(analysis)
        val nextIdentity = nextRecordIdentity(
            current = existing?.recordIdentity(),
            newMealId = {
                analysis.localRecord?.mealId
                    ?.takeIf(::isUuid)
                    ?: UUID.randomUUID().toString()
            }
        )
        val persisted = analysis.copy(
            items = assignStableComponentIds(analysis.items),
            localRecord = nextIdentity
        )
        dao.insert(persisted.toEntity())
        return persisted
    }

    suspend fun allMeals(): List<MealAnalysis> =
        latestVisibleRevisions(dao.allRevisions()).map { it.toModel() }

    suspend fun mealsFor(
        date: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<MealAnalysis> = allMeals().filter {
        Instant.ofEpochMilli(it.createdAtEpochMillis).atZone(zoneId).toLocalDate() == date
    }

    suspend fun revisionsFor(mealId: String): List<MealAnalysis> =
        dao.revisionsFor(mealId).map { it.toModel() }

    suspend fun delete(analysis: MealAnalysis) {
        val existing = findLatest(analysis) ?: return
        val latest = existing.toModel()
        val tombstone = latest.copy(
            status = "deleted",
            localRecord = nextRecordIdentity(existing.recordIdentity())
        )
        dao.insert(tombstone.toEntity())
    }

    private suspend fun findLatest(analysis: MealAnalysis): MealEntity? =
        analysis.localRecord?.mealId
            ?.let { dao.latestForMeal(it) }
            ?: dao.latestForAnalysis(analysis.analysisId)
}

internal fun nextRecordIdentity(
    current: LocalMealRecordIdentity?,
    newMealId: () -> String = { UUID.randomUUID().toString() }
): LocalMealRecordIdentity = if (current == null) {
    LocalMealRecordIdentity(
        mealId = newMealId(),
        revision = 1,
        supersedesRevision = null
    )
} else {
    LocalMealRecordIdentity(
        mealId = current.mealId,
        revision = current.revision + 1,
        supersedesRevision = current.revision
    )
}

internal fun assignStableComponentIds(
    items: List<MealItem>,
    newComponentId: () -> String = { UUID.randomUUID().toString() }
): List<MealItem> = items.map { item ->
    item.copy(
        componentId = item.componentId
            ?.takeIf(::isUuid)
            ?: newComponentId()
    )
}

internal fun latestVisibleRevisions(revisions: List<MealEntity>): List<MealEntity> =
    revisions
        .groupBy { it.mealId }
        .values
        .map { mealRevisions -> mealRevisions.maxBy { it.revision } }
        .filterNot { it.status == "deleted" }
        .sortedByDescending { it.createdAtEpochMillis }

internal fun deterministicLegacyUuid(seed: String): String =
    UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

private fun addLegacyComponentIds(analysisId: String, itemsJson: String): String =
    runCatching {
        val source = JSONArray(itemsJson)
        JSONArray().apply {
            repeat(source.length()) { index ->
                val item = JSONObject(source.getJSONObject(index).toString())
                val existingId = item.optString("component_id")
                if (!isUuid(existingId)) {
                    item.put(
                        "component_id",
                        deterministicLegacyUuid("component:$analysisId:$index")
                    )
                }
                put(item)
            }
        }.toString()
    }.getOrDefault(itemsJson)

private fun isUuid(value: String): Boolean =
    value.isNotBlank() && runCatching { UUID.fromString(value) }.isSuccess

private fun itemsToJson(items: List<MealItem>) = JSONArray().apply {
    items.forEach { item ->
        put(JSONObject().apply {
            put("name", item.name)
            put("estimated_quantity", item.estimatedQuantity)
            put("confidence", item.confidence)
            item.componentId?.let { put("component_id", it) }
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
    val identity = requireNotNull(localRecord) {
        "MealAnalysis must have local record identity before persistence"
    }
    val sourceItems = originalItems.ifEmpty { items }
    val sourceNutrition = originalNutrition ?: nutrition
    return MealEntity(
        mealId = identity.mealId,
        revision = identity.revision,
        supersedesRevision = identity.supersedesRevision,
        analysisId = analysisId,
        createdAtEpochMillis = createdAtEpochMillis,
        status = status,
        caloriesKcal = nutrition.caloriesKcal,
        proteinG = nutrition.proteinG,
        carbohydratesG = nutrition.carbohydratesG,
        fatG = nutrition.fatG,
        itemsJson = itemsToJson(items),
        assumptionsJson = JSONArray(assumptions).toString(),
        questionsJson = JSONArray(confirmationQuestions).toString(),
        answersJson = JSONObject(answers).toString(),
        originalItemsJson = itemsToJson(sourceItems),
        originalNutritionJson = nutritionToJson(sourceNutrition),
        userEdited = userEdited
    )
}

private fun parseItems(json: String): List<MealItem> {
    val array = JSONArray(json)
    return List(array.length()) { index ->
        val item = array.getJSONObject(index)
        MealItem(
            name = item.getString("name"),
            estimatedQuantity = item.getString("estimated_quantity"),
            confidence = item.getDouble("confidence"),
            componentId = item.optString("component_id").takeIf { it.isNotBlank() }
        )
    }
}

private fun parseNutrition(json: String, fallback: NutritionEstimate): NutritionEstimate {
    if (json.isBlank() || json == "{}") return fallback
    return runCatching {
        val value = JSONObject(json)
        NutritionEstimate(
            caloriesKcal = value.getInt("calories_kcal"),
            proteinG = value.getDouble("protein_g"),
            carbohydratesG = value.getDouble("carbohydrates_g"),
            fatG = value.getDouble("fat_g")
        )
    }.getOrDefault(fallback)
}

private fun MealEntity.recordIdentity() = LocalMealRecordIdentity(
    mealId = mealId,
    revision = revision,
    supersedesRevision = supersedesRevision
)

private fun MealEntity.toModel(): MealAnalysis {
    val questions = JSONArray(questionsJson)
    val assumptions = JSONArray(assumptionsJson)
    val answersObject = JSONObject(answersJson)
    val finalNutrition = NutritionEstimate(caloriesKcal, proteinG, carbohydratesG, fatG)
    val finalItems = parseItems(itemsJson)
    val sourceItems = runCatching { parseItems(originalItemsJson) }.getOrDefault(finalItems)
    return MealAnalysis(
        analysisId = analysisId,
        status = status,
        items = finalItems,
        nutrition = finalNutrition,
        assumptions = List(assumptions.length()) { assumptions.getString(it) },
        confirmationQuestions = List(questions.length()) { questions.getString(it) },
        answers = answersObject.keys().asSequence().associateWith { answersObject.getString(it) },
        createdAtEpochMillis = createdAtEpochMillis,
        originalItems = sourceItems,
        originalNutrition = parseNutrition(originalNutritionJson, finalNutrition),
        userEdited = userEdited,
        localRecord = recordIdentity()
    )
}
