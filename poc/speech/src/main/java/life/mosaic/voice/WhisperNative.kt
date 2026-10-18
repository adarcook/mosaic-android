package life.mosaic.voice

object WhisperNative {
    init { System.loadLibrary("mosaic_whisper") }
    fun ensureLoaded() = Unit
    external fun create(modelPath: String, useGpu: Boolean): Long
    external fun createHybrid(modelPath: String): Long
    external fun gpuBuild(): Boolean
    external fun prepare(handle: Long)
    external fun transcribe(handle: Long, samples: FloatArray, accurate: Boolean, budgetSeconds: Int, audioContext: Int): String
    external fun prepareEncoderInput(handle: Long, samples: FloatArray): FloatArray
    external fun transcribeEncoded(handle: Long, encoded: FloatArray, accurate: Boolean, budgetSeconds: Int): String
    external fun transcribeEncodedCross(handle: Long, encoded: FloatArray, cross: FloatArray, accurate: Boolean, budgetSeconds: Int): String
    external fun transcribeEncodedCrossBeam(handle: Long, encoded: FloatArray, cross: FloatArray, beamSize: Int, budgetSeconds: Int): String
    external fun compareCross(handle: Long, encoded: FloatArray, cross: FloatArray): String
    external fun phase(handle: Long): Int
    external fun timings(handle: Long): String
    external fun cancel(handle: Long)
    external fun release(handle: Long)
}
