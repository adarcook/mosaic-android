package life.mosaic.fit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import life.mosaic.core.settings.MosaicSettings
import life.mosaic.feature.fit.FitMealJournal

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = MosaicSettings(this)
        val journal = FitMealJournal(this)

        setContent {
            var themeId by remember { mutableStateOf(settings.selectedThemeId) }
            var serverUrl by remember { mutableStateOf(settings.serverUrl) }
            var dailyCalorieGoal by remember { mutableStateOf(settings.dailyCalorieGoal) }
            var dailyProteinGoalG by remember { mutableStateOf(settings.dailyProteinGoalG) }

            MosaicAppShell(
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
                },
                dailyCalorieGoal = dailyCalorieGoal,
                onDailyCalorieGoalChanged = {
                    dailyCalorieGoal = it
                    settings.dailyCalorieGoal = it
                },
                dailyProteinGoalG = dailyProteinGoalG,
                onDailyProteinGoalChanged = {
                    dailyProteinGoalG = it
                    settings.dailyProteinGoalG = it
                }
            )
        }
    }
}
