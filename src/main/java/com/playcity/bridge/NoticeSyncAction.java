package com.playcity.bridge;

import com.playcity.bridge.api.BridgeAction;
import com.playcity.bridge.api.BridgeActionContext;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import java.util.concurrent.CompletableFuture;

final class NoticeSyncAction implements BridgeAction {
    private final NoticePluginAdapter noticeAdapter;

    NoticeSyncAction(NoticePluginAdapter noticeAdapter) {
        this.noticeAdapter = noticeAdapter;
    }

    @Override
    public CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request, BridgeActionContext context) {
        if (!(context instanceof DefaultBridgeActionContext bridgeContext)) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(500, "invalid_context", "Bridge action context is unavailable.")
            );
        }
        if (!bridgeContext.config().noticeSyncEnabled()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(503, "notice_sync_disabled", "Notice sync action is disabled.")
            );
        }
        String unexpectedField = NoticePayloadPolicy.unexpectedSyncField(request.payload());
        if (!unexpectedField.isBlank()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_notice_payload", "Notice sync payload contains an unsupported field.")
            );
        }
        return bridgeContext.callSync(noticeAdapter::sync).thenCompose(future -> future);
    }
}
