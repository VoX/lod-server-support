package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicBoolean;

/** One admitted receipt; completion is observable before the owner's cache is detached. */
final class ColumnDelivery implements LSSApi.IngestFailureHandle {
    final LodRequestManager owner;
    final ResourceKey<Level> dimension;
    final long packed;
    final long version;
    final long dimensionGeneration;
    final long preClearStamp;
    final String bucket;
    /** Sent for the map backends alone (a map fill): the other consumers hold this column. */
    final boolean mapOnly;
    /** The delivery answers a fill's request: to the map alone, or to everyone because the
     *  server's column was newer than the one held. */
    final boolean fillAnswer;
    /** What was known about this column and the map before the delivery
     *  ({@link ColumnStateMap#mapState}); put back if the delivery is cancelled. */
    final byte mapStateBefore;
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean completed;
    private volatile boolean cancelled;
    private final java.util.concurrent.atomic.AtomicInteger leases = new java.util.concurrent.atomic.AtomicInteger();

    ColumnDelivery(LodRequestManager owner, ResourceKey<Level> dimension, long packed,
                   long version, long dimensionGeneration, long preClearStamp, String bucket) {
        this(owner, dimension, packed, version, dimensionGeneration, preClearStamp, bucket, false,
                false, ColumnStateMap.MAP_SETTLED);
    }

    ColumnDelivery(LodRequestManager owner, ResourceKey<Level> dimension, long packed,
                   long version, long dimensionGeneration, long preClearStamp, String bucket,
                   boolean mapOnly, boolean fillAnswer, byte mapStateBefore) {
        this.mapOnly = mapOnly;
        this.fillAnswer = fillAnswer || mapOnly;
        this.mapStateBefore = mapStateBefore;
        this.owner = owner;
        this.dimension = dimension;
        this.packed = packed;
        this.version = version;
        this.dimensionGeneration = dimensionGeneration;
        this.preClearStamp = preClearStamp;
        this.bucket = bucket;
    }

    @Override
    public boolean isActive() { return !this.cancelled && this.owner.isAcquisitionActive(); }

    @Override
    public void report() {
        // A failed delivery counts once, even when several consumers reject it.
        if (!isActive() || !this.failed.compareAndSet(false, true)) return;
        this.owner.executeDeliveryEvent(() -> this.owner.onDeliveryFailed(this));
    }

    @Override
    public Runnable deferAcceptance() {
        if (this.completed || !isActive()) return () -> {};
        this.leases.incrementAndGet();
        var released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                this.leases.decrementAndGet();
                this.owner.executeDeliveryEvent(() -> this.owner.finishDelivery(this));
            }
        };
    }

    private final AtomicBoolean mapWritten = new AtomicBoolean();

    private final AtomicBoolean mapEndApplied = new AtomicBoolean();

    /** A map has the column. Read when the delivery ends (the owner's applyMapEnd); with
     *  two maps, the first that writes it settles the debt (the debt is "no map was given
     *  this column"). */
    @Override
    public void written() {
        if (isActive()) this.mapWritten.set(true);
    }

    boolean mapWritten() { return this.mapWritten.get(); }
    boolean failed() { return this.failed.get(); }
    boolean cancelled() { return this.cancelled; }
    /** True once: what this delivery's end means for the map is applied one time. */
    boolean claimMapEnd() { return this.mapEndApplied.compareAndSet(false, true); }

    void cancel() { this.cancelled = true; }

    void complete() {
        this.completed = true;
        this.owner.executeDeliveryEvent(() -> this.owner.finishDelivery(this));
    }

    boolean settled() { return this.completed && this.leases.get() == 0; }
    boolean accepted() { return settled() && !this.failed.get(); }
}
