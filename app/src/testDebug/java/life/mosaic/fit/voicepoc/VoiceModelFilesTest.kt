package life.mosaic.fit.voicepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceModelFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun missingPackReportsAllRequiredFiles() {
        assertEquals(VoiceModelFiles.required, VoiceModelFiles.missing(temp.root))
    }

    @Test fun partialCopyReportsMissingAndEmptyFiles() {
        for (name in VoiceModelFiles.required.dropLast(1)) {
            File(temp.root, name).apply { parentFile.mkdirs(); writeText("fixture") }
        }
        File(temp.root, "reply.json").writeText("")
        assertEquals(listOf("reply.json", "blue/vocoder.onnx"), VoiceModelFiles.missing(temp.root))
        File(temp.root, "reply.json").writeText("fixture")
        File(temp.root, "blue/vocoder.onnx").writeText("fixture")
        assertTrue(VoiceModelFiles.missing(temp.root).isEmpty())
    }
}
