package dev.vox.lss.common.processing;

/**
 * A ready-to-send column payload queued for the main-thread flush, ordered by submission
 * sequence. Generic over the platform payload type (Fabric: CustomPacketPayload,
 * Paper: encoded byte[]) so common code can read {@link #estimatedBytes()} without
 * naming an MC type.
 *
 * <p>{@code packedPos} identifies the column position so the player state can track
 * which positions are enqueued-but-unsent (honest answers to re-requests of positions
 * whose data is still in the pipeline) and report which positions a send-failure
 * queue drop discarded.
 */
public record QueuedPayload<T>(T payload, int estimatedBytes, long submissionOrder, long packedPos)
        implements Comparable<QueuedPayload<T>> {
    @Override
    public int compareTo(QueuedPayload<T> other) {
        return Long.compare(this.submissionOrder, other.submissionOrder);
    }
}
