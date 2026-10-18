package life.mosaic.zipformergate

import org.junit.Assert.*
import org.junit.Test

class WakePhraseTest {
    @Test fun exactWakeWords() {
        assertTrue(WakePhrase.matches("היי מוזאיק!"))
        assertTrue(WakePhrase.matches("מוזייק"))
        assertTrue(WakePhrase.matches("Mosaic"))
    }
    @Test fun rejectsMusicAndSubstrings() {
        assertFalse(WakePhrase.matches("תנגן מוזיקה"))
        assertFalse(WakePhrase.matches("mosaics mosaicism"))
        assertFalse(WakePhrase.matches(""))
    }
}
