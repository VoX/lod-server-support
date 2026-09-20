package dev.vox.lss.common.diagnostics;

/** Bounded owner collection and any-thread invalidation. Late publications are discarded. */
public final class StatusCache<T> {
    private long lifecycle;
    private long nextCollectionNanos;
    private T latest;
    public synchronized long invalidate() {
        lifecycle++;
        latest = null;
        nextCollectionNanos = 0;
        return lifecycle;
    }
    public synchronized long lifecycle() { return lifecycle; }
    public synchronized T latest() { return latest; }
    public synchronized boolean due(long nowNanos) {
        if (nextCollectionNanos != 0 && nowNanos - nextCollectionNanos < 0) return false;
        nextCollectionNanos = nowNanos + 500_000_000L;
        return true;
    }
    public synchronized boolean publish(long expectedLifecycle, T snapshot) {
        if (expectedLifecycle != lifecycle) return false;
        latest = snapshot;
        return true;
    }
}
