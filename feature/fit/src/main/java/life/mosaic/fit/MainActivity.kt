package life.mosaic.fit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import life.mosaic.core.settings.MosaicSettings
import life.mosaic.fit.data.MealJournal

internal const val DefaultServerUrl = MosaicSettings.DEFAULT_SERVER_URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = MosaicSettings(this)
        val initialTheme = settings.selectedThemeId
        val initialServer = settings.serverUrl
        val journal = MealJournal(this)

        setContent {
            var themeId by remember { mutableStateOf(initialTheme) }
            var serverUrl by remember { mutableStateOf(initialServer) }
            val palette = Themes.firstOrNull { it.id == themeId } ?: Themes.first()

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(colorScheme = palette.colorScheme) {
                    MosaicFitRoot(
                        palette = palette,
                        journal = journal,
                        serverUrl = serverUrl,
                        onServerUrlChanged = {
                            serverUrl = it
                            settings.serverUrl = it
                        },
                        selectedThemeId = themeId,
                        onThemeSelected = {
                            themeId = it
                            settings.selectedThemeId = it
                        }
                    )
                }
            }
        }
    }
}
