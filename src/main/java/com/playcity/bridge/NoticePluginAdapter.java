package com.playcity.bridge;

import com.playcity.bridge.api.BridgeActionResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

final class NoticePluginAdapter {
    record NoticeTarget(Object instance, String version) {
    }

    private final Logger logger;
    private final Supplier<NoticeTarget> targetSupplier;

    NoticePluginAdapter(Logger logger, Supplier<NoticeTarget> targetSupplier) {
        this.logger = logger;
        this.targetSupplier = targetSupplier;
    }

    CompletableFuture<BridgeActionResult> sync() {
        NoticeTarget notice = targetSupplier.get();
        if (notice == null) {
            return CompletableFuture.completedFuture(unavailable());
        }

        Method method;
        try {
            method = notice.instance().getClass().getMethod(
                "syncMessagesFromDatabaseAsync",
                Runnable.class,
                Consumer.class
            );
        } catch (NoSuchMethodException exception) {
            logger.warning("Notice plugin does not expose the required sync API.");
            return CompletableFuture.completedFuture(incompatible());
        }

        CompletableFuture<BridgeActionResult> result = new CompletableFuture<>();
        Runnable onSuccess = () -> result.complete(successData(notice, "synchronizedAt"));
        Consumer<Throwable> onFailure = error -> {
            logger.warning(
                "Notice sync API rejected the Bridge action: type=" + errorType(error)
            );
            result.complete(BridgeActionResult.failure(503, "notice_sync_failed", "Notice synchronization failed."));
        };
        try {
            method.invoke(notice.instance(), onSuccess, onFailure);
        } catch (IllegalAccessException exception) {
            logger.warning("Notice sync API is inaccessible.");
            result.complete(incompatible());
        } catch (InvocationTargetException exception) {
            logger.warning(
                "Notice sync API failed before scheduling: type=" + errorType(exception.getCause())
            );
            result.complete(BridgeActionResult.failure(503, "notice_sync_failed", "Notice synchronization failed."));
        }
        return result;
    }

    BridgeActionResult send(String message) {
        NoticeTarget notice = targetSupplier.get();
        if (notice == null) {
            return unavailable();
        }

        Method method;
        try {
            method = notice.instance().getClass().getMethod("broadcastNoticeMessage", String.class);
        } catch (NoSuchMethodException exception) {
            logger.warning("Notice plugin does not expose the required send API.");
            return incompatible();
        }

        try {
            method.invoke(notice.instance(), message);
            Map<String, Object> data = new LinkedHashMap<>(successData(notice, "sentAt").data());
            data.put("messageLength", message.length());
            return BridgeActionResult.success(data);
        } catch (IllegalAccessException exception) {
            logger.warning("Notice send API is inaccessible.");
            return incompatible();
        } catch (InvocationTargetException exception) {
            logger.warning(
                "Notice send API rejected the Bridge action: type=" + errorType(exception.getCause())
            );
            return BridgeActionResult.failure(500, "notice_send_failed", "Notice send failed.");
        }
    }

    private static BridgeActionResult successData(NoticeTarget notice, String timestampKey) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("noticeVersion", notice.version());
        data.put(timestampKey, Instant.now().toString());
        return BridgeActionResult.success(data);
    }

    private static BridgeActionResult unavailable() {
        return BridgeActionResult.failure(503, "notice_unavailable", "Notice plugin is not available.");
    }

    private static BridgeActionResult incompatible() {
        return BridgeActionResult.failure(503, "notice_api_incompatible", "Notice plugin API is incompatible.");
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
