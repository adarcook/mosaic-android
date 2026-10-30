package life.mosaic.zipformergate

/** Exact words only. In particular מוזיקה must never be a wake phrase. */
internal object WakePhrase {
    fun matches(text: String): Boolean = text.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\u0591-\\u05bd\\u05bf\\u05c1-\\u05c2\\u05c4-\\u05c5\\u05c7]"), "")
        .split(Regex("[^\\p{L}]+"))
        .any { it == "מוזאיק" || it == "מוזייק" || it == "mosaic" }
}
