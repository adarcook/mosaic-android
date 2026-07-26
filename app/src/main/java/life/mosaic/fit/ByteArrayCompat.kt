package life.mosaic.fit

/** Joins a UTF-8 prefix with an already encoded suffix for multipart request construction. */
internal operator fun String.plus(suffix: ByteArray): ByteArray =
    toByteArray(Charsets.UTF_8) + suffix
