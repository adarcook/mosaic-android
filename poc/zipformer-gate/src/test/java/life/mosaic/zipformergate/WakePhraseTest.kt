package life.mosaic.zipformergate

import org.junit.Assert.*
import org.junit.Test

class WakePhraseTest {
    @Test fun exactWakeWords() {
        assertTrue(WakePhrase.matches("היי מוזאיק!"))
        assertTrue(WakePhrase.matches("מוזייק"))
        assertTrue(WakePhrase.matches("Mosaic"))
        assertTrue(WakePhrase.matches("היי־מוזאיק"))
    }
    @Test fun rejectsMusicAndSubstrings() {
        assertFalse(WakePhrase.matches("תנגן מוזיקה"))
        assertFalse(WakePhrase.matches("mosaics mosaicism"))
        assertFalse(WakePhrase.matches(""))
    }
    @Test fun keywordPathsUseOnlyExistingPiecesAndFixedWakeWords() {
        val tokens="▁ 0\nמ 1\nו 2\nז 3\nא 4\nי 5\nק 6\n▁מ 7\n"
        val lines=WakeKeywords.build(tokens).trim().lines()
        assertEquals(4,lines.size)
        assertTrue(lines.all { it.endsWith("@מוזאיק")||it.endsWith("@מוזייק") })
        assertFalse(lines.any { it.contains("מוזיקה") })
        assertTrue(lines.any { it.startsWith("▁מ ") })
        assertTrue(lines.any { it.startsWith("▁ מ ") })
    }
    @Test fun missingWakeTokensFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { WakeKeywords.build("▁ 0\nמ 1\n") }
    }
}
