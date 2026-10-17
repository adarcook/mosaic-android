package life.mosaic.zipformergate

/**
 * sherpa-onnx online transducers need bounded silence after the final real PCM frame
 * before inputFinished(), otherwise the final speech chunk may not become ready.
 * The samples are inference-only; original recorded PCM is never changed.
 */
internal object ZipformerTailPadding {
    // Pinned encoder: T=141 feature frames, decode_chunk_len=128 (10ms/frame).
    // isReady requires processed + T < ready. A generic 500ms tail can leave
    // the final real speech chunk undecoded; 2s covers the chunk plus lookahead.
    // This is synthetic model input, not an additional microphone wait.
    const val TAIL_PADDING_MS = 2_000

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
