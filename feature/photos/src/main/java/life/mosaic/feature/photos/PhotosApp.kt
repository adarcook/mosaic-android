package life.mosaic.feature.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DevicePhoto(
    val mediaId: Long,
    val contentUri: String,
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val dateAdded: Instant,
    val category: PhotoCategory
)

enum class PhotoCategory {
    IMPORTANT_CANDIDATE,
    CLEANUP_CANDIDATE
}

@Composable
fun PhotosApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = photoReadPermission()

    var hasPermission by remember {
        mutableStateOf(
            permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf<List<DevicePhoto>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(PhotoCategory.IMPORTANT_CANDIDATE) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) error = "נדרשת הרשאה לקריאת התמונות במכשיר"
    }

    suspend fun refresh() {
        loading = true
        error = null
        runCatching { scanDevicePhotos(context) }
            .onSuccess { photos = it }
            .onFailure { error = it.message ?: "סריקת התמונות נכשלה" }
        loading = false
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) refresh()
    }

    val important = photos.filter { it.category == PhotoCategory.IMPORTANT_CANDIDATE }
    val cleanup = photos.filter { it.category == PhotoCategory.CLEANUP_CANDIDATE }
    val visible = if (selectedCategory == PhotoCategory.IMPORTANT_CANDIDATE) important else cleanup

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text("Mosaic Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "סריקה מקומית של ספריית התמונות והכנה לסיווג, embeddings וסנכרון למחשב הביתי.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!hasPermission) {
                    item {
                        StatusCard("כדי לסרוק את ספריית התמונות יש לאשר גישה לתמונות במכשיר.")
                        Button(
                            onClick = { permission?.let(permissionLauncher::launch) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("אישור גישה לתמונות") }
                    }
                } else {
                    item { SummaryCard(total = photos.size, important = important.size, cleanup = cleanup.size) }

                    item {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CategoryButton(
                                label = "חשובות ${important.size}",
                                selected = selectedCategory == PhotoCategory.IMPORTANT_CANDIDATE,
                                onClick = { selectedCategory = PhotoCategory.IMPORTANT_CANDIDATE },
                                modifier = Modifier.weight(1f)
                            )
                            CategoryButton(
                                label = "לבדיקת ניקוי ${cleanup.size}",
                                selected = selectedCategory == PhotoCategory.CLEANUP_CANDIDATE,
                                onClick = { selectedCategory = PhotoCategory.CLEANUP_CANDIDATE },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    when {
                        loading -> item { StatusCard("סורק את התמונות במכשיר…") }
                        error != null -> item { StatusCard(error.orEmpty()) }
                        visible.isEmpty() -> item { StatusCard("לא נמצאו תמונות בקטגוריה הזאת.") }
                        else -> items(visible.take(100), key = { it.mediaId }) { photo -> PhotoCard(photo) }
                    }

                    item {
                        Button(
                            onClick = { scope.launch { refresh() } },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("סריקה מחדש") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(total: Int, important: Int, cleanup: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("סריקה ראשונית", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("$total תמונות נמצאו במכשיר")
            Text("$important מועמדות לשמירה וסנכרון")
            Text("$cleanup מועמדות לבדיקה לפני ניקוי")
            Text(
                "בשלב הזה הסיווג שמרני: צילומי מסך ותיקיות זמניות מסומנים לבדיקה, ושום תמונה לא נמחקת.",
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun CategoryButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun PhotoCard(photo: DevicePhoto) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm") }
    val localDate = photo.dateAdded.atZone(ZoneId.systemDefault()).format(formatter)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(photo.displayName.ifBlank { "תמונה ללא שם" }, fontWeight = FontWeight.Bold)
            Text("$localDate · ${photo.width}×${photo.height}")
            Text(photo.relativePath.ifBlank { "תיקייה לא ידועה" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (photo.category == PhotoCategory.CLEANUP_CANDIDATE) "מועמדת לבדיקה לפני ניקוי" else "מועמדת לשמירה וסנכרון",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Text(message, modifier = Modifier.padding(18.dp))
    }
}

private fun photoReadPermission(): String? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}

private suspend fun scanDevicePhotos(context: Context): List<DevicePhoto> = withContext(Dispatchers.IO) {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val projection = mutableListOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(MediaStore.Images.Media.RELATIVE_PATH)
        else add(MediaStore.Images.Media.DATA)
    }.toTypedArray()

    val result = mutableListOf<DevicePhoto>()
    context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        }

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val displayName = cursor.getString(nameColumn).orEmpty()
            val path = cursor.getString(pathColumn).orEmpty()
            val mimeType = cursor.getString(mimeColumn).orEmpty()
            val width = cursor.getInt(widthColumn)
            val height = cursor.getInt(heightColumn)
            val size = cursor.getLong(sizeColumn)
            val dateAdded = Instant.ofEpochSecond(cursor.getLong(dateColumn))
            val uri = ContentUris.withAppendedId(collection, id).toString()

            result += DevicePhoto(
                mediaId = id,
                contentUri = uri,
                displayName = displayName,
                relativePath = path,
                mimeType = mimeType,
                width = width,
                height = height,
                sizeBytes = size,
                dateAdded = dateAdded,
                category = classifyPhoto(displayName, path)
            )
        }
    }
    result
}

private fun classifyPhoto(displayName: String, relativePath: String): PhotoCategory {
    val text = "$displayName $relativePath".lowercase()
    val cleanupSignals = listOf(
        "screenshot",
        "screenshots",
        "screen_record",
        "screenrecord",
        "download",
        "downloads",
        "whatsapp images/sent",
        "telegram/telegram images"
    )
    return if (cleanupSignals.any(text::contains)) {
        PhotoCategory.CLEANUP_CANDIDATE
    } else {
        PhotoCategory.IMPORTANT_CANDIDATE
    }
}
