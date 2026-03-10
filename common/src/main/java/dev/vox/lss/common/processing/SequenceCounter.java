package dev.vox.lss.common.processing;

/**
 * Monotonic counter for chunk submission ordering.
 * Used to assign a unique sequence number to each chunk dispatched within a processing cycle.
 * <p><b>Thread safety:</b> Not thread-safe. Must only be called from
 * the processing thread (via {@link ProcessingContext}).</p>
 */
public class SequenceCounter {
    private long value = 0;

    public long next() { return value++; }
}
