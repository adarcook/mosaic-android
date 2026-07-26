package life.mosaic.core.settings

import android.content.Context
import android.content.SharedPreferences

class MosaicSettings(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    var selectedThemeId: String
        get() = preferences.getString(KEY_SELECTED_THEME, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID
        set(value) = preferences.edit().putString(KEY_SELECTED_THEME, value).apply()

    var serverUrl: String
        get() = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = preferences.edit().putString(KEY_SERVER_URL, value).apply()

    companion object {
        const val DEFAULT_THEME_ID = "ocean"
        const val DEFAULT_SERVER_URL = "http://192.168.1.200:8000"

        private const val PREFERENCES_NAME = "mosaic_fit_preferences"
        private const val KEY_SELECTED_THEME = "selected_theme"
        private const val KEY_SERVER_URL = "server_url"
    }
}
