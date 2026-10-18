package life.mosaic.fit.voicepoc

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CancellationException

class VoiceModelInstallerTest {
    private class Response(private val bytes: ByteArray, private val code: Int = 200, private val range: String? = null) : HttpURLConnection(URL("https://example.test/model")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getHeaderField(name: String): String? = if (name == "Content-Range") range else null
        override fun getInputStream() = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 2))
        }
    }
    private fun spec(root: File, bytes: ByteArray): ModelDownload {
        val fixture = File(root, "fixture").apply { writeBytes(bytes) }
        return ModelDownload("whisper/model.bin", "https://example.test/model", VoiceModelInstaller.sha256(fixture), bytes.size.toLong())
    }

    @Test fun cancelledDownloadResumesWithValidatedRange() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        try {
            val bytes = "abcdef".toByteArray()
            val model = spec(root, bytes)
            var cancelled = false
            try {
                VoiceModelInstaller(root) { Response(bytes) }.installFile(model, { cancelled }) { cancelled = it >= 2 }
                fail("Expected cancellation")
            } catch (_: CancellationException) {}
            assertEquals(2L, File(root, "whisper/model.bin.download").length())
            val response = Response("cdef".toByteArray(), 206, "bytes 2-5/6")
            VoiceModelInstaller(root) { response }.installFile(model, { false }) {}
            assertEquals("bytes=2-", response.getRequestProperty("Range"))
            assertArrayEquals(bytes, File(root, "whisper/model.bin").readBytes())
            assertFalse(File(root, "whisper/model.bin.download").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptDownloadPreservesInstalledModel() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        try {
            val model = spec(root, "correct".toByteArray())
            val old = File(root, model.path).apply { parentFile!!.mkdirs(); writeText("old") }
            try {
                VoiceModelInstaller(root) { Response("corrupt".toByteArray()) }.installFile(model, { false }) {}
                fail("Expected checksum failure")
            } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("checksum")) }
            assertEquals("old", old.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun completedVerifiedFileDoesNotDownloadAgain() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        try {
            val bytes = "correct".toByteArray()
            val model = spec(root, bytes)
            File(root, model.path).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            VoiceModelInstaller(root) { error("Must not connect") }.installFile(model, { false }) {}
        } finally { root.deleteRecursively() }
    }

    @Test fun escapingPathIsRejected() {
        val root = Files.createTempDirectory("voice-model-test").toFile()
        try {
            try { VoiceModelInstaller.target(root, "../outside"); fail("Expected invalid path") }
            catch (_: IllegalArgumentException) {}
        } finally { root.deleteRecursively() }
    }
}
