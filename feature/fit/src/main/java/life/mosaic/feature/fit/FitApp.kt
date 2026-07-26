package life.mosaic.feature.fit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import life.mosaic.fit.MosaicFitRoot
import life.mosaic.fit.Themes

@Composable
fun FitApp(
    journal: FitMealJournal,
    serverUrl: String,
    onServerUrlChanged: (String) -> Unit,
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit
) {
    val palette = Themes.firstOrNull { it.id == selectedThemeId } ?: Themes.first()

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = palette.colorScheme) {
            MosaicFitRoot(
                palette = palette,
                journal = journal,
                serverUrl = serverUrl,
                onServerUrlChanged = onServerUrlChanged,
                selectedThemeId = selectedThemeId,
                onThemeSelected = onThemeSelected
            )
        }
    }
}
