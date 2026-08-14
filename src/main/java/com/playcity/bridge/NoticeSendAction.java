package com.playcity.bridge;

import com.playcity.bridge.api.BridgeAction;
import com.playcity.bridge.api.BridgeActionContext;
import com.playcity.bridge.api.BridgeActionRequest;
import com.playcity.bridge.api.BridgeActionResult;
import java.util.concurrent.CompletableFuture;

final class NoticeSendAction implements BridgeAction {
    private final NoticePluginAdapter noticeAdapter;

    NoticeSendAction(NoticePluginAdapter noticeAdapter) {
        this.noticeAdapter = noticeAdapter;
    }

    @Override
    public CompletableFuture<BridgeActionResult> execute(BridgeActionRequest request, BridgeActionContext context) {
        if (!(context instanceof DefaultBridgeActionContext bridgeContext)) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(500, "invalid_context", "Bridge action context is unavailable.")
            );
        }
        BridgeConfig config = bridgeContext.config();
        if (!config.noticeSendEnabled()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(503, "notice_send_disabled", "Notice send action is disabled.")
            );
        }

        String unexpectedField = NoticePayloadPolicy.unexpectedSendField(request.payload());
        if (!unexpectedField.isBlank()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, "invalid_notice_payload", "Notice send payload contains an unsupported field.")
            );
        }

        NoticeMessagePolicy.Validation validation = NoticeMessagePolicy.validate(
            request.payload(),
            config.noticeMaxMessageLength()
        );
        if (!validation.valid()) {
            return CompletableFuture.completedFuture(
                BridgeActionResult.failure(400, validation.errorCode(), validation.errorMessage())
            );
        }
        return bridgeContext.callSync(() -> noticeAdapter.send(validation.message()));
    }
}
