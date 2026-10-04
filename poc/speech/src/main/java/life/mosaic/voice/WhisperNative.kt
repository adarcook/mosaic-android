package life.mosaic.voice

object WhisperNative {
    init { System.loadLibrary("mosaic_whisper") }
    fun ensureLoaded() = Unit
    external fun create(modelPath: String, useGpu: Boolean): Long
    external fun gpuBuild(): Boolean
    external fun prepare(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray, accurate: Boolean, budgetSeconds: Int, audioContext: Int): String
    external fun phase(handle: Long): Int
    external fun timings(handle: Long): String
    external fun cancel(handle: Long)
    external fun release(handle: Long)
}
