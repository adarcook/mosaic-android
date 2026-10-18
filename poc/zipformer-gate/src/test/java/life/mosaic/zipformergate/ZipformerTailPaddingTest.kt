package life.mosaic.zipformergate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZipformerTailPaddingTest {
    @Test fun finalizationPadsTwoSecondsOfSilenceBeforeFinish() {
        val events = mutableListOf<String>()
        var audio: FloatArray? = null
        val count = ZipformerTailPadding.finish(16_000, { samples, rate ->
            events.add("accept")
            assertEquals(16_000, rate)
            audio = samples
        }, {
            events.add("finished")
        })

        assertEquals(32_000, count)
        assertEquals(listOf("accept", "finished"), events)
        assertEquals(32_000, audio!!.size)
        assertTrue(audio!!.all { it == 0f })
    }

    @Test fun paddingDrainsRealFramesAtEveryPinnedEncoderChunkBoundary() {
        // Model metadata T=141, shift=128, feature frame step=10ms. Match the
        // native strict readiness comparison; cover all offsets within a chunk.
        // A 500ms tail fails this invariant even though callback-order tests pass.
        val paddingFrames = ZipformerTailPadding.TAIL_PADDING_MS / 10
        for (realFrames in 1..1_200) {
            var processed = 0
            val readyFrames = realFrames + paddingFrames - 3 // frontend margin
            while (processed + 141 < readyFrames) processed += 128
            assertTrue("Unprocessed real tail at frame $realFrames", processed >= realFrames)
        }
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
