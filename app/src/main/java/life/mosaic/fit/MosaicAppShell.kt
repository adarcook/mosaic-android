package life.mosaic.fit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.mosaic.feature.fit.FitApp
import life.mosaic.feature.fit.FitMealJournal
import life.mosaic.feature.photos.PhotosApp

private enum class MosaicDestination {
    Home,
    Fit,
    Photos
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MosaicAppShell(
    journal: FitMealJournal,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit
) {
    var destination by remember { mutableStateOf(MosaicDestination.Home) }

    BackHandler(enabled = destination != MosaicDestination.Home) {
        destination = MosaicDestination.Home
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                if (destination != MosaicDestination.Home) {
                    TopAppBar(
                        title = {
                            Text(
                                when (destination) {
                                    MosaicDestination.Fit -> "Mosaic Fit"
                                    MosaicDestination.Photos -> "Mosaic Photos"
                                    MosaicDestination.Home -> "Mosaic"
                                }
                            )
                        },
                        navigationIcon = {
                            TextButton(onClick = { destination = MosaicDestination.Home }) {
                                Text("חזרה")
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (destination) {
                    MosaicDestination.Home -> MosaicHome(
                        onOpenFit = { destination = MosaicDestination.Fit },
                        onOpenPhotos = { destination = MosaicDestination.Photos }
                    )

                    MosaicDestination.Fit -> FitApp(
                        journal = journal,
                        serverUrl = serverUrl,
                        onServerUrlChanged = onServerUrlChanged,
                        selectedThemeId = selectedThemeId,
                        onThemeSelected = onThemeSelected
                    )

                    MosaicDestination.Photos -> PhotosApp()
                }
            }
        }
    }
}

@Composable
private fun MosaicHome(
    onOpenFit: () -> Unit,
    onOpenPhotos: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "MOSAIC",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "המרכז האישי שלך",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "בחר את התחום שברצונך לפתוח",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))

            FeatureCard(
                symbol = "✦",
                title = "תזונה וכושר",
                description = "ניתוח ארוחות, מעקב יומי ומגמות",
                onClick = onOpenFit
            )
            Spacer(Modifier.height(16.dp))
            FeatureCard(
                symbol = "▣",
                title = "תמונות",
                description = "ארכיון חכם, ניקוי וזיהוי מקומי",
                onClick = onOpenPhotos
            )
        }
    }
}

@Composable
private fun FeatureCard(
    symbol: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = symbol,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "←",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 24.sp
            )
        }
    }
}
