package life.mosaic.fit

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.mosaic.fit.data.MealAnalysis
import life.mosaic.fit.data.MealItem
import life.mosaic.fit.data.NutritionEstimate
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@Composable
internal fun AnalyzeMealScreen(
    palette: ThemePalette,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    onSave: (MealAnalysis) -> Unit
) {
    val context = LocalContext.current
    var selectedImage by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var message by remember { mutableStateOf("צלם את הארוחה כדי להתחיל") }
    var analysis by remember { mutableStateOf<MealAnalysis?>(null) }
    var editing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = pendingCameraUri
        if (captured && uri != null) {
            selectedImage = uri
            analysis = null
            editing = false
            message = "התמונה מוכנה לניתוח"
        } else {
            message = "הצילום בוטל"
        }
    }

    ScreenColumn {
        Header(
            palette,
            "AI MEAL SCAN",
            "ניתוח ארוחה",
            "צלם, סרוק, תקן במקרה הצורך ושמור ביומן"
        )

        GlowCard(palette) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChanged,
                label = { Text("כתובת השרת") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.primary,
                    focusedLabelColor = palette.primary
                )
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val uri = createTemporaryMealPhotoUri(context)
                    pendingCameraUri = uri
                    camera.launch(uri)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.primary,
                    contentColor = palette.background
                )
            ) {
                Text(
                    if (selectedImage == null) "צילום ארוחה" else "צילום מחדש",
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "התמונה נשמרת זמנית בתוך האפליקציה ולא בגלריה.",
                color = palette.muted,
                fontSize = 12.sp
            )

            selectedImage?.let { uri ->
                Spacer(Modifier.height(14.dp))
                MealScanPreview(uri, loading, palette)
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = !loading,
                    onClick = {
                        loading = true
                        analysis = null
                        editing = false
                        message = "סורק ומנתח את הארוחה…"
                        scope.launch {
                            runCatching { uploadMeal(context, serverUrl, uri) }
                                .onSuccess {
                                    analysis = it
                                    message = "הניתוח הושלם"
                                }
                                .onFailure { message = "הניתוח נכשל: ${it.message}" }
                            loading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.success,
                        contentColor = palette.background
                    )
                ) {
                    Text("התחלת סריקה", fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            message,
            color = palette.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        analysis?.let { result ->
            if (editing) {
                MealAnalysisEditor(palette, result, { editing = false }, onSave)
            } else {
                MealAnalysisResult(palette, result)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { editing = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) { Text("עריכת הניתוח") }
                    Button(
                        onClick = { onSave(result) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.success,
                            contentColor = palette.background
                        )
                    ) { Text("שמירה למעקב", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun MealScanPreview(uri: Uri, scanning: Boolean, palette: ThemePalette) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        }.getOrNull()?.asImageBitmap()
    }
    val transition = rememberInfiniteTransition(label = "meal-scan")
    val progress by transition.animateFloat(
        initialValue = 0.05f,
        targetValue = 0.82f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan-position"
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surfaceHighlight)
            .border(1.dp, palette.primary.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "תמונת הארוחה",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        if (scanning) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.20f)))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .offset(y = 224.dp * progress)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                palette.primary.copy(alpha = 0.18f),
                                palette.primary,
                                palette.primary.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )
            Text(
                "AI סורק רכיבים וכמויות",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun MealAnalysisEditor(
    palette: ThemePalette,
    initial: MealAnalysis,
    onCancel: () -> Unit,
    onSave: (MealAnalysis) -> Unit
) {
    val items = remember(initial.analysisId) {
        mutableStateListOf<EditableItem>().apply {
            addAll(initial.items.map { EditableItem(it.name, it.estimatedQuantity) })
        }
    }
    var calories by remember(initial.analysisId) { mutableStateOf(initial.nutrition.caloriesKcal.toString()) }
    var protein by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.proteinG)) }
    var carbs by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.carbohydratesG)) }
    var fat by remember(initial.analysisId) { mutableStateOf(formatNumber(initial.nutrition.fatG)) }

    fun applyMultiplier(multiplier: Double) {
        calories = (initial.nutrition.caloriesKcal * multiplier).toInt().toString()
        protein = formatNumber(initial.nutrition.proteinG * multiplier)
        carbs = formatNumber(initial.nutrition.carbohydratesG * multiplier)
        fat = formatNumber(initial.nutrition.fatG * multiplier)
    }

    GlowCard(palette) {
        Text("עריכת הניתוח", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("הערכים המקוריים של ה-AI יישמרו לצד התיקון שלך.", color = palette.muted)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("חצי מנה" to 0.5, "מנה מלאה" to 1.0, "שתי מנות" to 2.0)
                .forEach { (label, multiplier) ->
                    OutlinedButton(
                        onClick = { applyMultiplier(multiplier) },
                        modifier = Modifier.weight(1f)
                    ) { Text(label, fontSize = 12.sp) }
                }
        }
        Spacer(Modifier.height(12.dp))
        NumericField("קלוריות", calories) { calories = it }
        NumericField("חלבון (גרם)", protein) { protein = it }
        NumericField("פחמימות (גרם)", carbs) { carbs = it }
        NumericField("שומן (גרם)", fat) { fat = it }
    }

    GlowCard(palette) {
        Text("מזונות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = palette.muted.copy(alpha = 0.25f))
            OutlinedTextField(
                value = item.name,
                onValueChange = { items[index] = item.copy(name = it) },
                label = { Text("שם המזון") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = item.quantity,
                onValueChange = { items[index] = item.copy(quantity = it) },
                label = { Text("כמות") },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { items.removeAt(index) }) {
                Text("מחיקת מזון", color = palette.secondary)
            }
        }
        OutlinedButton(
            onClick = { items.add(EditableItem("", "")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("+ הוספת מזון") }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp)
        ) { Text("ביטול") }
        Button(
            enabled = calories.toIntOrNull() != null &&
                listOf(protein, carbs, fat).all { it.toDoubleOrNull() != null },
            onClick = {
                val editedItems = items
                    .filter { it.name.isNotBlank() }
                    .map { MealItem(it.name.trim(), it.quantity.trim(), 1.0) }
                onSave(
                    initial.copy(
                        status = "confirmed",
                        items = editedItems,
                        nutrition = NutritionEstimate(
                            calories.toInt(), protein.toDouble(), carbs.toDouble(), fat.toDouble()
                        ),
                        originalItems = initial.originalItems.ifEmpty { initial.items },
                        originalNutrition = initial.originalNutrition ?: initial.nutrition,
                        userEdited = true
                    )
                )
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.success,
                contentColor = palette.background
            )
        ) { Text("שמירת התיקון", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun MealAnalysisResult(palette: ThemePalette, analysis: MealAnalysis) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlowCard(palette) {
            Text("הערכה תזונתית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            NutritionRow(palette, "קלוריות", "${analysis.nutrition.caloriesKcal} קק״ל")
            NutritionRow(palette, "חלבון", "${formatNumber(analysis.nutrition.proteinG)} גרם")
            NutritionRow(palette, "פחמימות", "${formatNumber(analysis.nutrition.carbohydratesG)} גרם")
            NutritionRow(palette, "שומן", "${formatNumber(analysis.nutrition.fatG)} גרם")
        }
        GlowCard(palette) {
            Text("מזונות שזוהו", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            analysis.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = palette.muted.copy(alpha = 0.25f))
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(item.name, fontWeight = FontWeight.Bold)
                    Text(item.estimatedQuantity, color = palette.muted)
                    Text("רמת ביטחון: ${(item.confidence * 100).toInt()}%", color = palette.primary)
                }
            }
        }
        if (analysis.assumptions.isNotEmpty()) {
            GlowCard(palette) {
                Text("הנחות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                analysis.assumptions.forEach { Text("• $it", color = palette.muted) }
            }
        }
    }
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
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

private suspend fun uploadMeal(
    context: Context,
    serverUrl: String,
    uri: Uri
): MealAnalysis = withContext(Dispatchers.IO) {
    val imageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("לא ניתן לקרוא את התמונה שצולמה")
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
    val boundary = "MosaicBoundary-${UUID.randomUUID()}"
    val endpoint = URL("${serverUrl.trimEnd('/')}/v1/meals/analyze")
    val body = ByteArrayOutputStream().apply {
        write("--$boundary\r\n".toByteArray())
        write(
            ("Content-Disposition: form-data; name=\"image\"; " +
                "filename=\"meal.jpg\"\r\n").toByteArray()
        )
        write("Content-Type: $mimeType\r\n\r\n".toByteArray())
        write(imageBytes)
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

private fun createTemporaryMealPhotoUri(context: Context): Uri {
    val directory = File(context.cacheDir, "meal-photos").apply { mkdirs() }
    directory.listFiles()
        ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
        ?.forEach { it.delete() }
    val photo = File.createTempFile("meal-", ".jpg", directory)
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photo
    )
}

private data class EditableItem(val name: String, val quantity: String)
