package life.mosaic.healthconnectlab

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 72, 48, 48)
                addView(TextView(this@PermissionsRationaleActivity).apply {
                    textSize = 24f
                    text = "Health Connect Lab"
                })
                addView(TextView(this@PermissionsRationaleActivity).apply {
                    textSize = 17f
                    text = "האפליקציה קוראת נתוני אימונים מ-Health Connect לצורך בדיקה בלבד. היא אינה שומרת או שולחת את הנתונים לשום שרת."
                })
            }
        )
    }
}
