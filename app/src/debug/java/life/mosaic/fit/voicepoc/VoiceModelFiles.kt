package life.mosaic.fit.voicepoc

import java.io.File

/** Lightweight setup check before permissions, recording or native initialization. */
internal object VoiceModelFiles {
    val required = listOf("manifest.json", "reply.json", "whisper/ggml-model.bin",
        "blue/duration_predictor_style.onnx", "blue/text_encoder.onnx",
        "blue/vector_estimator.onnx", "blue/vocoder.onnx")

    fun missing(root: File): List<String> = required.filter {
        val file = File(root, it)
        !file.isFile || file.length() == 0L
    }
}
