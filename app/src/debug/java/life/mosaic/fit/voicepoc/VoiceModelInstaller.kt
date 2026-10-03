package life.mosaic.fit.voicepoc

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal data class ModelDownload(val path: String, val url: String, val sha256: String, val size: Long)

/** Downloads public weights only. Partial files can resume; manifests commit last. */
internal class VoiceModelInstaller(
    private val root: File,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    companion object {
        fun target(root: File, name: String): File {
            require(name.isNotBlank() && !name.contains('\\') && !File(name).isAbsolute)
            val result = File(root, name).canonicalFile
            require(result.path.startsWith(root.canonicalPath + File.separator)) { "Invalid model path" }
            return result
        }
        fun sha256(file: File, cancelled: () -> Boolean = { false }): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    if (cancelled()) throw CancellationException("Cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        private fun move(source: File, destination: File) {
            try {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    fun installFile(spec: ModelDownload, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        require(spec.size > 0 && spec.sha256.matches(Regex("[0-9a-f]{64}")))
        val url = URL(spec.url)
        require(url.protocol == "https") { "HTTPS required" }
        val destination = target(root, spec.path)
        destination.parentFile!!.mkdirs()
        if (destination.isFile && destination.length() == spec.size && sha256(destination, cancelled) == spec.sha256) {
            progress(spec.size); return
        }
        val pending = File(destination.path + ".download")
        if (pending.length() > spec.size) pending.delete()
        if (pending.length() != spec.size) {
            val offset = pending.length()
            val connection = connect(url)
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            if (offset > 0) connection.setRequestProperty("Range", "bytes=$offset-")
            try {
                if (cancelled()) throw CancellationException("Cancelled")
                val code = connection.responseCode
                require(code == 200 || code == 206) { "Download HTTP $code: ${spec.path}" }
                val append = code == 206
                if (append) {
                    val range = connection.getHeaderField("Content-Range") ?: ""
                    require(range.startsWith("bytes $offset-") && range.endsWith("/${spec.size}")) { "Invalid resume response" }
                }
                var received = if (append) offset else 0L
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(pending, append).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            if (cancelled()) throw CancellationException("Cancelled")
                            val n = input.read(buffer)
                            if (n < 0) break
                            received += n
                            require(received <= spec.size) { "Model download exceeds expected size" }
                            output.write(buffer, 0, n)
                            progress(received)
                        }
                    }
                }
            } finally { connection.disconnect() }
        }
        require(pending.length() == spec.size) { "Incomplete download; tap download again to resume" }
        if (sha256(pending, cancelled) != spec.sha256) {
            pending.delete()
            error("Model checksum mismatch; retry download")
        }
        move(pending, destination)
        progress(spec.size)
    }

    fun commitFixture(reply: ByteArray, replySha256: String, manifest: ByteArray) {
        val pending = target(root, "reply.pending")
        pending.parentFile!!.mkdirs()
        pending.writeBytes(reply)
        require(sha256(pending) == replySha256) { "Reply fixture checksum mismatch" }
        move(pending, target(root, "reply.json"))
        val index = target(root, "manifest.pending")
        index.writeBytes(manifest)
        move(index, target(root, "manifest.json"))
    }
}
