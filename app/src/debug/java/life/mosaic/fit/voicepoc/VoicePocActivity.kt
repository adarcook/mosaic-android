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
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Experimental one-turn, fully local STT -> fixed-reply neural synthesis. No domain writes. */
class VoicePocActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val turn = AtomicInteger()
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
    private val nativeLock = Any()
    private var whisperHandle = 0L // Owned by worker; cancel/release share nativeLock.
    @Volatile private var mic: AudioRecord? = null
    @Volatile private var speaker: AudioTrack? = null
    private var verified = false
    private val autoStop = Runnable { stopRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = File(requireNotNull(getExternalFilesDir(null)), "voice-models")
        status = TextView(this).apply { textSize = 17f }
        accuracy = CheckBox(this).apply {
            text = "מצב דיוק (Beam 5) — עשוי לקחת יותר זמן"
            isChecked = true
        }
        talk = Button(this).apply { text = "דבר בעברית"; setOnClickListener { requestRecording() } }
        voice = Button(this).apply { text = "בדיקת BlueTTS בלבד"; setOnClickListener { begin(false) } }
        stop = Button(this).apply { text = "סיום משפט"; isEnabled = false; setOnClickListener {
            if (recording) stopRecording() else cancelTurn()
        } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
            setPadding(dp(16), dp(48), dp(16), dp(24))
            addView(talk); addView(stop); addView(accuracy); addView(voice); addView(status)
        }
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        status.text = "Whisper Small Q5 → BlueTTS 2.5\nתשובה קבועה; ללא LLM.\nמודלים: ${models.path}\nהכן והעתק את חבילת המודלים לפי המסמך ב־PR."
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
        accuracy.isEnabled = foreground && !busy
        talk.isEnabled = foreground && !busy
        voice.isEnabled = foreground && !busy
        stop.isEnabled = foreground && busy
        stop.text = if (recording) "סיום משפט" else "ביטול פעולה"
    }
    private fun post(id: Int, action: () -> Unit) {
        handler.post { if (foreground && turn.get() == id) action() }
    }
    private fun begin(withMic: Boolean) {
        if (busy || !foreground) return
        if (!checkModelFiles()) return
        val accurate = accuracy.isChecked
        val mode = if (accurate) "דיוק (Beam 5)" else "מהיר (Greedy)"
        val id = turn.incrementAndGet()
        busy = true; updateButtons()
        status.text = "בודק חבילת מודלים מקומית…"
        worker.execute {
            fun cancelled() = !foreground || turn.get() != id
            try {
                verifyModels()
                check(!cancelled()) { "Cancelled" }
                if (withMic) {
                    val profile = JSONObject(File(models, "manifest.json").readText()).optString("stt_profile")
                    require(profile == "small-q5_1") {
                        "יש לעדכן למודל התמלול הקטן: python -X utf8 scripts/prepare_voice_poc.py --stt-only, ואז להעתיק whisper ו־manifest.json לפיקסל."
                    }
                    val loadStart = SystemClock.elapsedRealtime()
                    val cached = whisperHandle != 0L
                    if (!cached) {
                        post(id) { status.append("\nטוען Whisper Small Q5 לזיכרון…") }
                        WhisperNative.ensureLoaded()
                        val handle = WhisperNative.create(File(models, "whisper/ggml-model.bin").path)
                        check(handle != 0L) { "Whisper load failed" }
                        synchronized(nativeLock) { whisperHandle = handle }
                    }
                    check(!cancelled()) { "Cancelled" }
                    val loadMs = SystemClock.elapsedRealtime() - loadStart
                    post(id) { status.append("\nSTT load: $loadMs ms${if (cached) " (cached)" else ""}") }
                    val pcm = capture(id)
                    check(!cancelled()) { "Cancelled" }
                    require(pcm.size >= 8000) { "המשפט קצר מדי. נסה לפחות חצי שנייה." }
                    post(id) { status.append("\nמתמלל מקומית…") }
                    val start = SystemClock.elapsedRealtime()
                    transcribing = true
                    synchronized(nativeLock) { WhisperNative.prepare(whisperHandle) }
                    val ticker = object : Runnable {
                        override fun run() {
                            if (foreground && turn.get() == id && transcribing) {
                                val phase = synchronized(nativeLock) { WhisperNative.phase(whisperHandle) }
                                val stage = when (phase) {
                                    1 -> "מחשב ייצוג שמע (encoder)"
                                    2 -> "מפענח מילים (decoder)"
                                    else -> "מכין שמע"
                                }
                                status.text = "ARM FP16 + dotprod\nWhisper Small Q5 — $mode\nSTT load: $loadMs ms\n$stage… ${(SystemClock.elapsedRealtime() - start) / 1000} שניות\nניתן ללחוץ ‘ביטול פעולה’."
                                handler.postDelayed(this, 1000)
                            }
                        }
                    }
                    handler.post(ticker)
                    val text = try {
                        check(!cancelled()) { "Cancelled" }
                        WhisperNative.transcribe(whisperHandle, pcm, accurate).trim()
                    } finally { transcribing = false; handler.removeCallbacks(ticker) }
                    val nativeTimings = WhisperNative.timings(whisperHandle)
                    val inferenceMs = SystemClock.elapsedRealtime() - start
                    require(text.isNotEmpty()) { "לא התקבל תמלול" }
                    post(id) { status.append("\nתמלול ($mode): $text\nSTT inference: $inferenceMs ms\n$nativeTimings\naudio: ${pcm.size / 16000f} s") }
                }
                check(!cancelled()) { "Cancelled" }
                post(id) { status.append("\nמסנתז תשובת בדיקה ב־BlueTTS…") }
                val start = SystemClock.elapsedRealtime()
                val (audio, rate) = BlueSynthesizer(models).synthesize(::cancelled)
                check(!cancelled()) { "Cancelled" }
                post(id) { status.append("\nTTS load+synthesis: ${SystemClock.elapsedRealtime() - start} ms\nמשמיע תשובה קבועה (נתוני דוגמה).") }
                play(audio, rate, ::cancelled)
                post(id) { status.append("\nהסתיים. בדוק דיוק, קול ומהירות גם במצב טיסה.") }
            } catch (e: Exception) {
                post(id) { status.append("\nשגיאה: ${e.message ?: e.javaClass.simpleName}") }
            } catch (e: LinkageError) {
                post(id) { status.append("\nלא ניתן לטעון את ספריית מנוע הקול: ${e.message ?: e.javaClass.simpleName}\nיש לעדכן את התקנת ה־APK ולשלוח את ההודעה הזו לבדיקה.") }
            } finally {
                busy = false; recording = false
                handler.post { if (!isDestroyed) {
                    updateButtons()
                    if (foreground && turn.get() != id) status.text = "הפעולה בוטלה. ניתן לנסות שוב."
                } }
            }
        }
    }

    private fun checkModelFiles(): Boolean {
        val missing = VoiceModelFiles.missing(models)
        if (missing.isEmpty()) return true
        status.text = "חבילת המודלים טרם הותקנה או שההעתקה לא הושלמה.\n" +
            "התקנת ה־APK אינה כוללת את המודלים.\n" +
            "במחשב: הפעל scripts/prepare_voice_poc.py, ואז העתק את voice-models דרך adb לפי docs/hebrew-voice-poc.md.\n" +
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
        val samples = FloatArray(16000 * 12)
        var count = 0
        try {
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            mic = recorder; recording = true
            if (!foreground || turn.get() != id) return floatArrayOf()
            recorder.startRecording()
            post(id) {
                status.append("\nדבר עכשיו; לחץ ‘סיום משפט’. עד 12 שניות.")
                updateButtons(); handler.postDelayed(autoStop, 12_000)
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
        synchronized(nativeLock) {
            if (whisperHandle != 0L && transcribing) WhisperNative.cancel(whisperHandle)
        }
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
        // Cancel native inference if loaded; no model download or initialization here.
        if (transcribing) {
            synchronized(nativeLock) {
                if (whisperHandle != 0L) WhisperNative.cancel(whisperHandle)
            }
        }
        try { speaker?.pause(); speaker?.flush() } catch (_: IllegalStateException) {}
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Queue destruction behind active inference; never free a context being used.
        worker.execute {
            synchronized(nativeLock) {
                if (whisperHandle != 0L) { WhisperNative.release(whisperHandle); whisperHandle = 0L }
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
