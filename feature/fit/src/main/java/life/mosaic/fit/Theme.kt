package life.mosaic.fit

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal data class ThemePalette(
    val id: String,
    val title: String,
    val description: String,
    val background: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val surface: Color,
    val surfaceHighlight: Color,
    val primary: Color,
    val secondary: Color,
    val text: Color,
    val muted: Color,
    val success: Color,
    val isLight: Boolean = false
) {
    val colorScheme
        get() = if (isLight) {
            lightColorScheme(
                primary = primary,
                secondary = secondary,
                background = background,
                surface = surface,
                onPrimary = background,
                onBackground = text,
                onSurface = text
            )
        } else {
            darkColorScheme(
                primary = primary,
                secondary = secondary,
                background = background,
                surface = surface,
                onPrimary = background,
                onBackground = text,
                onSurface = text
            )
        }
}

internal val Themes = listOf(
    ThemePalette(
        "ocean", "אוקיינוס", "כחול עמוק וטורקיז",
        Color(0xFF07111F), Color(0xFF0A1628), Color(0xFF050B14),
        Color(0xFF101D30), Color(0xFF172842), Color(0xFF62E7FF),
        Color(0xFF9B8CFF), Color(0xFFF3F7FF), Color(0xFFA8B5C8), Color(0xFF6EF2B4)
    ),
    ThemePalette(
        "forest", "יער", "ירוק רגוע עם גווני טבע",
        Color(0xFF0C1712), Color(0xFF12251C), Color(0xFF07100B),
        Color(0xFF17271F), Color(0xFF21392D), Color(0xFF7BE6A8),
        Color(0xFFD5B86A), Color(0xFFF4FAF6), Color(0xFFA9BDB0), Color(0xFF8AF0C0)
    ),
    ThemePalette(
        "sunset", "שקיעה", "סגול, ורוד וכתום חם",
        Color(0xFF180D1B), Color(0xFF2A142E), Color(0xFF0E0911),
        Color(0xFF2B1930), Color(0xFF422247), Color(0xFFFF8DAA),
        Color(0xFFFFC56E), Color(0xFFFFF4F8), Color(0xFFC8ADBE), Color(0xFF8BE6B3)
    ),
    ThemePalette(
        "paper", "בהיר ונקי", "רקע בהיר וצבעים רגועים",
        Color(0xFFF4F2EC), Color(0xFFFAF9F5), Color(0xFFECE8DF),
        Color.White, Color(0xFFF0ECE4), Color(0xFF276D68),
        Color(0xFF8C5E3C), Color(0xFF1B2523), Color(0xFF66736F), Color(0xFF2E8B67), true
    )
)
