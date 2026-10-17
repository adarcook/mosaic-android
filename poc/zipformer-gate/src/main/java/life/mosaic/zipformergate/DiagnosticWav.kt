package life.mosaic.zipformergate

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Local-only WAV reader for deterministic Zipformer replay.
 * Accepts exactly mono, 16 kHz, 16-bit little-endian PCM; never resamples audio.
 */
internal object DiagnosticWav {
    const val MAX_WAV_BYTES = 450_000
    const val MAX_PCM_BYTES = 384_000

    fun readPcm16(wav: ByteArray): ByteArray {
        require(wav.size in 44..MAX_WAV_BYTES) { "WAV size out of range" }
        require(tag(wav, 0) == "RIFF" && tag(wav, 8) == "WAVE") { "Not a RIFF/WAVE file" }
        val declaredRiffBytes = uint32(wav, 4)
        require(declaredRiffBytes >= 36 && declaredRiffBytes <= wav.size.toLong() - 8) {
            "Invalid RIFF size"
        }
        val limit = declaredRiffBytes.toInt() + 8
        var offset = 12
        var formatSeen = false
        var pcm: ByteArray? = null
        while (offset + 8 <= limit) {
            val name = tag(wav, offset)
            val length = uint32(wav, offset + 4)
            val payload = offset + 8
            require(length <= (limit - payload).toLong()) { "Truncated WAV chunk: $name" }
            val n = length.toInt()
            when (name) {
                "fmt " -> {
                    require(n >= 16) { "Incomplete WAV fmt chunk" }
                    val fmt = ByteBuffer.wrap(wav, payload, n).order(ByteOrder.LITTLE_ENDIAN)
                    val codec = fmt.short.toInt() and 0xffff
                    val channels = fmt.short.toInt() and 0xffff
                    val rate = fmt.int
                    val bytesPerSecond = fmt.int
                    val blockAlign = fmt.short.toInt() and 0xffff
                    val bits = fmt.short.toInt() and 0xffff
                    require(codec == 1 && channels == 1 && rate == 16_000 &&
                        bytesPerSecond == 32_000 && blockAlign == 2 && bits == 16) {
                        "Expected PCM16 mono WAV at 16 kHz"
                    }
                    formatSeen = true
                }
                "data" -> {
                    require(pcm == null) { "Multiple WAV data chunks unsupported" }
                    require(n in 16_000..MAX_PCM_BYTES && n % 2 == 0) {
                        "WAV duration must be 0.5 to 12 s"
                    }
                    pcm = wav.copyOfRange(payload, payload + n)
                }
            }
            offset = payload + n + (n and 1)
        }
        require(formatSeen && pcm != null) { "Missing WAV fmt or data chunk" }
        return pcm
    }

    private fun tag(bytes: ByteArray, offset: Int): String =
        String(bytes, offset, 4, StandardCharsets.US_ASCII)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL
}
