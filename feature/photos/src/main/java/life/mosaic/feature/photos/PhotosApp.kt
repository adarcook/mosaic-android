package life.mosaic.feature.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
            permission == null ||
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var photos by remember { mutableStateOf<List<DevicePhoto>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf(PhotoCategory.IMPORTANT_CANDIDATE) }
    var selectedPhoto by remember { mutableStateOf<DevicePhoto?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
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

    selectedPhoto?.let { photo ->
        PhotoPreviewDialog(photo = photo, onDismiss = { selectedPhoto = null })
    }

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Mosaic Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "גלריה מקומית שמכינה תמונות חשובות לסיווג, embeddings וסנכרון למחשב הביתי.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!hasPermission) {
                    StatusCard("כדי לסרוק את ספריית התמונות יש לאשר גישה לתמונות במכשיר.")
                    Button(
                        onClick = { permission?.let(permissionLauncher::launch) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("אישור גישה לתמונות") }
                } else {
                    SummaryCard(total = photos.size, important = important.size, cleanup = cleanup.size)

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

                    when {
                        loading -> StatusCard("סורק את התמונות במכשיר…")
                        error != null -> StatusCard(error.orEmpty())
                        visible.isEmpty() -> StatusCard("לא נמצאו תמונות בקטגוריה הזאת.")
                        else -> PhotoGrid(
                            photos = visible,
                            onPhotoClick = { selectedPhoto = it },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = { scope.launch { refresh() } },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("סריקה מחדש") }
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<DevicePhoto>,
    onPhotoClick: (DevicePhoto) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(photos, key = { it.mediaId }) { photo ->
            PhotoTile(photo = photo, onClick = { onPhotoClick(photo) })
        }
    }
}

@Composable
private fun PhotoTile(photo: DevicePhoto, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(photo.contentUri)
                .crossfade(true)
                .size(360)
                .build(),
            contentDescription = photo.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (photo.category == PhotoCategory.CLEANUP_CANDIDATE) {
            Text(
                text = "בדיקה",
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun PhotoPreviewDialog(photo: DevicePhoto, onDismiss: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm") }
    val localDate = photo.dateAdded.atZone(ZoneId.systemDefault()).format(formatter)

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(photo.contentUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = photo.displayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(photoAspectRatio(photo)),
                    contentScale = ContentScale.Fit
                )

                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        photo.displayName.ifBlank { "תמונה ללא שם" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("$localDate · ${photo.width}×${photo.height}")
                    Text(
                        photo.relativePath.ifBlank { "תיקייה לא ידועה" },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (photo.category == PhotoCategory.CLEANUP_CANDIDATE) {
                            "מועמדת לבדיקה לפני ניקוי"
                        } else {
                            "מועמדת לשמירה וסנכרון"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 8.dp, bottom = 8.dp)
                ) { Text("סגירה") }
            }
        }
    }
}

private fun photoAspectRatio(photo: DevicePhoto): Float {
    if (photo.width <= 0 || photo.height <= 0) return 1f
    return (photo.width.toFloat() / photo.height.toFloat()).coerceIn(0.65f, 1.75f)
}

@Composable
private fun SummaryCard(total: Int, important: Int, cleanup: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$total תמונות במכשיר", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$important לשמירה · $cleanup לבדיקת ניקוי")
            Text(
                "שום תמונה אינה נמחקת אוטומטית.",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall
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
