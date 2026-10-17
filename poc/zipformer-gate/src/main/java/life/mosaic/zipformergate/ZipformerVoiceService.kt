package life.mosaic.zipformergate

import android.app.Service
import android.content.Intent
import android.os.*
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Opt-in alternative ASR companion for Mosaic voice turns.
 * Accepts the existing signed Messenger protocol; decodes a captured utterance
 * after the mic closes. NOT live microphone streaming in the controller yet.
 * Runs in a killable separate process so Gemma/BlueTTS never share native heaps.
 */
class ZipformerVoiceService : Service() {
    companion object {
        private const val REVISION = "ee769a2a13b887f91572eec349458bfb0fe79e7a"
        private const val REPO = "ivrit-ai/zipformer2-66m-streaming-he"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_PCM_BYTES = 384_000 // 12 s, 16 kHz mono PCM16
        private val files = linkedMapOf(
            "encoder.int8.onnx" to Pair(70_478_964L, "6c6febcb74a09435315a1d872a6539f6e7fc2c333533f14c74edc0e85c49ffcd"),
            "decoder.int8.onnx" to Pair(1_049_105L, "feb03e7c8aa761593a9284a6fa1e91259be3702de75f630062256c1a59c5a906"),
            "joiner.int8.onnx" to Pair(771_805L, "1d2c6d8e6dabc5eb52de52e95718e9bdfbffbfe7ead3808fb5ffd74e83bd9cae"),
            "tokens.txt" to Pair(17_086L, "")
        )
    }

    @Volatile private var busy = false
    private var engine: OnlineRecognizer? = null
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            9 -> {
                android.os.Process.killProcess(android.os.Process.myPid())
                true
            }
            1 -> {
                if (!busy && msg.replyTo != null) {
                    busy = true
                    val request = msg.data
                    val reply = msg.replyTo
                    val prepare = request.getBoolean("prepare", false)
                    val pcm = request.getByteArray("pcm16")
                    Thread({ execute(reply, prepare, pcm) }, "mosaic-zipformer-asr").start()
                }
                true
            }
            else -> true
        }
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun execute(reply: Messenger, prepare: Boolean, pcm: ByteArray?) {
        try {
            val reused = engine != null
            var verifyMs = 0L
            var loadMs = 0L
            if (!reused) {
                send(reply, 2, "מאמת מודל Zipformer מקומי…")
                val verifyStart = SystemClock.elapsedRealtime()
                val dir = File(filesDir, "models/zipformer-he")
                verifyFiles(dir)
                verifyMs = SystemClock.elapsedRealtime() - verifyStart

                val loadStart = SystemClock.elapsedRealtime()
                send(reply, 2, "טוען Zipformer ל-CPU…")
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            joiner = File(dir, "joiner.int8.onnx").absolutePath,
                        ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        numThreads = 2,
                        provider = "cpu",
                        modelType = "zipformer2"
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search"
                )
                engine = OnlineRecognizer(assetManager = null, config = config)
                loadMs = SystemClock.elapsedRealtime() - loadStart
            }

            if (prepare) {
                val out = Bundle().apply {
                    putBoolean("prepared", true)
                    putBoolean("engine_reused", reused)
                    putString("asr_engine", "zipformer")
                    putString("model_revision", REVISION)
                    putLong("verify_ms", verifyMs)
                    putLong("load_ms", loadMs)
                }
                send(reply, 4, "מנוע Zipformer מוכן", out)
                return
            }
            require(pcm != null && pcm.size in 16_000..MAX_PCM_BYTES && pcm.size % 2 == 0) {
                "נדרשת הקלטה של חצי שנייה עד 12 שניות"
            }
            val recognizer = engine ?: error("Zipformer recognizer not loaded")
            val started = SystemClock.elapsedRealtime()
            val bytes = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val stream = recognizer.createStream()
            var decodeMs = 0L
            var partials = 0
            try {
                send(reply, 2, "מפענח עברית באמצעות Zipformer…")
                val frame = FloatArray(1_600) // 100ms at 16kHz
                while (bytes.hasRemaining()) {
                    val n = minOf(frame.size, bytes.remaining() / 2)
                    for (i in 0 until n) frame[i] = bytes.short / 32768f
                    stream.acceptWaveform(if (n == frame.size) frame else frame.copyOf(n), SAMPLE_RATE)
                    var cycles = 0
                    while (recognizer.isReady(stream)) {
                        require(++cycles <= 500) { "Zipformer decoder unexpectedly busy" }
                        val begin = SystemClock.elapsedRealtime()
                        recognizer.decode(stream)
                        decodeMs += SystemClock.elapsedRealtime() - begin
                    }
                    partials++
                }
                stream.inputFinished()
                var finalCycles = 0
                while (recognizer.isReady(stream)) {
                    require(++finalCycles <= 500) { "Zipformer finalization did not converge" }
                    val begin = SystemClock.elapsedRealtime()
                    recognizer.decode(stream)
                    decodeMs += SystemClock.elapsedRealtime() - begin
                }
                val result = recognizer.getResult(stream).text.trim()
                require(result.isNotBlank()) { "לא התקבל תמלול מ-Zipformer" }
                val memory = Debug.MemoryInfo()
                Debug.getMemoryInfo(memory)
                val thermal = (getSystemService(POWER_SERVICE) as PowerManager).currentThermalStatus
                val output = Bundle().apply {
                    putString("asr_engine", "zipformer")
                    putString("backend", "zipformer2-66m-int8; cpu; buffered_after_capture")
                    putString("model_revision", REVISION)
                    putString("transcript", result)
                    putBoolean("engine_reused", reused)
                    putLong("verify_ms", verifyMs)
                    putLong("load_ms", loadMs)
                    putLong("compute_ms", SystemClock.elapsedRealtime() - started)
                    putLong("decode_ms", decodeMs)
                    putInt("input_frames", partials)
                    putInt("worker_pss_kb", memory.totalPss)
                    putInt("thermal_status", thermal)
                }
                send(reply, 4, "התמלול הושלם", output)
            } finally {
                stream.release()
            }
        } catch (t: Throwable) {
            send(reply, 5, "Zipformer: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            busy = false
        }
    }

    private fun verifyFiles(dir: File) {
        val lock = JSONObject(File(dir, "zipformer-lock.json").readText())
        require(lock.getInt("format") == 1 &&
                lock.getString("repo_id") == REPO &&
                lock.getString("revision") == REVISION) { "Zipformer model revision does not match" }
        val manifest = lock.getJSONObject("files")
        require(manifest.length() == files.size) { "Unexpected model file count" }
        for ((name, requirement) in files) {
            val file = File(dir, name)
            require(file.isFile && file.length() == requirement.first) {
                "Zipformer private model missing or incorrect size: $name"
            }
            val checksum = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    checksum.update(buf, 0, n)
                }
            }
            val sha = checksum.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val pinned = manifest.getJSONObject("sherpa-onnx/$name")
            require(pinned.getLong("bytes") == requirement.first &&
                pinned.getString("sha256") == sha &&
                (requirement.second.isEmpty() || sha == requirement.second)) {
                "Zipformer model hash mismatch: $name"
            }
        }
    }

    private fun send(reply: Messenger, what: Int, text: String, details: Bundle = Bundle()) {
        details.putString("text", text)
        val message = Message.obtain(null, what).apply { data = details }
        try {
            reply.send(message)
        } catch (_: RemoteException) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
