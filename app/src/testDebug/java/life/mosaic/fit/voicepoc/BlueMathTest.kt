package life.mosaic.fit.voicepoc

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BlueMathTest {
    @Test fun guidanceKeepsUnconditionalAtZeroAndConditionalAtOne() {
        val c = floatArrayOf(2f, 5f); val u = floatArrayOf(1f, 3f)
        assertArrayEquals(u, BlueMath.guidance(c, u, 0f), 0f)
        assertArrayEquals(c, BlueMath.guidance(c, u, 1f), 0f)
        assertArrayEquals(floatArrayOf(5f, 11f), BlueMath.guidance(c, u, 4f), 0f)
    }
    @Test fun channelMajorCompressionBecomesInterleavedTimeWithDenormalization() {
        // NumPy oracle: ((x/.5)*std+mean).reshape(1,2,2,2).transpose(0,1,3,2).reshape(-1)
        assertArrayEquals(floatArrayOf(1f, 14f, 3f, 18f, 44f, 78f, 50f, 86f),
            BlueMath.vocoderLatent(floatArrayOf(0f,1f,2f,3f,4f,5f,6f,7f),
                floatArrayOf(1f,6f,20f,30f), floatArrayOf(1f,2f,3f,4f), .5f, 2, 2, 2), 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMismatchedGuidanceShapes() {
        BlueMath.guidance(floatArrayOf(1f), floatArrayOf(), 4f)
    }
}
