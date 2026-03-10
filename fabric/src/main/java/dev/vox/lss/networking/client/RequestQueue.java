package dev.vox.lss.networking.client;

/**
 * Ring buffer of chunk positions to request, populated by the scanner and
 * consumed by {@link LodRequestManager}.
 *
 * <p><b>Thread safety:</b> Not thread-safe. All methods must be called from
 * the main client thread (render/tick loop).
 */
class RequestQueue {

    private static final long[] EMPTY = new long[0];

    private long[] positions = EMPTY;
    private long[] timestamps = EMPTY;
    private int size = 0;
    private int readIndex = 0;

    /**
     * Replace the queue contents with the given scan result.
     */
    void populate(SpiralScanner.ScanResult result) {
        int n = result.count();
        if (this.positions.length < n) {
            this.positions = new long[n];
            this.timestamps = new long[n];
        }
        System.arraycopy(result.positions(), 0, this.positions, 0, n);
        System.arraycopy(result.timestamps(), 0, this.timestamps, 0, n);
        this.size = n;
        this.readIndex = 0;
    }

    boolean hasNext() {
        return this.readIndex < this.size;
    }

    long peekPosition() {
        return this.positions[this.readIndex];
    }

    long peekTimestamp() {
        return this.timestamps[this.readIndex];
    }

    /**
     * Advance past the current entry (skip without consuming).
     */
    void skip() {
        if (this.readIndex < this.size) this.readIndex++;
    }

    int remaining() {
        return Math.max(0, this.size - this.readIndex);
    }

    void clear() {
        this.positions = EMPTY;
        this.timestamps = EMPTY;
        this.size = 0;
        this.readIndex = 0;
    }
}
