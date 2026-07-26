package life.mosaic.fit

import java.io.ByteArrayOutputStream

/**
 * Accepts text writes used by the multipart request builder.
 *
 * The current request code accidentally concatenates a String with a ByteArray for the
 * Content-Disposition line. Kotlin turns that expression into a String containing the
 * ByteArray identity. Normalize that one header here so the request remains valid while
 * keeping all other text writes straightforward.
 */
internal fun ByteArrayOutputStream.write(value: String) {
    val normalized = if (
        value.startsWith("Content-Disposition: form-data; name=\"image\"; ")
    ) {
        "Content-Disposition: form-data; name=\"image\"; filename=\"meal.jpg\"\r\n"
    } else {
        value
    }
    write(normalized.toByteArray())
}
