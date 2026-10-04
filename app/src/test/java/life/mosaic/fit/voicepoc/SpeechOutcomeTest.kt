package life.mosaic.fit.voicepoc
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
class SpeechOutcomeTest {
    @Test fun timeoutCannotBeOverwrittenByLateSuccess() {
        val result = SpeechOutcome<String>()
        assertTrue(result.finish("timeout")); assertFalse(result.finish("success"))
        assertEquals("timeout", result.get())
    }
    @Test fun disconnectCannotEraseCompletedResult() {
        val result = SpeechOutcome<String>()
        assertTrue(result.finish("success")); assertFalse(result.finish("disconnect"))
        assertEquals("success", result.get())
    }
    @Test fun concurrentTerminalsHaveOneWinner() {
        val result = SpeechOutcome<String>()
        val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf("cancel", "success").map { value -> pool.submit<Boolean> { gate.await(); result.finish(value) } }
            gate.countDown()
            assertEquals(1, tasks.count { it.get() }); assertTrue(result.get() in listOf("cancel", "success"))
        } finally { pool.shutdownNow() }
    }
}
