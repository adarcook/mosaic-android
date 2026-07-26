package life.mosaic.fit

import android.content.Context
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
import life.mosaic.fit.data.MealJournal

private const val PreferencesName = "mosaic_fit_preferences"
private const val ThemePreferenceKey = "selected_theme"
private const val ServerUrlPreferenceKey = "server_url"
internal const val DefaultServerUrl = "http://192.168.1.200:8000"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        val initialTheme = preferences.getString(ThemePreferenceKey, Themes.first().id)
            ?: Themes.first().id
        val initialServer = preferences.getString(ServerUrlPreferenceKey, DefaultServerUrl)
            ?: DefaultServerUrl
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
                            preferences.edit().putString(ServerUrlPreferenceKey, it).apply()
                        },
                        selectedThemeId = themeId,
                        onThemeSelected = {
                            themeId = it
                            preferences.edit().putString(ThemePreferenceKey, it).apply()
                        }
                    )
                }
            }
        }
    }
}
