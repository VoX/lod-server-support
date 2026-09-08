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
    private final AtomicBoolean failed = new AtomicBoolean();
    private volatile boolean completed;
    private final java.util.concurrent.atomic.AtomicInteger leases = new java.util.concurrent.atomic.AtomicInteger();

    ColumnDelivery(LodRequestManager owner, ResourceKey<Level> dimension, long packed,
                   long version, long dimensionGeneration, long preClearStamp, String bucket) {
        this.owner = owner;
        this.dimension = dimension;
        this.packed = packed;
        this.version = version;
        this.dimensionGeneration = dimensionGeneration;
        this.preClearStamp = preClearStamp;
        this.bucket = bucket;
    }

    @Override
    public boolean isActive() { return this.owner.isAcquisitionActive(); }

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

    void complete() {
        this.completed = true;
        this.owner.executeDeliveryEvent(() -> this.owner.finishDelivery(this));
    }

    boolean settled() { return this.completed && this.leases.get() == 0; }
    boolean accepted() { return settled() && !this.failed.get(); }
}
