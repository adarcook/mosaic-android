package life.mosaic.fit

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        layout.addView(TextView(this).apply {
            text = "Mosaic משתמשת ב-Health Connect כדי לקרוא אימוני שחייה, אימוני כוח ודופק. הנתונים נשארים במכשיר ומשמשים להצגת היסטוריה, עמידה ביעדים וניתוח האימונים."
            textSize = 18f
        })

        setContentView(layout)
    }
}
