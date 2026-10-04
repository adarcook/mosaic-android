package life.mosaic.fit.voicepoc

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import life.mosaic.voice.WhisperNative
import org.json.JSONObject
import java.io.File
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Experimental one-turn, fully local STT -> fixed-reply neural synthesis. No domain writes. */
class VoicePocActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val turn = AtomicInteger()
    private lateinit var download: Button
    private var bootstrapAvailable = false
    private lateinit var gpu: CheckBox
    private var gpuBuild = false
    private var loadedGpu = false
    private lateinit var accuracy: CheckBox
    private lateinit var status: TextView
    private lateinit var talk: Button
    private lateinit var voice: Button
    private lateinit var stop: Button
    private lateinit var models: File
    @Volatile private var foreground = false
    @Volatile private var busy = false
    @Volatile private var recording = false
    @Volatile private var transcribing = false
    @Volatile private var speechCall: SpeechCall? = null
    private lateinit var load: Button
    private lateinit var shortWindow: CheckBox
    private val diagLock = Any()
    private fun diagnostic(message: String) {
        synchronized(diagLock) {
            val file = File(filesDir, "speech-diagnostic.log")
            if (file.length() > 64000) file.writeText("")
            file.appendText("${System.currentTimeMillis()} $message\n")
        }
    }
    @Volatile private var mic: AudioRecord? = null
    @Volatile private var speaker: AudioTrack? = null
    private var verified = false
    private val autoStop = Runnable { stopRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        File(cacheDir, "speech-input.pcm").delete()
        models = File(requireNotNull(getExternalFilesDir(null)), "voice-models")
        bootstrapAvailable = try { assets.open("voice-bootstrap/index.json").close(); true } catch (_: java.io.IOException) { false }
        download = Button(this).apply {
            text = "הורדת מודלים למכשיר (כ־2GB)"
            isEnabled = bootstrapAvailable
            setOnClickListener { installModels() }
        }
        status = TextView(this).apply { textSize = 17f }
        gpuBuild = try { WhisperNative.gpuBuild() } catch (_: LinkageError) { false }
        gpu = CheckBox(this).apply {
            text = if (gpuBuild) "ניסוי האצת GPU (Vulkan)" else "GPU זמין ב־APK הניסיוני בלבד"
            isChecked = false
            isEnabled = gpuBuild
        }
        accuracy = CheckBox(this).apply {
            text = "מצב דיוק (Beam 5) — עשוי לקחת יותר זמן"
            isChecked = true
        }
        load = Button(this).apply { text = "1. בדיקת טעינת מודל בלבד"; setOnClickListener { begin(true, true) } }
        shortWindow = CheckBox(this).apply {
            text = "חלון קצר ניסיוני — השווה גם דיוק בעברית"
            isChecked = true
        }
        talk = Button(this).apply { text = "2. תמלול משפט קצר (עד 8 שניות)"; setOnClickListener { requestRecording() } }
        voice = Button(this).apply { text = "בדיקת BlueTTS בלבד"; setOnClickListener { begin(false) } }
        stop = Button(this).apply { text = "סיום משפט"; isEnabled = false; setOnClickListener {
            if (recording) stopRecording() else cancelTurn()
        } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
            setPadding(dp(16), dp(48), dp(16), dp(24))
            addView(download); addView(load); addView(shortWindow); addView(talk); addView(stop); addView(accuracy); addView(gpu); addView(voice); addView(status)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        status.text = "גרסת CPU בלבד • שני תהליכונים • ללא תמלול וקול במקביל.\nהחלון הקצר ניסיוני; אפשר לבטל אותו להשוואה עם חלון מלא.\n" +
            (if (bootstrapAvailable) "אם המודלים כבר קיימים אין צורך להוריד שוב.\n" else "הכן מודלים לפי מסמך ההתקנה.\n") +
            File(filesDir, "speech-diagnostic.log").takeIf { it.isFile }?.readText()?.takeLast(3000).orEmpty()

    }

    private fun requestRecording() {
        if (busy || !foreground) return
        if (!checkModelFiles()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        } else begin(true)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 1) status.text = if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            "הרשאה אושרה. לחץ ‘דבר בעברית’." else "לא אושרה הרשאת מיקרופון. ניתן לבדוק קול בנפרד."
    }

    private fun updateButtons() {
        download.isEnabled = bootstrapAvailable && foreground && !busy
        gpu.isEnabled = gpuBuild && foreground && !busy
        accuracy.isEnabled = foreground && !busy
        talk.isEnabled = foreground && !busy
        voice.isEnabled = foreground && !busy
        load.isEnabled = foreground && !busy
        shortWindow.isEnabled = foreground && !busy
        stop.isEnabled = foreground && busy
        stop.text = if (recording) "סיום משפט" else "ביטול פעולה"
    }
    private fun post(id: Int, action: () -> Unit) {
        handler.post { if (foreground && turn.get() == id) action() }
    }
    private fun begin(withMic: Boolean, loadOnly: Boolean = false) {
        if (busy || !foreground || !checkModelFiles()) return
        val accurate = accuracy.isChecked
        val short = shortWindow.isChecked
        val id = turn.incrementAndGet()
        busy = true; updateButtons()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "בודק חבילת מודלים…"
        worker.execute {
            fun cancelled() = !foreground || turn.get() != id
            try {
                diagnostic("attempt start loadOnly=$loadOnly short=$short beam=$accurate CPU=2")
                verifyModels()
                check(!cancelled()) { "Cancelled" }
                if (withMic) {
                    val pcm = if (loadOnly) null else capture(id)
                    check(!cancelled()) { "Cancelled" }
                    if (pcm != null) {
                        require(pcm.size in 8000..128000) { "הקלטה חייבת להיות בין חצי שנייה לשמונה שניות" }
                        DataOutputStream(File(cacheDir, "speech-input.pcm").outputStream().buffered()).use { out ->
                            for (sample in pcm) out.writeFloat(sample)
                        }
                        diagnostic("audio samples=${pcm.size} seconds=${pcm.size / 16000f}")
                    }
                    val start = SystemClock.elapsedRealtime()
                    val call = SpeechCall(applicationContext) { phase ->
                        val stage = when (phase) { 1 -> "encoder"; 2 -> "decoder"; else -> "טעינת מודל / הכנת שמע" }
                        diagnostic("phase=$stage elapsedMs=${SystemClock.elapsedRealtime() - start}")
                        post(id) { status.text = "$stage • ${(SystemClock.elapsedRealtime() - start) / 1000} שניות\nCPU בלבד • חלון ${if (short) "קצר" else "מלא"} • מגבלה 90 שניות" }
                    }
                    speechCall = call; transcribing = true
                    if (cancelled()) call.cancel()
                    val result = call.run(loadOnly, accurate, short)
                    speechCall = null; transcribing = false
                    diagnostic("terminal error=${result.getString("error").orEmpty()} loadMs=${result.getLong("loadMs")} inferenceMs=${result.getLong("inferenceMs")} ${result.getString("timings").orEmpty()}")
                    result.getString("error")?.let { error(it) }
                    check(!cancelled()) { "Cancelled" }
                    val text = result.getString("text").orEmpty()
                    if (!loadOnly) require(text.isNotBlank()) { "לא התקבל תמלול" }
                    post(id) { status.text = if (loadOnly) "טעינת המודל הצליחה.\n${result.getLong("loadMs")} ms" else
                        "תמלול: $text\nload: ${result.getLong("loadMs")} ms\ninference: ${result.getLong("inferenceMs")} ms\n${result.getString("timings")}\naudio: ${pcm!!.size / 16000f} s" }
                } else {
                    post(id) { status.text = "בדיקת BlueTTS נפרדת…" }
                    val (audio, rate) = BlueSynthesizer(models).synthesize(::cancelled)
                    check(!cancelled()) { "Cancelled" }
                    play(audio, rate, ::cancelled)
                    post(id) { status.text = "בדיקת הקול הסתיימה." }
                }
            } catch (e: Exception) {
                diagnostic("attempt stopped ${e.javaClass.simpleName}: ${e.message}")
                post(id) { status.text = "הבדיקה נעצרה: ${e.message}" }
            } catch (e: LinkageError) {
                diagnostic("native linkage error")
                post(id) { status.text = "לא ניתן לטעון את מנוע הקול: ${e.message}" }
            } finally {
                File(cacheDir, "speech-input.pcm").delete()
                speechCall = null; transcribing = false; busy = false; recording = false
                handler.post { if (!isDestroyed) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    updateButtons()
                    if (foreground && turn.get() != id) status.text = "הפעולה בוטלה."
                } }
            }
        }
    }

    private fun installModels() {
        if (busy || !foreground || !bootstrapAvailable) return
        val id = turn.incrementAndGet()
        busy = true; updateButtons()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "מכין הורדת מודלים… השאר את האפליקציה פתוחה."
        worker.execute {
            fun cancelled() = !foreground || turn.get() != id
            try {
                val index = JSONObject(assets.open("voice-bootstrap/index.json").bufferedReader().use { it.readText() })
                val manifest = index.getJSONObject("manifest")
                val entries = index.getJSONArray("downloads")
                val specs = (0 until entries.length()).map { i ->
                    val item = entries.getJSONObject(i)
                    ModelDownload(item.getString("path"), item.getString("url"), item.getString("sha256"), item.getLong("size"))
                }
                val total = specs.sumOf { it.size }
                models.mkdirs()
                val needed = specs.sumOf {
                    val target = VoiceModelInstaller.target(models, it.path)
                    maxOf(0L, it.size - maxOf(target.length(), File(target.path + ".download").length()))
                }
                require(models.usableSpace > needed + 64L * 1024 * 1024) { "אין מספיק מקום פנוי להורדת המודלים" }
                verified = false
                val installer = VoiceModelInstaller(models)
                var completed = 0L
                var lastUpdate = 0L
                for (spec in specs) {
                    check(!cancelled()) { "Cancelled" }
                    post(id) { status.text = "מוריד/בודק ${spec.path}…" }
                    installer.installFile(spec, ::cancelled) { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 500 || bytes == spec.size) {
                            lastUpdate = now
                            val percent = (100 * (completed + bytes) / total).toInt()
                            val mb = (completed + bytes) / (1024 * 1024)
                            post(id) { status.text = "הורדת מודלים: $percent% ($mb / ${total / (1024 * 1024)} MB)\n${spec.path}\nהשאר את המסך פתוח. ניתן לבטל ולהמשיך בהמשך." }
                        }
                    }
                    completed += spec.size
                }
                check(!cancelled()) { "Cancelled" }
                val reply = assets.open("voice-bootstrap/reply.json").use { it.readBytes() }
                installer.commitFixture(reply, manifest.getJSONObject("files").getString("reply.json"), manifest.toString().toByteArray(Charsets.UTF_8))
                verifyModels()
                post(id) { status.text = "המודלים מוכנים. בצע תחילה בדיקת טעינת מודל בלבד.\nמכאן ניתן לעבוד גם במצב טיסה." }
            } catch (e: Exception) {
                post(id) { status.text = "ההתקנה נעצרה: ${e.message}\nלחץ על הורדת מודלים כדי להמשיך. קבצים שהושלמו נשמרו." }
            } finally {
                busy = false
                handler.post { if (!isDestroyed) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    updateButtons()
                    if (foreground && turn.get() != id) status.text = "ההורדה בוטלה. לחץ ‘הורדת מודלים’ כדי להמשיך."
                } }
            }
        }
    }

    private fun checkModelFiles(): Boolean {
        val missing = VoiceModelFiles.missing(models)
        if (missing.isEmpty()) return true
        status.text = "חבילת המודלים טרם הותקנה או שההעתקה לא הושלמה.\n" +
            "התקנת ה־APK אינה כוללת את המודלים.\n" +
            (if (bootstrapAvailable) "לחץ ‘הורדת מודלים למכשיר’. אין צורך במחשב.\n" else "במחשב: הפעל scripts/prepare_voice_poc.py והעתק דרך adb.\n") +
            "יעד: ${models.path}\nקבצים חסרים:\n${missing.joinToString("\n")}"
        return false
    }

    private fun verifyModels() {
        if (verified) return
        val file = File(models, "manifest.json")
        require(file.isFile) { "חבילת המודלים חסרה. הפעל prepare_voice_poc.py והעתק לפי המסמך." }
        val entries = JSONObject(file.readText()).getJSONObject("files")
        val required = setOf("reply.json", "whisper/ggml-model.bin", "blue/duration_predictor_style.onnx",
            "blue/text_encoder.onnx", "blue/vector_estimator.onnx", "blue/vocoder.onnx")
        require(required.all { entries.has(it) }) { "Manifest incomplete" }
        for (name in entries.keys()) {
            val target = File(models, name).canonicalFile
            require(target.path.startsWith(models.canonicalPath + File.separator) && target.isFile) { "Missing model: $name" }
            val digest = MessageDigest.getInstance("SHA-256")
            target.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hex == entries.getString(name)) { "Model checksum mismatch: $name" }
        }
        verified = true
    }

    @Suppress("MissingPermission")
    private fun capture(id: Int): FloatArray {
        val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(size > 0) { "Microphone format unavailable" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(size, 8192))
        val samples = FloatArray(16000 * 8)
        var count = 0
        try {
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            mic = recorder; recording = true
            if (!foreground || turn.get() != id) return floatArrayOf()
            recorder.startRecording()
            post(id) {
                status.append("\nדבר עכשיו; לחץ ‘סיום משפט’. עד 8 שניות.")
                updateButtons(); handler.postDelayed(autoStop, 8_000)
            }
            val chunk = ShortArray(1024)
            while (recording && foreground && turn.get() == id && count < samples.size) {
                val n = recorder.read(chunk, 0, minOf(chunk.size, samples.size - count), AudioRecord.READ_BLOCKING)
                if (n < 0) { if (!recording) break else error("Microphone read failed: $n") }
                for (i in 0 until n) samples[count++] = chunk[i] / 32768f
            }
            return samples.copyOf(count)
        } finally {
            recording = false; mic = null
            try { recorder.stop() } catch (_: IllegalStateException) {}
            recorder.release(); handler.removeCallbacks(autoStop)
            post(id) { updateButtons() }
        }
    }
    private fun stopRecording() {
        recording = false; handler.removeCallbacks(autoStop)
        try { mic?.stop() } catch (_: IllegalStateException) {}
        updateButtons()
    }
    private fun cancelTurn() {
        turn.incrementAndGet(); stopRecording()
        speechCall?.cancel()
        diagnostic("user cancellation")
        try { speaker?.pause(); speaker?.flush() } catch (_: IllegalStateException) {}
        status.text = "מבטל את הפעולה…"
    }
    private fun play(audio: FloatArray, rate: Int, cancelled: () -> Boolean) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(maxOf(16384, AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try {
            speaker = track
            if (cancelled()) return
            track.play()
            var written = 0
            while (!cancelled() && written < audio.size) {
                val n = track.write(audio, written, minOf(2048, audio.size - written), AudioTrack.WRITE_BLOCKING)
                check(n > 0) { "Playback failed: $n" }; written += n
            }
            while (!cancelled() && track.playbackHeadPosition < written) Thread.sleep(20)
        } finally {
            speaker = null
            try { track.stop() } catch (_: IllegalStateException) {}
            track.release()
        }
    }
    override fun onResume() { super.onResume(); foreground = true; updateButtons() }
    override fun onPause() {
        foreground = false; turn.incrementAndGet(); stopRecording()
        speechCall?.cancel("Cancelled when app left foreground")
        if (busy) diagnostic("activity left foreground; cancellation requested")
        try { speaker?.pause(); speaker?.flush() } catch (_: IllegalStateException) {}
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speechCall?.cancel("Activity destroyed")
        worker.shutdown()
        super.onDestroy()
    }
}
