package life.mosaic.zipformergate

/**
 * sherpa-onnx online transducers need bounded silence after the final real PCM frame
 * before inputFinished(), otherwise the final speech chunk may not become ready.
 * The samples are inference-only; original recorded PCM is never changed.
 */
internal object ZipformerTailPadding {
    const val TAIL_PADDING_MS = 500

    fun finish(
        sampleRate: Int,
        acceptWaveform: (FloatArray, Int) -> Unit,
        inputFinished: () -> Unit,
    ): Int {
        require(sampleRate == 16_000) { "This voice gate requires 16 kHz PCM" }
        val count = sampleRate * TAIL_PADDING_MS / 1000
        acceptWaveform(FloatArray(count), sampleRate)
        inputFinished()
        return count
    }
}
