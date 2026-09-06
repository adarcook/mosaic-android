package life.mosaic.fit.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import life.mosaic.core.model.MealAnalysis
import life.mosaic.core.model.MealItem
import life.mosaic.core.model.NutritionEstimate
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class MealPhotoInput(
    val bytes: ByteArray,
    val mimeType: String,
    val fileName: String = "meal.jpg"
)

fun interface MealAnalyzer {
    suspend fun analyze(photo: MealPhotoInput): MealAnalysis
}

/**
 * Legacy development adapter for the existing Mosaic Server endpoint.
 *
 * Production meal capture should not depend on this adapter. It remains useful while the
 * on-device analyzer is not implemented and demonstrates the replaceable analyzer boundary.
 */
class HttpMealAnalyzer(
    private val serverUrl: String
) : MealAnalyzer {
    override suspend fun analyze(photo: MealPhotoInput): MealAnalysis = withContext(Dispatchers.IO) {
        val boundary = "MosaicBoundary-${UUID.randomUUID()}"
        val endpoint = URL("${serverUrl.trimEnd('/')}/v1/meals/analyze")
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray())
            write(
                ("Content-Disposition: form-data; name=\"image\"; " +
                    "filename=\"${photo.fileName}\"\r\n").toByteArray()
            )
            write("Content-Type: ${photo.mimeType}\r\n\r\n".toByteArray())
            write(photo.bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()

        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", body.size.toString())
        }

        try {
            connection.outputStream.use { it.write(body) }
            val responseText = (
                if (connection.responseCode in 200..299) connection.inputStream
                else connection.errorStream
                ).bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                error("השרת החזיר ${connection.responseCode}: $responseText")
            }
            parseMealAnalysis(JSONObject(responseText))
        } finally {
            connection.disconnect()
        }
    }
}

private fun parseMealAnalysis(json: JSONObject): MealAnalysis {
    val nutritionJson = json.getJSONObject("nutrition")
    val itemsJson = json.getJSONArray("items")
    val assumptionsJson = json.getJSONArray("assumptions")
    val questionsJson = json.getJSONArray("confirmation_questions")
    val items = List(itemsJson.length()) { index ->
        itemsJson.getJSONObject(index).let {
            MealItem(it.getString("name"), it.getString("estimated_quantity"), it.getDouble("confidence"))
        }
    }
    val nutrition = NutritionEstimate(
        nutritionJson.getInt("calories_kcal"),
        nutritionJson.getDouble("protein_g"),
        nutritionJson.getDouble("carbohydrates_g"),
        nutritionJson.getDouble("fat_g")
    )
    return MealAnalysis(
        analysisId = json.getString("analysis_id"),
        status = json.getString("status"),
        items = items,
        nutrition = nutrition,
        assumptions = List(assumptionsJson.length()) { assumptionsJson.getString(it) },
        confirmationQuestions = List(questionsJson.length()) { questionsJson.getString(it) },
        originalItems = items,
        originalNutrition = nutrition
    )
}
