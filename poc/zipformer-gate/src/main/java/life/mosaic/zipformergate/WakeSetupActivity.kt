package life.mosaic.zipformergate

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*

/** User-facing OS permission gate; no recording starts in this Activity. */
class WakeSetupActivity : Activity() {
    private lateinit var status: TextView
    private fun missing(): Array<String> = listOf(Manifest.permission.RECORD_AUDIO)
        .plus(if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
        .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (missing().isEmpty()) { setResult(RESULT_OK); finish(); return }
        val layout=LinearLayout(this).apply { orientation=1; setPadding(40,100,40,40) }
        status=TextView(this).apply { text="האזנה מקומית למוזאיק\n\nנדרשות הרשאת מיקרופון והתראה גלויה עם כפתור עצירה. מצב הניסוי משתמש במעבד ובסוללה. אודיו ההמתנה אינו נשמר."; textSize=20f }
        layout.addView(status)
        layout.addView(Button(this).apply { text="אישור הרשאות והמשך"; setOnClickListener { requestPermissions(missing(),91) } })
        setContentView(layout)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code,permissions,results)
        if (code==91) {
            if(missing().isEmpty()){setResult(RESULT_OK);finish()}
            else status.text="ההרשאות לא אושרו במלואן. ההאזנה לא תופעל. אפשר לחזור למוזאיק ולהמשיך בשיחה בלחיצה."
        }
    }
}
