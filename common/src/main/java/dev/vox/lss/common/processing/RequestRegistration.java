package dev.vox.lss.common.processing;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Process-local identity of one player-state lifetime; never reused for a replacement.
 * The result sink belongs to this identity, so a late worker cannot publish into a
 * replacement UUID's queue. Retirement is one-way; a racing append can at worst reach
 * this detached sink, which the replacement registration never drains. */
public final class RequestRegistration {
    private final ConcurrentLinkedQueue<ChunkReadResult> results = new ConcurrentLinkedQueue<>();
    private volatile boolean retired;

    public boolean isRetired() { return this.retired; }

    public void retire() {
        this.retired = true;
        this.results.clear();
    }

    ConcurrentLinkedQueue<ChunkReadResult> results() { return this.results; }

    void addResult(ChunkReadResult result) {
        if (!this.retired) this.results.add(result);
    }
}
