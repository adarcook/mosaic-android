package life.mosaic.zipformergate

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import com.k2fsa.sherpa.onnx.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Software wake experiment, explicitly started by the owner from Mosaic's foreground UI.
 * Raw waiting audio and recognized text exist in memory only and are never logged/saved.
 * This service has its own process, notification, mic indicator and stop action.
 */
class WakeListenService : Service() {
    private val binder = Binder()
    @Volatile private var running = false
    @Volatile private var mic: AudioRecord? = null
    private var workerStarted = false
    @Volatile private var ownerStopped = false
    private var reply: PendingIntent? = null
    private var headset: life.mosaic.audio.HeadsetAudio? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean {
        // The owner/assistant disappearing must not leave an orphaned live microphone.
        running = false
        try { mic?.stop() } catch (_: Exception) {}
        if (!workerStarted) stopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            ownerStopped = true
            running = false
            try { mic?.stop() } catch (_: Exception) {}
            if (!workerStarted) stopSelf()
            return START_NOT_STICKY
        }
        if (workerStarted) return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val callback = intent?.getParcelableExtra<PendingIntent>("callback")
        if (callback == null || callback.creatorPackage != "life.mosaic.tensorllm") {
            stopSelf(); return START_NOT_STICKY
        }
        try { headset=life.mosaic.audio.HeadsetAudio.read(requireNotNull(intent).extras ?: Bundle()) } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        reply = callback
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("mosaic_wake", "האזנה למוזאיק", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, WakeListenService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "mosaic_wake")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("מוזאיק · האזנה מקומית")
                .setContentText("מצב ניסוי: אמור מוזאיק, המתן לצליל ואז דבר. אפשר לעצור כאן.")
                .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, "הפסקת האזנה", stop).build()).build()
            startForeground(54, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mosaic:software-wake").apply { acquire() }
            running = true; workerStarted = true
            Thread({ listen() }, "mosaic-local-wake").start()
        } catch (_: Exception) {
            report("error"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun listen() {
        var recognizer: KeywordSpotter? = null
        var stream: OnlineStream? = null
        var result = "stopped"
        var detectedAt = -1L
        try {
            val dir = File(filesDir, "models/zipformer-he")
            verify(dir)
            if (!running) return
            val keywords = File(cacheDir,"mosaic-wake-keywords-v1.txt")
            keywords.writeText(WakeKeywords.build(File(dir,"tokens.txt").readText()))
            recognizer = KeywordSpotter(assetManager = null, config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0f),
                modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").absolutePath,
                    decoder = File(dir, "decoder.int8.onnx").absolutePath,
                    joiner = File(dir, "joiner.int8.onnx").absolutePath),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    numThreads = 2, provider = "cpu", modelType = "zipformer2"),
                maxActivePaths = 4, keywordsFile = keywords.absolutePath,
                keywordsScore = 1.5f, keywordsThreshold = 0.01f, numTrailingBlanks = 2))
            stream = recognizer.createStream()
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0)
            val recorder = AudioRecord(if(headset!=null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 4, 12800))
            mic = recorder
            require(recorder.state == AudioRecord.STATE_INITIALIZED)
            if (!running) return
            headset?.prefer(getSystemService(AudioManager::class.java),recorder)
            recorder.startRecording()
            require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            headset?.awaitInput(getSystemService(AudioManager::class.java),recorder)
            report("listening")
            val samples = ShortArray(1600)
            var streamSamples = 0
            var healthAt = SystemClock.elapsedRealtime()
            var healthSamples = 0L
            var nonzeroBlocks = 0L
            var decodeMs = 0L
            var maxRms = 0L
            while (running) {
                val count = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (!running) break
                require(count > 0)
                headset?.requireInput(getSystemService(AudioManager::class.java),recorder)
                require(recorder.activeRecordingConfiguration?.isClientSilenced != true)
                val currentStream = requireNotNull(stream)
                currentStream.acceptWaveform(FloatArray(count) { samples[it] / 32768f }, 16000)
                streamSamples += count
                healthSamples += count
                if ((0 until count).any { samples[it].toInt() != 0 }) nonzeroBlocks++
                maxRms=maxOf(maxRms,kotlin.math.sqrt((0 until count).sumOf { samples[it].toDouble()*samples[it] }/count).toLong())
                val decodeStart = SystemClock.elapsedRealtime()
                var cycles = 0
                while (recognizer.isReady(currentStream)) {
                    require(++cycles < 100)
                    recognizer.decode(currentStream)
                }
                decodeMs += SystemClock.elapsedRealtime() - decodeStart
                val now = SystemClock.elapsedRealtime()
                if (now - healthAt >= 5000) {
                    try { reply?.send(this, 0, Intent().putExtra("state", "health")
                        .putExtra("wall_ms", now-healthAt).putExtra("audio_ms", healthSamples*1000/16000)
                        .putExtra("decode_ms", decodeMs).putExtra("nonzero_blocks", nonzeroBlocks)
                        .putExtra("max_rms", maxRms)
                        .putExtra("interactive", (getSystemService(POWER_SERVICE) as PowerManager).isInteractive)) }
                    catch (_: Exception) { running=false }
                    healthAt=now;healthSamples=0;nonzeroBlocks=0;decodeMs=0;maxRms=0
                }
                if (WakePhrase.matches(recognizer.getResult(currentStream).keyword)) { detectedAt=SystemClock.elapsedRealtime();result = "wake"; report("detected",detectedAt); break }
                if (streamSamples >= 16000 * 30) {
                    currentStream.release(); stream = recognizer.createStream(); streamSamples = 0
                }
            }
        } catch (_: Throwable) { if (running) result = "error" }
        finally {
            running = false
            try { mic?.stop() } catch (_: Exception) {}
            mic?.release(); mic = null
            try { stream?.release() } catch (_: Exception) {}
            try { recognizer?.release() } catch (_: Exception) {}
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Reply after mic/native cleanup. Controller waits for Binder death before heavy workers.
            report(result, detectedAt)
            stopSelf()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun report(state: String, detectedAt: Long = -1L) {
        try { reply?.send(this, 0, Intent().putExtra("state", state).putExtra("detected_elapsed_ms",detectedAt).putExtra("owner_stop",ownerStopped)) } catch (_: Exception) { running = false }
    }

    private fun verify(dir: File) {
        val pinned = linkedMapOf(
            "encoder.int8.onnx" to Pair(70478964L, "6c6febcb74a09435315a1d872a6539f6e7fc2c333533f14c74edc0e85c49ffcd"),
            "decoder.int8.onnx" to Pair(1049105L, "feb03e7c8aa761593a9284a6fa1e91259be3702de75f630062256c1a59c5a906"),
            "joiner.int8.onnx" to Pair(771805L, "1d2c6d8e6dabc5eb52de52e95718e9bdfbffbfe7ead3808fb5ffd74e83bd9cae"),
            "tokens.txt" to Pair(17086L, ""))
        val lock = JSONObject(File(dir, "zipformer-lock.json").readText())
        require(lock.getInt("format") == 1 && lock.getString("repo_id") == "ivrit-ai/zipformer2-66m-streaming-he" &&
            lock.getString("revision") == "ee769a2a13b887f91572eec349458bfb0fe79e7a")
        val files = lock.getJSONObject("files")
        require(files.length() == pinned.size)
        for ((name, identity) in pinned) {
            val file = File(dir, name)
            require(file.isFile && file.length() == identity.first)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(1 shl 20); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val item = files.getJSONObject("sherpa-onnx/$name")
            require(item.getLong("bytes") == identity.first && item.getString("sha256") == sha && (identity.second.isEmpty() || sha == identity.second))
        }
    }

    override fun onDestroy() {
        running = false
        try { mic?.stop() } catch (_: Exception) {}
        if (!workerStarted) { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        super.onDestroy()
    }
}
