package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

import java.util.UUID;

/**
 * Actions produced by the processing thread, consumed by the main thread.
 * These represent packets that must be sent from the main thread. Requests are
 * addressed by packed chunk position (the protocol's request key).
 *
 * <p>Each action carries the state it was produced for: a dimension change
 * re-registers the same UUID with a fresh state, and actions produced for the
 * old session must not be delivered into the new one.
 */
public sealed interface SendAction {
    UUID playerUuid();
    long packedPosition();
    /** State the action was produced for; drained only while still the live session. */
    AbstractPlayerRequestState<?> producerState();

    byte responseType();

    record RateLimited(UUID playerUuid, long packedPosition,
                       AbstractPlayerRequestState<?> producerState) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_RATE_LIMITED; }
    }

    record ColumnUpToDate(UUID playerUuid, long packedPosition,
                          AbstractPlayerRequestState<?> producerState) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_UP_TO_DATE; }
    }

    record ColumnNotGenerated(UUID playerUuid, long packedPosition,
                              AbstractPlayerRequestState<?> producerState) implements SendAction {
        @Override public byte responseType() { return LSSConstants.RESPONSE_NOT_GENERATED; }
    }
}
