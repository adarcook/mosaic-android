package life.mosaic.zipformergate
import org.junit.Test
import org.junit.Assert.*
class WakeProbeSummaryTest {
    @Test fun fixedExpectedWordIsRecognizedWithoutReturningText(){val result=WakeProbeSummary.analyze("מוזאיק");assertTrue(result.expected);assertFalse(result.empty);assertEquals(0,result.variant)}
    @Test fun nearWordAndMusicAreDiagnosticFlagsNotWakes(){assertFalse(WakeProbeSummary.analyze("מוזיק").expected);assertEquals(1,WakeProbeSummary.analyze("מוזיק").variant);assertFalse(WakeProbeSummary.analyze("מוזיקה").expected);assertEquals(2,WakeProbeSummary.analyze("מוזיקה").variant)}
    @Test fun unrelatedPrivateTextProducesOnlyClosedFlags(){val result=WakeProbeSummary.analyze("שלום עולם");assertFalse(result.expected);assertFalse(result.empty);assertEquals(0,result.variant);assertTrue(WakeProbeSummary.analyze(" ").empty)}
}
