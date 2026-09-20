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
 *
 * <p>{@code estimatedBytes} is RAW-denominated (raw section bytes + envelope estimate) —
 * the bandwidth limiter's charge, deliberately unchanged by wire compression (the cap
 * bounds client decode/ingest WORK, which scales with raw bytes — design §5).
 * {@code wireBytes} is the SHIPPED size (frame or raw + envelope estimate) — the
 * {@code wire_bytes} diagnostics counter's input, counted at send success.
 */
public record QueuedPayload<T>(T payload, int estimatedBytes, int wireBytes,
                               long submissionOrder, long packedPos, boolean corrective)
        implements Comparable<QueuedPayload<T>> {

    /** Ordinary payload builders retain their existing constructor and wire bytes. */
    public QueuedPayload(T payload, int estimatedBytes, int wireBytes, long submissionOrder, long packedPos) {
        this(payload, estimatedBytes, wireBytes, submissionOrder, packedPos, false);
    }

    /** Pre-compression shape (wireBytes == estimatedBytes) — raw-shipping call sites
     *  and the existing test rigs. */
    public QueuedPayload(T payload, int estimatedBytes, long submissionOrder, long packedPos) {
        this(payload, estimatedBytes, estimatedBytes, submissionOrder, packedPos);
    }

    @Override
    public int compareTo(QueuedPayload<T> other) {
        return Long.compare(this.submissionOrder, other.submissionOrder);
    }
}
