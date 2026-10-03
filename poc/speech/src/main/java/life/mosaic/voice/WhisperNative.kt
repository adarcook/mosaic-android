package life.mosaic.voice

object WhisperNative {
    init { System.loadLibrary("mosaic_whisper") }
    fun ensureLoaded() = Unit
    external fun create(modelPath: String): Long
    external fun prepare(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray): String
    external fun cancel(handle: Long)
    external fun release(handle: Long)
}
