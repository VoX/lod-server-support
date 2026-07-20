package dev.vox.lss.common.processing;

import dev.vox.lss.common.LSSConstants;

/**
 * Adaptive throttle for LSS disk reads (prototype). LSS reads flow through Minecraft's
 * single-threaded, per-dimension IO worker, which it <em>shares with vanilla</em> chunk
 * loading. LSS cannot see vanilla's queue depth directly, but it can measure its own
 * read latency (submit&rarr;result): when that latency rises the shared worker is busy —
 * likely servicing vanilla — so LSS should reduce its in-flight reads and yield the worker;
 * when latency is low the worker is idle and LSS can use it freely.
 *
 * <p>The controller is a classic AIMD loop (additive-increase / multiplicative-decrease,
 * the same shape TCP congestion control uses) over an <em>effective read-concurrency
 * limit</em>, clocked by completed reads and keyed on a smoothed (EWMA) latency signal:
 *
 * <pre>
 *   on each completed read, with sample latency s (nanos):
 *     ewma := (1 - alpha) * ewma + alpha * s          // smooth out single-read spikes
 *     if ewma &le; target:  limit := min(max, limit + increaseStep)   // headroom  -> grow slowly
 *     else:               limit := max(min, limit * decreaseFactor) // congested -> back off fast
 * </pre>
 *
 * The limit is a hard ceiling on outstanding reads: {@link #canSubmit(int)} admits a new
 * read only while {@code currentInFlight < limit}. When idle, the limit walks up to the
 * pool's full depth (so the throttle never gates before the pool itself would); under
 * congestion it collapses toward {@code minLimit} within a handful of samples.
 *
 * <p><b>Thread-safety.</b> {@link #recordLatency(long)} runs on the reader-pool threads
 * (several at once); {@link #canSubmit(int)} / {@link #currentLimit()} run on the single
 * processing thread that submits reads. The read-modify-write of {@code ewmaNanos} +
 * {@code limit} is done under {@code synchronized(this)} in {@code recordLatency} — the
 * only writer — and the published {@code limit} is a {@code volatile double} the submit
 * side reads without locking (a slightly stale limit only mis-sizes one admission, which
 * the next sample corrects). The controller is a heuristic: exact ordering is not required.
 *
 * <p><b>Known weakness (honest).</b> The latency sample is taken around the whole read
 * <em>operation</em> on the pool thread (see {@code AbstractChunkDiskReader.readAndDeliver}:
 * the clock starts at execution, so LSS's own queue depth is NOT in the window — but NBT
 * parsing and section serialization CPU are, and the pool threads run at low priority, so
 * descheduling under general CPU load inflates the sample too). The proxy therefore
 * conflates <em>disk contention</em> with <em>CPU pressure</em>: it can back off when the
 * box is CPU-busy but the disk is idle (and, conversely, under-react if a vanilla IO burst
 * is short relative to the EWMA window). Both misreads are conservative — LSS throttles
 * itself. It reduces the load LSS places on the shared worker, but it does <em>not</em>
 * truly make vanilla's reads jump the queue — there is no priority hand-off here, only
 * self-restraint.
 */
public final class AdaptiveReadThrottle {

    // ---- Tunable defaults (used by the production factory; overridable via the full constructor) ----

    /** EWMA weight of the newest latency sample. 0.2 blends roughly the last ~5 reads, so a
     *  single slow read nudges the signal without swinging the limit. Higher = more reactive,
     *  lower = smoother/slower. */
    public static final double DEFAULT_EWMA_ALPHA = 0.2;
    /** Multiplicative-decrease factor applied per over-target sample. 0.7 (a 30% cut) drops the
     *  limit to the floor within a few congested reads — fast yield, matching AIMD's "back off
     *  hard" half. */
    public static final double DEFAULT_DECREASE_FACTOR = 0.7;
    /** Additive-increase step applied per at-or-below-target sample. +1 read per good completion
     *  recovers the limit gradually (probe for headroom), matching AIMD's "grow gently" half. */
    public static final double DEFAULT_INCREASE_STEP = 1.0;
    /** Floor on the effective limit: at least one read may always be in flight. Never fully
     *  starving LSS keeps a live latency signal flowing, so the controller can detect recovery
     *  and climb back out of a backed-off state. */
    public static final int DEFAULT_MIN_LIMIT = 1;

    // ---- Immutable configuration ----

    private final int minLimit;
    private final int maxLimit;
    private final long targetLatencyNanos;
    private final double ewmaAlpha;
    private final double decreaseFactor;
    private final double increaseStep;

    // ---- Mutable controller state ----

    /** Smoothed read latency (nanos). Read/written only inside {@code synchronized recordLatency}. */
    private double ewmaNanos;
    /** True once the first sample has seeded {@code ewmaNanos}. Guarded by {@code synchronized}. */
    private boolean seeded;
    /** Effective concurrency ceiling. Written under lock in {@code recordLatency}, read lock-free
     *  by the submit side — hence volatile for visibility. Kept in [minLimit, maxLimit]. */
    private volatile double limit;

    /**
     * Build a throttle for a reader pool of {@code threadCount} workers each backed by
     * {@code queueCapacityPerThread} queued slots, steering toward {@code targetLatencyMs}.
     * The ceiling is the pool's full depth (running + queued) — beyond it the pool rejects
     * anyway, so the adaptive gate and the pool gate coincide when the worker is idle.
     * All other coefficients take the documented {@code DEFAULT_*} values.
     */
    public static AdaptiveReadThrottle forPool(int threadCount, int queueCapacityPerThread, int targetLatencyMs) {
        int maxLimit = Math.max(DEFAULT_MIN_LIMIT, threadCount * (1 + queueCapacityPerThread));
        long targetNanos = (long) targetLatencyMs * LSSConstants.NANOS_PER_MS;
        return new AdaptiveReadThrottle(DEFAULT_MIN_LIMIT, maxLimit, /* initialLimit = */ maxLimit,
                targetNanos, DEFAULT_EWMA_ALPHA, DEFAULT_DECREASE_FACTOR, DEFAULT_INCREASE_STEP);
    }

    /**
     * Full constructor — every coefficient explicit so tests can drive the control law with
     * synthetic latencies. Production code should prefer {@link #forPool(int, int, int)}.
     *
     * @param minLimit           floor on the effective limit (&ge; 1)
     * @param maxLimit           ceiling on the effective limit (&ge; minLimit)
     * @param initialLimit       starting limit (clamped into [minLimit, maxLimit]); the pool
     *                           starts optimistic at the ceiling so an idle worker is used freely
     * @param targetLatencyNanos EWMA latency setpoint; at/below it the limit grows, above it it shrinks
     * @param ewmaAlpha          newest-sample weight in (0, 1]
     * @param decreaseFactor     multiplicative-decrease factor in (0, 1)
     * @param increaseStep       additive-increase step per good sample (&gt; 0)
     */
    public AdaptiveReadThrottle(int minLimit, int maxLimit, double initialLimit,
                                long targetLatencyNanos, double ewmaAlpha,
                                double decreaseFactor, double increaseStep) {
        if (minLimit < 1) throw new IllegalArgumentException("minLimit must be >= 1");
        if (maxLimit < minLimit) throw new IllegalArgumentException("maxLimit must be >= minLimit");
        if (ewmaAlpha <= 0 || ewmaAlpha > 1) throw new IllegalArgumentException("ewmaAlpha must be in (0, 1]");
        if (decreaseFactor <= 0 || decreaseFactor >= 1) throw new IllegalArgumentException("decreaseFactor must be in (0, 1)");
        if (increaseStep <= 0) throw new IllegalArgumentException("increaseStep must be > 0");

        this.minLimit = minLimit;
        this.maxLimit = maxLimit;
        this.targetLatencyNanos = targetLatencyNanos;
        this.ewmaAlpha = ewmaAlpha;
        this.decreaseFactor = decreaseFactor;
        this.increaseStep = increaseStep;
        this.limit = clampLimit(initialLimit);
    }

    /**
     * Admission gate for the submit side (processing thread): true iff another read may be put
     * in flight right now. {@code currentInFlight} is the reader's outstanding-read count
     * (submitted minus completed). Compares against the floored current limit, so the limit is
     * an inclusive ceiling on concurrent reads.
     */
    public boolean canSubmit(int currentInFlight) {
        return currentInFlight < currentLimit();
    }

    /** The current effective concurrency ceiling, floored to an int (never below {@code minLimit}). */
    public int currentLimit() {
        return (int) this.limit; // limit is already >= minLimit >= 1, so the floor stays >= minLimit
    }

    /**
     * Feed one completed read's latency (nanos). Called on a reader-pool thread. Updates the
     * EWMA and takes a single AIMD step. Non-positive samples (a bounced/failed-before-read
     * completion that measured no real IO) are ignored so they cannot bias the signal.
     */
    public void recordLatency(long sampleNanos) {
        if (sampleNanos <= 0) return;
        synchronized (this) {
            if (this.seeded) {
                this.ewmaNanos = (1.0 - this.ewmaAlpha) * this.ewmaNanos + this.ewmaAlpha * sampleNanos;
            } else {
                this.ewmaNanos = sampleNanos; // seed directly — no prior estimate to blend with
                this.seeded = true;
            }

            double next;
            if (this.ewmaNanos <= this.targetLatencyNanos) {
                next = this.limit + this.increaseStep;        // headroom: grow additively toward max
            } else {
                next = this.limit * this.decreaseFactor;      // congested: shrink multiplicatively toward min
            }
            this.limit = clampLimit(next);
        }
    }

    private double clampLimit(double value) {
        if (value < this.minLimit) return this.minLimit;
        if (value > this.maxLimit) return this.maxLimit;
        return value;
    }

    // ---- Observability (test + diagnostics) ----

    /** Smoothed latency estimate in nanos (0 until the first sample). */
    public double ewmaLatencyNanos() {
        synchronized (this) { return this.ewmaNanos; }
    }

    public int minLimit() { return this.minLimit; }
    public int maxLimit() { return this.maxLimit; }
    public long targetLatencyNanos() { return this.targetLatencyNanos; }
}
