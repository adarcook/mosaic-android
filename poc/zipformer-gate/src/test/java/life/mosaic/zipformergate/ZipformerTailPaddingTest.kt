package life.mosaic.zipformergate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipformerTailPaddingTest {
    @Test fun finalizationPadsExactlyHalfSecondOfSilenceBeforeFinish() {
        val events = mutableListOf<String>()
        var audio: FloatArray? = null
        val count = ZipformerTailPadding.finish(16_000, { samples, rate ->
            events.add("accept")
            assertEquals(16_000, rate)
            audio = samples
        }, {
            events.add("finished")
        })

        assertEquals(8_000, count)
        assertEquals(listOf("accept", "finished"), events)
        assertEquals(8_000, audio!!.size)
        assertTrue(audio!!.all { it == 0f })
    }

    @Test fun invalidCaptureRateFailsWithoutCallingNativeStream() {
        var called = false
        try {
            ZipformerTailPadding.finish(48_000, { _, _ -> called = true }, { called = true })
            throw AssertionError("Expected 16 kHz guard")
        } catch (_: IllegalArgumentException) {
            assertFalse(called)
        }
    }
}
