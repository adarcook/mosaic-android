package life.mosaic.voice

object WhisperNative {
    init { System.loadLibrary("mosaic_whisper") }
    external fun transcribe(modelPath: String, samples: FloatArray): String
    external fun cancel()
}
