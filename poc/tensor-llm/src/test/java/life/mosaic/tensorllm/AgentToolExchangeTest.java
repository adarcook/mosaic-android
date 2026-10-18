package life.mosaic.tensorllm;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentToolExchangeTest {
    @Test public void onlyMatchingReplyCompletesOnce() throws Exception {
        AgentToolExchange exchange=new AgentToolExchange();
        assertFalse(exchange.complete("old-id","OK","OLD_DATA"));
        assertTrue(exchange.complete(exchange.id,"OK","SYNTHETIC_DATA"));
        assertFalse(exchange.complete(exchange.id,"OK","SECOND_DATA"));
        assertEquals("SYNTHETIC_DATA",exchange.await(0).displayed);
    }
    @Test public void malformedRepliesCannotAdvanceExchange() throws Exception {
        AgentToolExchange exchange=new AgentToolExchange();
        assertFalse(exchange.complete(exchange.id,"invented_status","data"));
        assertFalse(exchange.complete(exchange.id,"OK","x".repeat(1201)));
        assertFalse(exchange.complete(exchange.id,"OK",""));
        assertFalse(exchange.complete(exchange.id,"OK",null));
        assertTrue(exchange.complete(exchange.id,"DEVICE_LOCKED","Locked"));
        assertEquals(VoiceTool.Status.DEVICE_LOCKED,exchange.await(0).status);
    }
    @Test public void timeoutAndCancellationRejectLateReplies() {
        AgentToolExchange timed=new AgentToolExchange();
        assertThrows(Exception.class,()->timed.await(0));
        assertFalse(timed.complete(timed.id,"OK","LATE_DATA"));
        AgentToolExchange cancelled=new AgentToolExchange();cancelled.close();
        assertFalse(cancelled.complete(cancelled.id,"OK","CANCELLED_DATA"));
        assertThrows(Exception.class,()->cancelled.await(0));
    }
    @Test public void ipcPayloadIsExcludedFromDiagnostics() {
        for(String field:new String[]{"agent_content","agent_arguments","agent_function","agent_call_id","text","answer","normalized_text"})
            assertFalse(VoiceStagePrivacy.journalField(field,true));
        assertTrue(VoiceStagePrivacy.journalField("generate_ms",true));
        assertTrue(VoiceStagePrivacy.journalField("worker_pss_kb",true));
        assertTrue(VoiceStagePrivacy.journalField("answer",false));
    }
}
