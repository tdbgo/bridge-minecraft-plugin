package com.playcity.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.playcity.bridge.api.BridgeActionResult;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class NoticePluginAdapterTest {
    private static final Logger LOGGER = Logger.getLogger(NoticePluginAdapterTest.class.getName());

    @Test
    void syncCompletesThroughNoticeCallback() {
        FakeNotice notice = new FakeNotice();
        NoticePluginAdapter adapter = adapterFor(notice);

        BridgeActionResult result = adapter.sync().join();

        assertTrue(result.ok());
        assertTrue(notice.syncCalled);
        assertEquals("3.0.0", result.data().get("noticeVersion"));
    }

    @Test
    void sendPassesTheOriginalMessageWithoutBuildingACommand() {
        FakeNotice notice = new FakeNotice();
        NoticePluginAdapter adapter = adapterFor(notice);

        BridgeActionResult result = adapter.send("Hello players");

        assertTrue(result.ok());
        assertEquals("Hello players", notice.sentMessage);
        assertEquals(13, result.data().get("messageLength"));
    }

    @Test
    void reportsUnavailableAndIncompatibleNoticeApis() {
        NoticePluginAdapter unavailable = new NoticePluginAdapter(LOGGER, () -> null);
        assertEquals("notice_unavailable", unavailable.send("Hello").code());

        Object incompatibleTarget = new Object();
        NoticePluginAdapter incompatible = new NoticePluginAdapter(
            LOGGER,
            () -> new NoticePluginAdapter.NoticeTarget(incompatibleTarget, "unknown")
        );
        assertEquals("notice_api_incompatible", incompatible.send("Hello").code());
        assertEquals("notice_api_incompatible", incompatible.sync().join().code());
    }

    @Test
    void mapsNoticeApiFailuresToStableCodes() {
        FailingNotice notice = new FailingNotice();
        NoticePluginAdapter adapter = new NoticePluginAdapter(
            LOGGER,
            () -> new NoticePluginAdapter.NoticeTarget(notice, "3.0.0")
        );

        assertEquals("notice_sync_failed", adapter.sync().join().code());
        assertEquals("notice_send_failed", adapter.send("Hello").code());
    }

    private static NoticePluginAdapter adapterFor(FakeNotice notice) {
        return new NoticePluginAdapter(
            LOGGER,
            () -> new NoticePluginAdapter.NoticeTarget(notice, "3.0.0")
        );
    }

    public static final class FakeNotice {
        private boolean syncCalled;
        private String sentMessage;

        public void syncMessagesFromDatabaseAsync(Runnable onSuccess, Consumer<Throwable> onFailure) {
            syncCalled = true;
            onSuccess.run();
        }

        public void broadcastNoticeMessage(String message) {
            sentMessage = message;
        }
    }

    public static final class FailingNotice {
        public void syncMessagesFromDatabaseAsync(Runnable onSuccess, Consumer<Throwable> onFailure) {
            onFailure.accept(new IllegalStateException("not ready"));
        }

        public void broadcastNoticeMessage(String message) {
            throw new IllegalStateException("send failed");
        }
    }
}
