package life.mosaic.zipformergate

/** Enumerate the existing BPE pieces of only the two fixed wake words. No idle text. */
internal object WakeKeywords {
    fun build(tokens: String): String {
        val vocabulary = tokens.lineSequence().filter { it.isNotBlank() }
            .map { it.substringBeforeLast(' ') }.toSet()
        val lines = mutableListOf<String>()
        for (word in listOf("מוזאיק", "מוזייק")) {
            val paths = mutableListOf<List<String>>()
            fun visit(rest: String, prefix: List<String>) {
                require(paths.size < 128) { "Wake keyword tokenization exceeds bound" }
                if (rest.isEmpty()) { paths.add(prefix); return }
                for (length in 1..rest.length) {
                    val piece=rest.take(length)
                    if (piece in vocabulary) visit(rest.drop(length),prefix+piece)
                }
            }
            visit("▁$word", emptyList())
            require(paths.isNotEmpty()) { "Wake word is not representable by enrolled tokens" }
            for (path in paths) lines.add(path.joinToString(" ")+" @"+word)
        }
        return lines.joinToString("\n",postfix="\n")
    }
}
