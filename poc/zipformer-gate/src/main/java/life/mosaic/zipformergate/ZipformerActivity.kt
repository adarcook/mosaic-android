package life.mosaic.zipformergate

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.documentfile.provider.DocumentFile
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.FileWriter
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone, offline-only Hebrew streaming ASR experiment.
 * No access to Mosaic's production Whisper, Gemma, TTS, model directories or user data.
 */
class ZipformerActivity : Activity() {
    companion object {
        private const val PICK_FOLDER = 100
        private const val SAVE_JOURNAL = 101
        private const val MICROPHONE_PERMISSION = 102
        private const val PICK_REPLAY_WAV = 103
        private const val SAMPLE_RATE = 16_000
        private const val MAX_AUDIO_SECONDS = 30
        private const val MODEL_REV = "ee769a2a13b887f91572eec349458bfb0fe79e7a"
        private const val REPO_ID = "ivrit-ai/zipformer2-66m-streaming-he"
        private const val RUNTIME_SHA = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
        private val FILES = linkedMapOf(
            "encoder.int8.onnx" to Pair(70_478_964L, "6c6febcb74a09435315a1d872a6539f6e7fc2c333533f14c74edc0e85c49ffcd"),
            "decoder.int8.onnx" to Pair(1_049_105L, "feb03e7c8aa761593a9284a6fa1e91259be3702de75f630062256c1a59c5a906"),
            "joiner.int8.onnx" to Pair(771_805L, "1d2c6d8e6dabc5eb52de52e95718e9bdfbffbfe7ead3808fb5ffd74e83bd9cae"),
            "tokens.txt" to Pair(17_086L, "") // Hash verified against private SHA-256 lock.
        )
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val stopRequested = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var metrics: TextView
    private lateinit var importButton: Button
    private lateinit var prepareButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var replayButton: Button
    private lateinit var exportButton: Button
    private var busy = false  // UI-thread-owned
    private var loaded = false  // UI-thread-owned
    private var ready = false  // UI-thread-owned
    private var recognizer: OnlineRecognizer? = null  // worker-thread-owned
    private var latestJournal: File? = null
    private val modelFolder by lazy { File(filesDir, "models/zipformer-he") }
    private val journalFolder by lazy { File(filesDir, "journals") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)

        fun label(value: String, size: Float = 17f): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setPadding(0, 12, 0, 16)
                column.addView(this)
            }
        fun button(value: String, action: () -> Unit): Button =
            Button(this).apply {
                text = value
                setOnClickListener { action() }
                column.addView(this)
            }
        label("Mosaic · Zipformer Hebrew Streaming", 22f)
        label("ניסוי CPU מקומי בלבד · אין גישה לאינטרנט · עד 30 שניות הקלטה", 14f)
        status = label("מאתר מודל מקומי…")
        importButton = button("1. ייבוא תיקיית Zipformer מאחסון המכשיר") {
            if (loaded) {
                status.text = "כדי לייבא מודל אחר יש לסגור ולפתוח את אפליקציית הניסוי."
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivityForResult(intent, PICK_FOLDER)
            }
        }
        prepareButton = button("2. טעינת מנוע Streaming") { prepare() }
        startButton = button("3. דבר — הצג תמלול בזמן אמת") { startRecording() }
        stopButton = button("סיום משפט") { stopRequested.set(true); status.text = "מסיים פענוח…" }
        replayButton = button("בדיקת WAV קיים — השוואת סוף משפט") {
            if (busy || !loaded) return@button
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*" // WAV may be audio/wav, audio/x-wav, or octet-stream.
            }
            startActivityForResult(intent, PICK_REPLAY_WAV)
        }
        transcript = label("התמלול יופיע כאן בזמן הדיבור.", 22f)
        transcript.setTextIsSelectable(true)
        metrics = label("מדידות טרם נאספו.", 16f)
        exportButton = button("ייצוא יומן הניסוי האחרון") { exportJournal() }
        updateButtons()

        worker.execute {
            try {
                validateModel(modelFolder)
                runOnUiThread {
                    ready = true
                    status.text = "המודל מאומת ומוכן לטעינה."
                    updateButtons()
                }
            } catch (_: Exception) {
                show("בחר תיקייה שהועתקה לפיקסל, הכוללת zipformer-lock.json ותיקיית sherpa-onnx.")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        when (requestCode) {
            PICK_REPLAY_WAV -> replayWav(data.data!!)
            PICK_FOLDER -> {
                if (busy || loaded) return
                setBusy(true)
                val uri = data.data!!
                worker.execute {
                    try {
                        importModel(uri)
                        runOnUiThread {
                            ready = true
                            status.text = "ייבוא הצליח; ארבעת הקבצים וחתימות SHA-256 אומתו."
                            setBusy(false)
                        }
                    } catch (t: Throwable) {
                        show("ייבוא נכשל: ${t.message}")
                        runOnUiThread { setBusy(false) }
                    }
                }
            }
            SAVE_JOURNAL -> {
                val file = latestJournal ?: return
                try {
                    contentResolver.openOutputStream(data.data!!)?.use { output ->
                        FileInputStream(file).use { input -> input.copyTo(output) }
                    } ?: error("Cannot write selected journal destination")
                    status.text = "יומן הניסוי נשמר בהצלחה."
                } catch (e: Exception) {
                    status.text = "ייצוא היומן נכשל: ${e.message}"
                }
            }
        }
    }

    private fun importModel(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri) ?: error("Cannot read selected directory")
        val source = root.findFile("sherpa-onnx") ?: error("Missing sherpa-onnx folder")
        val lockDocument = root.findFile("zipformer-lock.json") ?: error("Missing zipformer-lock.json")
        require(lockDocument.isFile && lockDocument.length() <= 128_000L) { "Invalid lock" }
        val lock = contentResolver.openInputStream(lockDocument.uri)!!.bufferedReader().use {
            JSONObject(it.readText())
        }
        val manifest = checkedLock(lock)
        val stage = File(filesDir, "models/zipformer-stage")
        if (stage.exists()) stage.deleteRecursively()
        check(stage.mkdirs()) { "Cannot create model stage" }
        try {
            for ((name, required) in FILES) {
                val entry = source.findFile(name) ?: error("Model file missing: $name")
                require(entry.isFile) { "Not a file: $name" }
                val outFile = File(stage, name)
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                contentResolver.openInputStream(entry.uri)!!.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            bytes += n
                            require(bytes <= required.first) { "Oversize asset: $name" }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                        }
                    }
                }
                require(bytes == required.first) { "Unexpected size: $name" }
                val actual = hex(digest.digest())
                val pinned = manifest.getJSONObject("sherpa-onnx/$name")
                require(pinned.getLong("bytes") == bytes && pinned.getString("sha256") == actual) {
                    "Lock digest mismatch: $name"
                }
                if (required.second.isNotEmpty()) {
                    require(actual == required.second) { "Pinned digest mismatch: $name" }
                }
            }
            File(stage, "zipformer-lock.json").writeText(lock.toString(2))
            validateModel(stage)
            val backup = File(filesDir, "models/zipformer-backup")
            if (backup.exists()) backup.deleteRecursively()
            if (modelFolder.exists()) check(modelFolder.renameTo(backup)) { "Cannot back up old model" }
            if (!stage.renameTo(modelFolder)) {
                if (backup.exists()) backup.renameTo(modelFolder)
                error("Cannot activate validated model")
            }
            if (backup.exists()) backup.deleteRecursively()
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun checkedLock(lock: JSONObject): JSONObject {
        require(lock.getInt("format") == 1 &&
                lock.getString("repo_id") == REPO_ID &&
                lock.getString("revision") == MODEL_REV) { "Unapproved model revision" }
        return lock.getJSONObject("files").also {
            require(it.length() == FILES.size) { "Unexpected file count" }
            for ((name, _) in FILES) {
                require(it.has("sherpa-onnx/$name")) { "Missing pinned $name" }
            }
        }
    }

    private fun validateModel(dir: File) {
        val lock = JSONObject(File(dir, "zipformer-lock.json").readText())
        val manifest = checkedLock(lock)
        for ((name, required) in FILES) {
            val file = File(dir, name)
            require(file.isFile && file.length() == required.first) { "Missing file: $name" }
            val digest = hash(file)
            require(digest == manifest.getJSONObject("sherpa-onnx/$name").getString("sha256")) {
                "Hash mismatch: $name"
            }
            if (required.second.isNotEmpty()) require(digest == required.second)
        }
    }

    private fun prepare() {
        if (busy || !ready || loaded) return
        setBusy(true)
        worker.execute {
            try {
                validateModel(modelFolder)
                val started = SystemClock.elapsedRealtime()
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(modelFolder, "encoder.int8.onnx").absolutePath,
                            decoder = File(modelFolder, "decoder.int8.onnx").absolutePath,
                            joiner = File(modelFolder, "joiner.int8.onnx").absolutePath,
                        ),
                        tokens = File(modelFolder, "tokens.txt").absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2",
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search"
                )
                recognizer = OnlineRecognizer(assetManager = null, config = config)
                val ms = SystemClock.elapsedRealtime() - started
                runOnUiThread {
                    loaded = true
                    status.text = "מנוע Zipformer מוכן, זמן טעינה: $ms ms."
                    metrics.text = "זמן טעינת מודל: $ms ms"
                    setBusy(false)
                }
            } catch (t: Throwable) {
                show("טעינת המודל נכשלה: ${t.message}")
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun startRecording() {
        if (busy || !loaded) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
            status.text = "אשר הרשאת מיקרופון, ואז לחץ שוב על דבר."
            return
        }
        stopRequested.set(false)
        transcript.text = "מאזין…"
        setBusy(true, recording = true)
        worker.execute { recordAndDecode() }
    }

    private fun recordAndDecode() {
        val engine = recognizer
        if (engine == null) {
            show("Recognizer is not loaded")
            runOnUiThread { setBusy(false) }
            return
        }
        var recorder: AudioRecord? = null
        var stream: com.k2fsa.sherpa.onnx.OnlineStream? = null
        val started = SystemClock.elapsedRealtime()
        journalFolder.mkdirs()
        val journal = File(journalFolder, "zipformer-${System.currentTimeMillis()}.jsonl")
        latestJournal = journal
        var captureSamples = 0L
        var decodeMs = 0L
        var firstPartialMs = -1L
        var lastText = ""
        var partialUpdates = 0
        var failure: String? = null
        try {
            write(journal, JSONObject()
                .put("event", "start").put("model_revision", MODEL_REV)
                .put("runtime_sha256", RUNTIME_SHA).put("mode", "online_streaming_cpu_int8")
                .put("sample_rate", SAMPLE_RATE).put("max_audio_seconds", MAX_AUDIO_SECONDS))
            val frame = ShortArray(SAMPLE_RATE / 10)
            val minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "AudioRecord cannot capture mono 16kHz PCM" }
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, frame.size * 2 * 4))
            require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
            stream = engine.createStream()
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            show("מאזין ומתמלל תוך כדי דיבור…")
            while (!stopRequested.get() && captureSamples < SAMPLE_RATE * MAX_AUDIO_SECONDS) {
                val read = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
                    error("Microphone stream failed: $read")
                }
                if (read <= 0) continue
                val pcm = FloatArray(read) { i -> frame[i] / 32768.0f }
                stream.acceptWaveform(pcm, SAMPLE_RATE)
                captureSamples += read
                var cycles = 0
                while (engine.isReady(stream) && cycles < 40) {
                    val before = SystemClock.elapsedRealtime()
                    engine.decode(stream)
                    decodeMs += SystemClock.elapsedRealtime() - before
                    cycles++
                }
                val text = engine.getResult(stream).text.trim()
                if (text.isNotBlank() && text != lastText) {
                    if (firstPartialMs < 0L) firstPartialMs = SystemClock.elapsedRealtime() - started
                    lastText = text
                    partialUpdates++
                    val elapsed = SystemClock.elapsedRealtime() - started
                    write(journal, JSONObject().put("event", "partial")
                        .put("elapsed_ms", elapsed).put("text", text))
                    runOnUiThread { transcript.text = text }
                }
            }
            recorder.stop()
            val captureEnd = SystemClock.elapsedRealtime()
            stream.inputFinished()
            var finalCycles = 0
            // Safety ceiling for an unresponsive decoder. Never hang the UI indefinitely.
            while (engine.isReady(stream) && finalCycles < 200 &&
                SystemClock.elapsedRealtime() - captureEnd < 5_000L) {
                val before = SystemClock.elapsedRealtime()
                engine.decode(stream)
                decodeMs += SystemClock.elapsedRealtime() - before
                finalCycles++
            }
            require(!engine.isReady(stream)) { "Decoder could not finalize within 5 seconds" }
            val finalText = engine.getResult(stream).text.trim()
            val end = SystemClock.elapsedRealtime()
            val audioMs = captureSamples * 1_000L / SAMPLE_RATE
            val finalMs = end - captureEnd
            val activeMs = end - started
            val memory = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
            val thermal = (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
            val result = JSONObject()
                .put("event", "complete").put("capture_ms", audioMs)
                .put("decode_ms", decodeMs).put("wall_ms", activeMs)
                .put("finalize_after_capture_ms", finalMs)
                .put("first_partial_after_start_ms", firstPartialMs)
                .put("partial_updates", partialUpdates).put("text", finalText)
                .put("decode_rtf", if (audioMs > 0) decodeMs.toDouble() / audioMs else -1.0)
                .put("available_memory_mb", memory.availMem / 1024 / 1024)
                .put("thermal_status", thermal)
            write(journal, result)
            val human = "אודיו: $audioMs ms | פענוח CPU: $decodeMs ms | RTF: " +
                String.format(Locale.US, "%.3f", if (audioMs > 0) decodeMs.toDouble() / audioMs else -1.0) +
                "\nראשית מילים: $firstPartialMs ms מתחילת ההאזנה" +
                "\nסיום פענוח: $finalMs ms מסיום הקלטה | כולל: $activeMs ms" +
                "\nThermal: $thermal | RAM available: ${memory.availMem / 1024 / 1024} MB"
            runOnUiThread {
                transcript.text = finalText.ifBlank { "(לא זוהה דיבור)" }
                metrics.text = human
                status.text = "הניסוי הסתיים; אפשר לייצא את היומן או לבצע תור נוסף."
            }
        } catch (t: Throwable) {
            failure = t.javaClass.simpleName + ": " + (t.message ?: "")
            write(journal, JSONObject().put("event", "error")
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("message", failure))
            show("ניסוי נכשל: $failure")
        } finally {
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            try { stream?.release() } catch (_: Exception) {}
            runOnUiThread { setBusy(false) }
        }
    }

    /** Compare the same user-selected PCM WAV with varying inference-only silence tails.
     *  No speech is uploaded; the original file is read-only.
     */
    private fun replayWav(uri: Uri) {
        if (busy || !loaded) return
        setBusy(true)
        transcript.text = "משווה תמלולים של אותה הקלטה…"
        worker.execute {
            try {
                val engine = recognizer ?: error("טען תחילה את המודל")
                val raw = ByteArrayOutputStream()
                contentResolver.openInputStream(uri)?.use { input ->
                    val chunk = ByteArray(8_192)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        raw.write(chunk, 0, n)
                        require(raw.size() <= DiagnosticWav.MAX_WAV_BYTES) { "WAV file exceeds diagnostic limit" }
                    }
                } ?: error("אין גישה לקובץ האודיו שנבחר")
                val pcm = DiagnosticWav.readPcm16(raw.toByteArray())
                val sha = MessageDigest.getInstance("SHA-256").digest(pcm)
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                journalFolder.mkdirs()
                val journal = File(journalFolder, "zipformer-replay-" + System.currentTimeMillis() + ".jsonl")
                latestJournal = journal
                write(journal, JSONObject().put("event", "replay_start")
                    .put("input_sha256", sha).put("pcm_seconds", pcm.size / 32_000.0)
                    .put("decoder", "online_greedy_int8_cpu").put("mode", "fixed_pcm_replay"))

                val summary = StringBuilder("אותו WAV: " + String.format(Locale.US, "%.2f", pcm.size / 32_000.0) + " שניות\n")
                for (tailMs in listOf(0, 500, 1000, 2000)) {
                    val started = SystemClock.elapsedRealtime()
                    val stream = engine.createStream()
                    var cycles = 0
                    var finalCycles = 0
                    var resultText = ""
                    try {
                        val bytes = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                        val frame = FloatArray(1_600)
                        while (bytes.hasRemaining()) {
                            val n = minOf(frame.size, bytes.remaining() / 2)
                            for (i in 0 until n) frame[i] = bytes.short / 32768f
                            stream.acceptWaveform(if (n == frame.size) frame else frame.copyOf(n), SAMPLE_RATE)
                            while (engine.isReady(stream)) {
                                require(++cycles <= 500) { "Decoder exceeded replay budget" }
                                engine.decode(stream)
                            }
                        }
                        if (tailMs > 0) stream.acceptWaveform(FloatArray(SAMPLE_RATE * tailMs / 1000), SAMPLE_RATE)
                        stream.inputFinished()
                        while (engine.isReady(stream)) {
                            require(++finalCycles <= 500) { "Finalization exceeded replay budget" }
                            engine.decode(stream)
                        }
                        resultText = engine.getResult(stream).text.trim()
                        write(journal, JSONObject().put("event", "replay_result")
                            .put("tail_ms", tailMs).put("text", resultText)
                            .put("decode_cycles", cycles).put("finalize_cycles", finalCycles)
                            .put("elapsed_ms", SystemClock.elapsedRealtime() - started))
                    } finally {
                        stream.release()
                    }
                    summary.append("\n").append(tailMs).append("ms → ").append(resultText.ifBlank { "(ריק)" })
                }
                runOnUiThread {
                    transcript.text = summary.toString()
                    status.text = "הבדיקה המקומית הסתיימה. ניתן לייצא יומן להשוואה."
                }
            } catch (t: Throwable) {
                show("בדיקת WAV נכשלה: " + t.javaClass.simpleName + ": " + t.message)
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun exportJournal() {
        val file = latestJournal
        if (busy || file == null || !file.exists()) {
            status.text = "עדיין אין יומן שהושלם לייצוא."
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
        startActivityForResult(intent, SAVE_JOURNAL)
    }

    private fun setBusy(value: Boolean, recording: Boolean = false) {
        busy = value
        importButton.isEnabled = !value && !loaded
        prepareButton.isEnabled = !value && ready && !loaded
        startButton.isEnabled = !value && loaded
        replayButton.isEnabled = !value && loaded
        stopButton.isEnabled = value && recording
        exportButton.isEnabled = !value && latestJournal?.exists() == true
    }

    private fun updateButtons() = setBusy(busy)
    private fun show(message: String) = runOnUiThread { status.text = message }

    private fun write(file: File, event: JSONObject) {
        try {
            FileWriter(file, true).use { it.write(event.toString() + "\n") }
        } catch (_: Exception) {
            // Device journals are optional diagnostics; inference must still release resources.
        }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        worker.execute {
            recognizer?.release()
            recognizer = null
        }
        worker.shutdown()
        super.onDestroy()
    }
}

private fun hash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
