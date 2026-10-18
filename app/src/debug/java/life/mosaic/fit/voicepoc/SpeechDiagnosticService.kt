package life.mosaic.fit.voicepoc

import android.app.Service
import android.content.Intent
import android.os.*
import life.mosaic.voice.WhisperNative
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.Executors

/** Same UID, private disposable CPU process. Never runs TTS or uploads audio. */
class SpeechDiagnosticService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var handle = 0L
    private val nativeLock = Any()
    private var running = false
    private var reply: Messenger? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (running) {
                send(2, Bundle().apply { putInt("phase", synchronized(nativeLock) { if (handle == 0L) 0 else WhisperNative.phase(handle) }) })
                main.postDelayed(this, 1000)
            }
        }
    }
    private fun send(code: Int, data: Bundle) {
        try { reply?.send(Message.obtain(null, code).apply { this.data = data }) }
        catch (_: RemoteException) { Process.killProcess(Process.myPid()) }
    }
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == 9) { Process.killProcess(Process.myPid()); true }
        else if (message.what == 1 && !running) {
            running = true; reply = message.replyTo
            val data = Bundle(message.data)
            send(1, Bundle().apply { putInt("pid", Process.myPid()) })
            main.post(ticker)
            worker.execute {
                val result = Bundle()
                try {
                    val models = File(requireNotNull(getExternalFilesDir(null)), "voice-models")
                    val start = SystemClock.elapsedRealtime()
                    handle = WhisperNative.create(File(models, "whisper/ggml-model.bin").path, false)
                    check(handle != 0L) { "Model load failed" }
                    result.putLong("loadMs", SystemClock.elapsedRealtime() - start)
                    if (!data.getBoolean("loadOnly")) {
                        val pcmFile = File(cacheDir, "speech-input.pcm")
                        val count = (pcmFile.length() / 4).toInt()
                        require(pcmFile.length() == count.toLong() * 4 && count in 8000..128000)
                        val pcm = FloatArray(count)
                        DataInputStream(pcmFile.inputStream().buffered()).use { input ->
                            for (i in pcm.indices) pcm[i] = input.readFloat()
                        }
                        pcmFile.delete()
                        WhisperNative.prepare(handle)
                        val begin = SystemClock.elapsedRealtime()
                        result.putString("text", WhisperNative.transcribe(handle, pcm,
                            data.getBoolean("accurate"), 90, if (data.getBoolean("shortWindow")) 512 else 0).trim())
                        result.putLong("inferenceMs", SystemClock.elapsedRealtime() - begin)
                        result.putString("timings", WhisperNative.timings(handle))
                    }
                } catch (e: Throwable) {
                    result.putString("error", e.javaClass.simpleName + ": " + (e.message ?: "Speech failed"))
                } finally {
                    synchronized(nativeLock) { if (handle != 0L) { WhisperNative.release(handle); handle = 0L } }
                    main.post {
                        running = false; main.removeCallbacks(ticker)
                        send(3, result)
                        // Disposal also reclaims all native allocations after every attempt.
                        main.postDelayed({ Process.killProcess(Process.myPid()) }, 200)
                    }
                }
            }
            true
        } else true
    })
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
}
