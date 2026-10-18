package life.mosaic.tensorllm;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceToolRouterTest {
    private static final String READ = "תקריא לי את ההודעה האחרונה בוואטסאפ";

    @Test public void routingDoesNotReadNotifications() {
        AtomicInteger reads = new AtomicInteger();
        WhatsAppVoiceTool tool = new WhatsAppVoiceTool(() -> {
            reads.incrementAndGet();
            return WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.NO_MESSAGE);
        });
        VoiceToolRouter router = new VoiceToolRouter(List.of(tool));
        assertSame(tool, router.route(READ));
        assertEquals(0, reads.get());
        assertEquals(VoiceTool.Status.NO_MESSAGE, router.route(READ).execute().status);
        assertEquals(1, reads.get());
    }

    @Test public void conversationAndRejectedCommandsNeverReadPrivateData() {
        VoiceToolRouter router = new VoiceToolRouter(List.of(new WhatsAppVoiceTool(() -> {
            fail("Unselected capability must not execute");
            return null;
        })));
        for (String request : new String[]{null, "", "שלום מוזאיק", "אל תקריא הודעה בוואטסאפ",
                "שלח הודעה חדשה בוואטסאפ", "איך לקרוא הודעה בוואטסאפ",
                "תקריא לי את כל היסטוריית השיחות בוואטסאפ"}) {
            assertNull(router.route(request));
        }
    }

    @Test public void permissionAndLockAreRecheckedAfterSelection() {
        WhatsAppNotificationTool.Status[] current = {WhatsAppNotificationTool.Status.PERMISSION_DENIED};
        VoiceToolRouter router = new VoiceToolRouter(List.of(new WhatsAppVoiceTool(
                () -> WhatsAppNotificationTool.unavailable(current[0]))));
        VoiceTool selected = router.route(READ);
        assertEquals(VoiceTool.Status.PERMISSION_DENIED, selected.execute().status);
        current[0] = WhatsAppNotificationTool.Status.DEVICE_LOCKED;
        assertEquals(VoiceTool.Status.DEVICE_LOCKED, selected.execute().status);
        current[0] = WhatsAppNotificationTool.Status.NO_MESSAGE;
        assertEquals(VoiceTool.Status.NO_MESSAGE, selected.execute().status);
    }

    @Test public void allUnavailableStatusesKeepTheirTruthfulResponse() {
        for (WhatsAppNotificationTool.Status status : WhatsAppNotificationTool.Status.values()) {
            if (status == WhatsAppNotificationTool.Status.OK) continue;
            WhatsAppNotificationTool.Result original = WhatsAppNotificationTool.unavailable(status);
            VoiceTool.Result result = new WhatsAppVoiceTool(() -> original).execute();
            assertEquals(status.name(), result.status.name());
            assertEquals(WhatsAppNotificationTool.displayed(original), result.displayed);
            assertEquals(WhatsAppNotificationTool.spoken(original), result.spoken);
        }
    }

    @Test public void displayAndSpeechStaySeparateAndPayloadIsNotCached() {
        AtomicInteger reads = new AtomicInteger();
        WhatsAppVoiceTool tool = new WhatsAppVoiceTool(() -> reads.getAndIncrement() == 0
                ? WhatsAppNotificationTool.selectLatest(List.of(new WhatsAppNotificationTool.Snapshot(
                        "com.whatsapp", "TEST_SENDER", "https://example.com/private", 1, false, true)))
                : WhatsAppNotificationTool.unavailable(WhatsAppNotificationTool.Status.DEVICE_LOCKED));
        VoiceTool.Result first = tool.execute();
        assertTrue(first.displayed.contains("https://example.com/private"));
        assertFalse(first.spoken.contains("https://"));
        assertTrue(first.spoken.contains("קישור מצורף"));
        assertFalse(first.toString().contains("TEST_SENDER"));
        VoiceTool.Result second = tool.execute();
        assertEquals(VoiceTool.Status.DEVICE_LOCKED, second.status);
        assertFalse(second.displayed.contains("TEST_SENDER"));
        assertFalse(second.spoken.contains("example.com"));
    }

    @Test public void registryIsAnImmutableSnapshot() {
        List<VoiceTool> registry = new ArrayList<>();
        VoiceTool tool = fake("test", "test_route");
        registry.add(tool);
        VoiceToolRouter router = new VoiceToolRouter(registry);
        registry.clear();
        assertSame(tool, router.route("request"));
    }

    @Test public void duplicateIdentitiesAndReservedRoutesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VoiceToolRouter(List.of(
                fake("same", "one"), fake("same", "two"))));
        assertThrows(IllegalArgumentException.class, () -> new VoiceToolRouter(List.of(
                fake("one", "same"), fake("two", "same"))));
        assertThrows(IllegalArgumentException.class, () -> new VoiceToolRouter(List.of(fake("one", "conversation"))));
    }

    @Test public void ambiguousSelectionFailsBeforeExecution() {
        VoiceToolRouter router = new VoiceToolRouter(List.of(fake("one", "one"), fake("two", "two")));
        assertThrows(IllegalStateException.class, () -> router.route("request"));
    }

    private static VoiceTool fake(String id, String route) {
        return new VoiceTool() {
            public String id() { return id; }
            public String route() { return route; }
            public boolean matches(String request) { return true; }
            public Result execute() { throw new AssertionError("Routing executed a capability"); }
        };
    }
}
