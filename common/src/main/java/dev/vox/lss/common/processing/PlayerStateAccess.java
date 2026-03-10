package dev.vox.lss.common.processing;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Interface for player state methods used by {@link OffThreadProcessor}.
 * Implemented by both Fabric's PlayerRequestState and Paper's PaperPlayerRequestState,
 * eliminating trivial delegate methods in the platform-specific processors.
 */
public interface PlayerStateAccess {
    void drainDirtyClearRequests();
    void clearProcessingState();
    boolean hasDiskReadDone(int cx, int cz);
    void markDiskReadDone(int cx, int cz);
    int getSendQueueSize();
    int getPendingSyncCount();
    int getPendingGenerationCount();
    boolean supportsVoxelColumns();
    UUID getPlayerUUID();
    RateLimiterSet getRateLimiters();
    ArrayDeque<AbstractPlayerRequestState.QueuedRequest> getWaitingQueue();
    int getWaitingQueueSize();

    // Per-request methods
    IncomingRequest pollIncomingRequest();
    void addPendingRequest(PendingRequest pending);
    PendingRequest removePendingByPosition(int cx, int cz);
    PendingRequest removePendingByRequestId(int requestId);
    boolean hasPendingRequest(int cx, int cz);

    // Cancel support
    Integer pollCancel();
}
