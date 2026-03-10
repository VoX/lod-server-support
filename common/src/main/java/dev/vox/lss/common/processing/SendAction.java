package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.UUID;

/**
 * Actions produced by the processing thread, consumed by the main thread.
 * These represent packets that must be sent from the main thread.
 */
public sealed interface SendAction {
    UUID playerUuid();
    int requestId();

    default byte responseType() {
        return switch (this) {
            case RateLimited a -> LSSConstants.RESPONSE_RATE_LIMITED;
            case ColumnUpToDate a -> LSSConstants.RESPONSE_UP_TO_DATE;
            case ColumnNotGenerated a -> LSSConstants.RESPONSE_NOT_GENERATED;
        };
    }

    record RateLimited(UUID playerUuid, int requestId) implements SendAction {}
    record ColumnUpToDate(UUID playerUuid, int requestId) implements SendAction {}
    record ColumnNotGenerated(UUID playerUuid, int requestId) implements SendAction {}
}
