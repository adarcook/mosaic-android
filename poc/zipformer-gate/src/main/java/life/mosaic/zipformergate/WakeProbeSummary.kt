package life.mosaic.zipformergate

/** Closed diagnostic flags only. Never retains/returns arbitrary recognized text. */
internal data class WakeProbeSummary(val expected: Boolean,val empty: Boolean,val variant: Int) {
    companion object {
        fun analyze(text: String): WakeProbeSummary {
            val words=text.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}]+"))
            val variant=when { "מוזיק" in words -> 1; "מוזיקה" in words -> 2; "מוזאיקה" in words -> 3; "מוזייקה" in words -> 4; else -> 0 }
            return WakeProbeSummary(WakePhrase.matches(text),text.isBlank(),variant)
        }
    }
}
