package life.mosaic.fit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import life.mosaic.core.settings.MosaicSettings
import life.mosaic.feature.fit.FitApp
import life.mosaic.feature.fit.FitMealJournal

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = MosaicSettings(this)
        val journal = FitMealJournal(this)

        setContent {
            var themeId by remember { mutableStateOf(settings.selectedThemeId) }
            var serverUrl by remember { mutableStateOf(settings.serverUrl) }

            FitApp(
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
