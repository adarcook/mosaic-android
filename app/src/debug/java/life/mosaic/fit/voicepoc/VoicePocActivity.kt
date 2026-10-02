package life.mosaic.fit.voicepoc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/** Disposable debug probe. No domain writes, transcript logs, network fallback or background mic. */
class VoicePocActivity : Activity(), RecognitionListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var diagnostics: TextView
    private lateinit var retry: Button
    private lateinit var testVoice: Button
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var foreground = false
    private var ready = false
    private var busy = false
    private var generation = 0
    private var started = 0L
    private var ended = 0L
    private var speakRequested = 0L
    private var voiceInfo = ""
    private val timeout = Runnable { fail("תם זמן הניסוי. אפשר לנסות שוב.") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = TextView(this).apply { textSize = 18f; text = "בודק קול עברי מקומי…" }
        retry = Button(this).apply {
            text = "ניסיון נוסף"; isEnabled = false
            setOnClickListener { requestConversation() }
        }
        testVoice = Button(this).apply {
            text = "בדיקת קול בלבד"; isEnabled = false
            setOnClickListener {
                generation++; busy = true
                retry.isEnabled = false; isEnabled = false
                diagnostics.text = "$voiceInfo\nבדיקת קול עברי ללא תמלול"
                speakReply()
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
            addView(diagnostics); addView(retry); addView(testVoice)
        }
        setContentView(layout)
        tts = TextToSpeech(this) { status ->
            // Post because the constructor callback can precede assignment of tts.
            handler.post { initializeVoice(status) }
        }
    }

    private fun initializeVoice(status: Int) {
        if (isDestroyed) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            fail("מנוע ההקראה לא זמין."); return
        }
        val voice = engine.voices.orEmpty().filter {
            it.locale.language in setOf("he", "iw") && !it.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.sortedBy { it.name }.firstOrNull()
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            fail("אין קול עברי מקומי מותקן. התקן נתוני קול עברי בהגדרות ההקראה ופתח מחדש."); return
        }
        voiceInfo = "TTS: ${voice.name}, network=false"
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = updateUtterance(utteranceId) {
                diagnostics.append("\nTTS start: ${SystemClock.elapsedRealtime() - speakRequested} ms")
            }
            override fun onDone(utteranceId: String?) = updateUtterance(utteranceId) {
                finishTurn(); diagnostics.append("\nהניסוי הסתיים. איכות התמלול והקול דורשת בדיקה שלך.")
            }
            @Deprecated("Android callback")
            override fun onError(utteranceId: String?) = updateUtterance(utteranceId) {
                fail("ההקראה נכשלה. $voiceInfo")
            }
        })
        ready = true
        retry.isEnabled = foreground
        testVoice.isEnabled = foreground
        if (foreground) requestConversation()
    }

    private fun updateUtterance(id: String?, block: () -> Unit) {
        handler.post { if (foreground && busy && id == generation.toString()) block() }
    }

    private fun requestConversation() {
        if (!ready || !foreground || busy) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        beginTurn()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 1) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestConversation()
        else fail("נדרשת הרשאת מיקרופון לניסוי. לחץ לניסיון נוסף לאחר אישור ההרשאה.")
    }

    private fun beginTurn() {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            fail("אין שירות תמלול מקומי מתאים. הניסוי דורש Android 13 ומעלה."); return
        }
        generation++
        val turn = generation
        busy = true; retry.isEnabled = false; testVoice.isEnabled = false
        started = 0L; ended = 0L
        diagnostics.text = "$voiceInfo\nבודק תמיכה מקומית בעברית…"
        handler.postDelayed(timeout, 30_000)
        try {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            recognizer!!.setRecognitionListener(object : RecognitionListener {
                private fun current() = foreground && busy && generation == turn
                override fun onReadyForSpeech(params: Bundle?) {
                    if (current()) this@VoicePocActivity.onReadyForSpeech(params)
                }
                override fun onEndOfSpeech() { if (current()) this@VoicePocActivity.onEndOfSpeech() }
                override fun onResults(results: Bundle?) {
                    if (current()) this@VoicePocActivity.onResults(results)
                }
                override fun onError(error: Int) { if (current()) this@VoicePocActivity.onError(error) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer!!.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(support: RecognitionSupport) {
                    if (!foreground || !busy || generation != turn) return
                    val installed = support.installedOnDeviceLanguages
                    val hebrewInstalled = installed.any {
                        Locale.forLanguageTag(it.replace('_', '-')).language in setOf("he", "iw")
                    }
                    if (!hebrewInstalled) {
                        fail("עברית אינה מותקנת לתמלול מקומי. installed=$installed\n$voiceInfo")
                        return
                    }
                    diagnostics.append("\nSTT: on-device; installed=$installed")
                    try { recognizer?.startListening(intent) }
                    catch (e: RuntimeException) { fail("לא ניתן להתחיל תמלול: ${e.javaClass.simpleName}") }
                }
                override fun onError(error: Int) {
                    if (foreground && busy && generation == turn) fail("בדיקת תמיכת STT נכשלה: $error")
                }
            })
        } catch (e: RuntimeException) {
            fail("שירות התמלול נכשל: ${e.javaClass.simpleName}")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (!busy || !foreground) return
        started = SystemClock.elapsedRealtime()
        diagnostics.append("\nדבר עכשיו בעברית.")
    }
    override fun onEndOfSpeech() { ended = SystemClock.elapsedRealtime() }
    override fun onResults(results: Bundle?) {
        if (!busy || !foreground) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (text.isNullOrBlank()) { fail("לא התקבל תמלול."); return }
        val now = SystemClock.elapsedRealtime()
        diagnostics.append("\nתמלול: $text\nSTT ready→result: ${now - started} ms")
        if (ended > 0) diagnostics.append("\nSTT end→result: ${now - ended} ms")
        recognizer?.destroy(); recognizer = null
        speakReply()
    }

    private fun speakReply() {
        speakRequested = SystemClock.elapsedRealtime()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 30_000)
        val reply = "שמעתי אותך. זו תשובת הבדיקה של מוזאיק בעברית. נשארו לך ארבעים ושניים גרם חלבון. זה נתון לדוגמה בלבד."
        if (tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, generation.toString()) != TextToSpeech.SUCCESS) {
            fail("מנוע ההקראה דחה את הבקשה.")
        }
    }
    override fun onError(error: Int) {
        if (busy && foreground) fail("תמלול נכשל: $error. שגיאות שפה 12/13 מצביעות על תמיכה או מודל חסרים.")
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun finishTurn() {
        busy = false
        handler.removeCallbacks(timeout)
        recognizer?.destroy(); recognizer = null
        retry.isEnabled = ready && foreground
        testVoice.isEnabled = ready && foreground
    }
    private fun fail(message: String) {
        finishTurn(); tts?.stop()
        diagnostics.text = message
    }
    override fun onResume() {
        super.onResume(); foreground = true
        retry.isEnabled = ready && !busy
        testVoice.isEnabled = ready && !busy
    }
    override fun onPause() {
        foreground = false; generation++
        finishTurn(); tts?.stop()
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy(); tts?.shutdown(); tts = null
        super.onDestroy()
    }
}
