package life.mosaic.zipformergate

/** Exact words only. In particular מוזיקה must never be a wake phrase. */
internal object WakePhrase {
    fun matches(text: String): Boolean = text.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\u0591-\\u05C7]"), "")
        .split(Regex("[^\\p{L}]+"))
        .any { it == "מוזאיק" || it == "מוזייק" || it == "mosaic" }
}
