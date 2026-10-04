package life.mosaic.fit.voicepoc

import android.content.*
import android.os.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** First terminal outcome wins: completion cannot overwrite cancellation/timeout. */
internal class SpeechOutcome<T> {
    private val value = AtomicReference<T?>(null)
    fun finish(result: T): Boolean = value.compareAndSet(null, result)
    fun get(): T? = value.get()
}

internal class SpeechCall(private val context: Context, private val progress: (Int) -> Unit) {
    private val outcome = SpeechOutcome<Bundle>()
    private val done = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private var remote: Messenger? = null
    private var bound = false
    private var pid = 0
    private var connection: ServiceConnection? = null
    private fun finish(result: Bundle) {
        if (outcome.finish(result)) done.countDown()
    }
    private fun error(message: String) = Bundle().apply { putString("error", message) }
    private fun dispose() {
        if (pid != 0) { android.os.Process.killProcess(pid); pid = 0 }
        else try { remote?.send(Message.obtain(null, 9)) } catch (_: RemoteException) {}
        remote = null
        if (bound) { connection?.let { context.unbindService(it) }; bound = false }
    }
    fun cancel(reason: String = "Cancelled") {
        finish(error(reason))
        main.post { dispose() }
    }
    fun run(loadOnly: Boolean, accurate: Boolean, shortWindow: Boolean): Bundle {
        main.post {
            if (outcome.get() != null) return@post
            val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
                when (message.what) {
                    1 -> { pid = message.data.getInt("pid"); if (outcome.get() != null) dispose() }
                    2 -> if (outcome.get() == null) progress(message.data.getInt("phase"))
                    3 -> finish(Bundle(message.data))
                }
                true
            })
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    remote = Messenger(binder)
                    if (outcome.get() != null) { dispose(); return }
                    try {
                        remote!!.send(Message.obtain(null, 1).apply {
                            replyTo = callback
                            data = Bundle().apply {
                                putBoolean("loadOnly", loadOnly); putBoolean("accurate", accurate)
                                putBoolean("shortWindow", shortWindow)
                            }
                        })
                    } catch (_: RemoteException) { finish(error("Speech process disconnected")) }
                }
                override fun onServiceDisconnected(name: ComponentName) { finish(error("Speech process disconnected")) }
                override fun onNullBinding(name: ComponentName) { finish(error("Speech binding failed")) }
                override fun onBindingDied(name: ComponentName) { finish(error("Speech binding died")) }
            }
            connection = conn
            try {
                bound = context.bindService(Intent(context, SpeechDiagnosticService::class.java), conn, Context.BIND_AUTO_CREATE)
                if (!bound) finish(error("Cannot start speech process"))
            } catch (e: Exception) { finish(error("Cannot bind speech process: ${e.javaClass.simpleName}")) }
        }
        try {
            if (!done.await(90, TimeUnit.SECONDS)) cancel("Timeout after 90 seconds")
            return requireNotNull(outcome.get())
        } finally { main.post { dispose() } }
    }
}
