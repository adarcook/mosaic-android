package life.mosaic.feature.photos

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.runtime.collectAsState
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import life.mosaic.core.data.photos.PhotoRepository
import life.mosaic.core.database.photos.PhotoEntity

private const val IMPORTANT = "IMPORTANT_CANDIDATE"
private const val CLEANUP = "CLEANUP_CANDIDATE"

@Composable
fun PhotosApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember(context) { PhotoRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val permission = photoReadPermission()
    val photos by repository.observePhotos().collectAsState(initial = emptyList())
    var hasPermission by remember {
        mutableStateOf(permission == null || ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf(IMPORTANT) }
    var selectedPhoto by remember { mutableStateOf<PhotoEntity?>(null) }

    suspend fun refresh() {
        loading = true
        error = null
        runCatching { repository.refresh() }
            .onFailure { error = it.message ?: "סריקת התמונות נכשלה" }
        loading = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) error = "נדרשת הרשאה לקריאת התמונות במכשיר"
    }

    LaunchedEffect(hasPermission) { if (hasPermission) refresh() }

    val important = photos.filter { it.automaticCategory == IMPORTANT }
    val cleanup = photos.filter { it.automaticCategory == CLEANUP }
    val visible = if (selectedCategory == IMPORTANT) important else cleanup

    selectedPhoto?.let { PhotoPreviewDialog(it) { selectedPhoto = null } }

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Mosaic Photos", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("הגלריה נטענת ממסד מקומי ומתעדכנת מול ספריית התמונות במכשיר.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!hasPermission) {
                    StatusCard("כדי לסרוק את ספריית התמונות יש לאשר גישה לתמונות במכשיר.")
                    Button(onClick = { permission?.let(permissionLauncher::launch) }, modifier = Modifier.fillMaxWidth()) {
                        Text("אישור גישה לתמונות")
                    }
                } else {
                    SummaryCard(photos.size, important.size, cleanup.size)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryButton("חשובות ${important.size}", selectedCategory == IMPORTANT, { selectedCategory = IMPORTANT }, Modifier.weight(1f))
                        CategoryButton("לבדיקת ניקוי ${cleanup.size}", selectedCategory == CLEANUP, { selectedCategory = CLEANUP }, Modifier.weight(1f))
                    }
                    when {
                        loading && photos.isEmpty() -> StatusCard("סורק את התמונות במכשיר…")
                        error != null -> StatusCard(error.orEmpty())
                        visible.isEmpty() -> StatusCard("לא נמצאו תמונות בקטגוריה הזאת.")
                        else -> PhotoGrid(visible, { selectedPhoto = it }, Modifier.weight(1f))
                    }
                    Button(onClick = { scope.launch { refresh() } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        Text(if (loading) "סורק…" else "סריקה מחדש")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(photos: List<PhotoEntity>, onPhotoClick: (PhotoEntity) -> Unit, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items(photos, key = { it.mediaId }) { photo -> PhotoTile(photo) { onPhotoClick(photo) } }
    }
}

@Composable
private fun PhotoTile(photo: PhotoEntity, onClick: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick)) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(photo.contentUri).crossfade(true).size(360).build(),
            contentDescription = photo.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        if (photo.automaticCategory == CLEANUP) {
            Text(
                "בדיקה",
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PhotoPreviewDialog(photo: PhotoEntity, onDismiss: () -> Unit) {
    val formatter = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm") }
    val localDate = Instant.ofEpochSecond(photo.dateAddedEpochSeconds).atZone(ZoneId.systemDefault()).format(formatter)
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(photo.contentUri).crossfade(true).build(),
                    contentDescription = photo.displayName,
                    modifier = Modifier.fillMaxWidth().aspectRatio(photoAspectRatio(photo)),
                    contentScale = ContentScale.Fit
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(photo.displayName.ifBlank { "תמונה ללא שם" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$localDate · ${photo.width}×${photo.height}")
                    Text(photo.relativePath.ifBlank { "תיקייה לא ידועה" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (photo.automaticCategory == CLEANUP) "מועמדת לבדיקה לפני ניקוי" else "מועמדת לשמירה וסנכרון", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 8.dp)) { Text("סגירה") }
            }
        }
    }
}

private fun photoAspectRatio(photo: PhotoEntity): Float {
    if (photo.width <= 0 || photo.height <= 0) return 1f
    return (photo.width.toFloat() / photo.height.toFloat()).coerceIn(0.65f, 1.75f)
}

@Composable
private fun SummaryCard(total: Int, important: Int, cleanup: Int) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$total תמונות במכשיר", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$important לשמירה · $cleanup לבדיקת ניקוי")
            Text("שום תמונה אינה נמחקת אוטומטית.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CategoryButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) Button(onClick = onClick, modifier = modifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
}

@Composable
private fun StatusCard(message: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Text(message, modifier = Modifier.padding(18.dp)) }
}

private fun photoReadPermission(): String? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
    else -> null
}
