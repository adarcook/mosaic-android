package life.mosaic.zipformergate

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DiagnosticWavTest {
    private fun wav(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
        fun le(value: Int, size: Int) { repeat(size) { out.write(value ushr (8 * it) and 255) } }
        ascii("RIFF")
        le(36 + pcm.size, 4)
        ascii("WAVE")
        ascii("fmt ")
        le(16, 4)
        le(1, 2)
        le(channels, 2)
        le(sampleRate, 4)
        le(sampleRate * channels * 2, 4)
        le(channels * 2, 2)
        le(16, 2)
        ascii("data")
        le(pcm.size, 4)
        out.write(pcm)
        return out.toByteArray()
    }

    @Test fun extractsOriginalPcmWithoutModification() {
        val pcm = ByteArray(32_000) { i -> (i % 251).toByte() }
        assertArrayEquals(pcm, DiagnosticWav.readPcm16(wav(pcm)))
    }

    @Test fun rejectsOtherSampleRatesRatherThanResamplingSilently() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticWav.readPcm16(wav(ByteArray(32_000), 48_000))
        }
    }

    @Test fun rejectsTruncatedInputWithoutReadingBeyondFile() {
        val full = wav(ByteArray(32_000))
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticWav.readPcm16(full.copyOf(full.size - 1))
        }
    }

    @Test fun rejectsOversizedData() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticWav.readPcm16(wav(ByteArray(400_000)))
        }
    }
}
