package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

class ClientColumnProcessor {

    // Log-sweep hygiene (2026-08-13): per-column/per-frame failure sites aggregate to
    // one line/min — a persistent condition must not flood the client log.
    private static final dev.vox.lss.common.LogThrottle PROCESS_FAIL_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    static final int MAX_QUEUED_COLUMNS = 8000;
    /** Byte budget for queued (still-compressed-in-memory) section payloads: the count cap
     *  alone admits up to 8000 x 2 MiB = 16 GiB from a hostile or misbehaving server before
     *  any drop fires. 256 MiB is ~17 s of backlog at the default 15 MiB/s per-player
     *  bandwidth cap (v0.9.1) — unreachable in normal play, fatal-allocation-proof under
     *  attack. */
    static final long MAX_QUEUED_BYTES = 256L * 1024 * 1024;
    private static final long DROP_WARN_INTERVAL_MS = 5000;

    /**
     * Receives every "delivered but never ingested" report this processor emits (queue
     * overflow, backlog cleared without dispatch, empty/corrupt section bytes). Test seam
     * with a production default of {@link LSSClientNetworking#reportIngestFailure}, which
     * makes the manager forget the position's received-stamp and re-request it.
     */
    @FunctionalInterface
    interface FailureReporter {
        void report(ResourceKey<Level> dimension, int chunkX, int chunkZ);
    }

    /**
     * Fan-out target for a successfully decoded column. Test seam: production wiring
     * dispatches via {@link LSSApi#dispatchColumn} with the live client level.
     */
    @FunctionalInterface
    interface ColumnDispatcher {
        void dispatch(ResourceKey<Level> dimension, int chunkX, int chunkZ, VoxelColumnData columnData);
    }

    private final FailureReporter failureReporter;
    // Supplies the level scheduleProcessing drains against (null clears the backlog with
    // reports). Test seam; the production default reads the live Minecraft client.
    private final Supplier<ClientLevel> levelSupplier;

    /**
     * A queued column plus whether it is a RESYNC (the client already held data for this
     * position). On a resync the decode fills every absent section-Y with an all-air section
     * so a consumer overwrites ghost terrain the server cleared (WorldEdit-style clears and
     * fully-emptied columns). Captured on the main thread at offer time — the decode thread
     * must not touch ColumnStateMap.
     *
     * <p>{@code charge} is the byte-gauge charge this column took at offer — stored so
     * every release path (drain, clear, shutdown, undispatched sweep) returns EXACTLY what
     * was charged, never re-derived (plan §0.5). Raw-work-denominated: the payload's
     * {@code rawSize()} under the max(shipped, clamp(declared)) rule for codec-1 frames.
     */
    private record QueuedColumn(VoxelColumnS2CPayload payload, boolean resync, int charge,
                                ColumnDelivery delivery) {}

    private final ConcurrentLinkedQueue<QueuedColumn> columnQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicLong columnsDropped = new AtomicLong();
    private volatile long lastDropWarnMs = 0;

    // Off-thread column processing: one executor for the singleton's lifetime. A session
    // epoch (bumped on disconnect) makes any in-flight drain self-terminate instead of
    // dispatching a stale session's payloads — no executor teardown/recreation needed.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, Brand.shortName() + "-ColumnProcessor");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean processing = new AtomicBoolean();
    private volatile int sessionEpoch;

    ClientColumnProcessor() {
        this(LSSClientNetworking::reportIngestFailure, ClientColumnProcessor::liveClientLevel);
    }

    /** Seam constructor for tests; the no-arg constructor is the production wiring. */
    ClientColumnProcessor(FailureReporter failureReporter, Supplier<ClientLevel> levelSupplier) {
        this.failureReporter = failureReporter;
        this.levelSupplier = levelSupplier;
    }

    private static ClientLevel liveClientLevel() {
        var mc = Minecraft.getInstance();
        return mc != null ? mc.level : null;
    }

    /**
     * The byte-gauge charge for one payload: raw-work-denominated (the backpressure-halt
     * and scanner pressure gates are calibrated in raw bytes), floored at the SHIPPED
     * length so a lying codec-1 header can never under-charge below resident bytes
     * (plan §0.5 — the payload's {@code rawSize()} already applies the
     * max(shipped, clamp(declared, 0, MAX)) rule at read).
     */
    private static int chargeOf(VoxelColumnS2CPayload payload) {
        return payload.shippedSections() == null ? 0 : payload.rawSize();
    }

    /** Queue admission: bounded by count AND bytes (either alone admits multi-GiB retention). */
    static boolean admits(int queuedCount, long queuedBytes, int payloadBytes) {
        return queuedCount < MAX_QUEUED_COLUMNS
                && queuedBytes + payloadBytes <= MAX_QUEUED_BYTES;
    }

    void offer(VoxelColumnS2CPayload payload, boolean resync) {
        offer(payload, resync, null);
    }

    void offer(VoxelColumnS2CPayload payload, boolean resync, ColumnDelivery delivery) {
        // Null/corrupt section bytes still traverse the queue (the drain reports them):
        // count them as zero rather than NPE here.
        int payloadBytes = chargeOf(payload);
        if (admits(this.queueSize.get(), this.queuedBytes.get(), payloadBytes)) {
            this.columnQueue.add(new QueuedColumn(payload, resync, payloadBytes, delivery));
            this.queueSize.incrementAndGet();
            this.queuedBytes.addAndGet(payloadBytes);
        } else {
            long dropped = this.columnsDropped.incrementAndGet();
            long now = System.currentTimeMillis();
            if (now - this.lastDropWarnMs > DROP_WARN_INTERVAL_MS) {
                this.lastDropWarnMs = now;
                LSSLogger.warn("Column processing queue full (" + MAX_QUEUED_COLUMNS
                        + "), " + dropped + " columns dropped total");
            }
            // The receive handler already stamped this position received — without the
            // report the drop would never be re-requested (permanent hole).
            reportFailure(new QueuedColumn(payload, resync, payloadBytes, delivery));
            if (delivery != null) delivery.complete();
        }
    }

    private void reportFailure(QueuedColumn queued) {
        if (queued.delivery() != null) queued.delivery().report();
        else this.failureReporter.report(queued.payload().dimension(),
                queued.payload().chunkX(), queued.payload().chunkZ());
    }

    void scheduleProcessing(boolean serverEnabled) {
        if (!serverEnabled || !LSSClientConfig.CONFIG.receiveServerLods || !LSSApi.hasVoxelConsumers()) {
            reportAndClearBacklog();
            return;
        }

        var level = this.levelSupplier.get();
        if (level == null) {
            reportAndClearBacklog();
            return;
        }

        if (this.columnQueue.isEmpty()) return;

        int epoch = this.sessionEpoch;
        if (this.processing.compareAndSet(false, true)) {
            try {
                this.executor.execute(() -> {
                    try {
                        drainColumnQueue(level, epoch);
                    } finally {
                        this.processing.set(false);
                    }
                });
            } catch (Exception e) {
                this.processing.set(false);
            }
        }
    }

    /**
     * Clear the decode backlog, reporting every queued column as an ingest failure. Each
     * was stamped received at arrival, so the previously-silent clears (receiveServerLods
     * disable flip, last consumer deregistering, level-null race) left permanent false
     * stamps for data no consumer ever saw. A clear-while-still-serving loop is bounded
     * by the per-position failure cap (ColumnStateMap.MAX_INGEST_FAILURES parks the
     * position as satisfied).
     */
    private void reportAndClearBacklog() {
        QueuedColumn queued;
        while ((queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-queued.charge());
            reportFailure(queued);
            if (queued.delivery() != null) queued.delivery().complete();
        }
    }

    private void drainColumnQueue(ClientLevel level, int epoch) {
        var factory = PalettedContainerFactory.create(level.registryAccess());
        var resolver = resolverFor(level, factory);
        drainColumnQueue(level.dimension(), level.getSectionsCount(), level.getMinSectionY(),
                level.dimensionType().hasSkyLight(),
                factory,
                // Legacy-server (v16 OR the C3 ladder's 19 rung) sessions ship NATIVE
                // bodies — translating them would parse sectionCount as dictCount and
                // fail every column (review C1-2). The gate is PER PAYLOAD via the
                // decode-time stamp (payload.nativeBodyAtDecode(), applied in the inner
                // drain) — the drain thread must never consult the LIVE dialect flag,
                // which the C3 ladder can flip while old-dialect columns are still
                // queued (review MAJOR-2).
                resolver != null ? resolver::toNative
                        : bytes -> { throw new IllegalStateException(
                                "identity resolver unavailable (see the construction"
                                        + " warn) — column reported as ingest failure"); },
                (dimension, chunkX, chunkZ, columnData) ->
                        LSSApi.dispatchColumn(level, dimension, chunkX, chunkZ, columnData),
                epoch);
    }

    // Per-session §3 resolver (memoized identities + once-per-identity fallback warns).
    // Rebuilt only if the registry access changes — dimensions share the server's, so a
    // dimension change keeps the memo; a new server connection gets a fresh one.
    private volatile ClientIdentityResolver identityResolver;
    private volatile Object identityResolverKey;

    private ClientIdentityResolver resolverFor(ClientLevel level, PalettedContainerFactory factory) {
        var key = level.registryAccess();
        var resolver = this.identityResolver;
        if (resolver == null || this.identityResolverKey != key) {
            try {
                resolver = new ClientIdentityResolver(
                        key.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME),
                        factory.biomeStrategy().globalMap());
            } catch (Throwable ctor) {
                // Containment belt (C6 review C-2): a throwing constructor used to
                // escape the drain's executor task EVERY tick with no ingest-failure
                // report — columns never dispatched, decode queue fills, backpressure
                // halts, LOD dead for the session with only stack traces as signal.
                // Config validation now prevents the known cause (null curated
                // entries); this belt makes any future variant degrade: null resolver
                // -> the call site's translator throws PER COLUMN -> the inner drain's
                // existing decode containment reports each as an ingest failure (the
                // server re-serves; a fixed config + rejoin heals). Warn once per key.
                if (this.identityResolverKey != key) {
                    dev.vox.lss.common.LSSLogger.warn("Identity resolver construction"
                            + " failed — v20 columns will report as ingest failures"
                            + " until the cause (likely a malformed client config) is"
                            + " fixed", ctor);
                }
                this.identityResolverKey = key;
                this.identityResolver = null;
                return null;
            }
            this.identityResolver = resolver;
            this.identityResolverKey = key;
        }
        return resolver;
    }

    /**
     * The drain loop, parameterized on the level-derived inputs so tests can drive it
     * without a {@link ClientLevel}. Contract per polled column: a stale-dimension column
     * is consumed silently (its stamp lives in the other dimension's map — a report here
     * would unstamp the wrong position); empty/absent bytes and any decode throw report
     * exactly once and the drain continues; the loop re-checks the session epoch at every
     * poll, so a teardown mid-drain lets at most the already-polled column dispatch.
     */
    /** Test-seam overload: drives the drain over NATIVE-layout fixture bodies (no v20
     *  translation) — the drain-mechanics suites predate protocol 20 and pin epoch/
     *  reporting/dispatch behavior, not the body encoding. Production always goes
     *  through the resolver-backed 8-arg form. */
    void drainColumnQueue(ResourceKey<Level> levelDimension, int levelSectionCount, int minSectionY,
                          boolean hasSkyLight,
                          PalettedContainerFactory factory, ColumnDispatcher dispatcher, int epoch) {
        drainColumnQueue(levelDimension, levelSectionCount, minSectionY, hasSkyLight,
                factory, java.util.function.UnaryOperator.identity(), dispatcher, epoch);
    }

    void drainColumnQueue(ResourceKey<Level> levelDimension, int levelSectionCount, int minSectionY,
                          boolean hasSkyLight,
                          PalettedContainerFactory factory,
                          java.util.function.UnaryOperator<byte[]> v20ToNative,
                          ColumnDispatcher dispatcher, int epoch) {
        QueuedColumn queued;
        while (epoch == this.sessionEpoch && (queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-queued.charge());
            var delivery = queued.delivery();
            try {
            var payload = queued.payload();
            if (!levelDimension.equals(payload.dimension())) continue;

            byte[] shipped = payload.shippedSections();
            if (shipped == null || shipped.length == 0) {
                // Defensive (a compliant server always writes at least the section-count
                // varint) — but the position was stamped received at arrival, so a silent
                // drop here would be a permanent false stamp.
                reportFailure(queued);
                continue;
            }

            try {
                // Codec dispatch (protocol 19): codec 0 decodes the shipped bytes as-is;
                // codec 1 decompresses first, inside this try — a bomb-guard rejection or
                // any zstd throw reports through the same ingest-failure path as a decode
                // throw (the server re-serves). Codec values outside {0,1} are decode
                // failures too — NOT the source tag's pass-through rule; the byte changes
                // how these bytes must be read (plan §0.7).
                byte[] decompressed = decompressForDecode(payload.codec(), shipped);
                // Protocol 20: translate the identity-dictionary body to THIS client's
                // native layout (identities resolved through the §3 fallback ladder),
                // then feed the existing native decode unchanged (§2.3). Gated per
                // payload on the decode-time dialect stamp — a native body (v16/19
                // session at the moment this frame decoded) skips translation even if
                // the ladder has since re-established a different dialect.
                byte[] nativeBytes = payload.nativeBodyAtDecode()
                        ? decompressed : v20ToNative.apply(decompressed);
                var sections = decodeSections(nativeBytes, levelSectionCount, factory);
                if (ClientTraceLog.enabled()) {
                    // Per-section light presence — the boundary-lighting instrument (black
                    // leaf-face investigation 2026-07-27): [sectionY, hasBlockLight,
                    // hasSkyLight, hasOnlyAir] per decoded section. Absent Ys are absent.
                    var sb = new StringBuilder(64 + sections.length * 14);
                    sb.append("\"pos\":[").append(payload.chunkX()).append(',')
                            .append(payload.chunkZ()).append("],\"sections\":[");
                    for (int i = 0; i < sections.length; i++) {
                        var s = sections[i];
                        if (i > 0) sb.append(',');
                        sb.append('[').append(s.sectionY())
                                .append(',').append(s.blockLight() != null ? 1 : 0)
                                .append(',').append(s.skyLight() != null ? 1 : 0)
                                .append(',').append(s.section().hasOnlyAir() ? 1 : 0).append(']');
                    }
                    sb.append(']');
                    ClientTraceLog.event("col_light", sb.toString());
                }
                if (hasSkyLight) {
                    // Implicit-sky fill (2026-07-27, black-boundary-faces fix): vanilla
                    // stores sky layers only up to heightmap+1 — everything above is
                    // IMPLICITLY full-bright, a rule the vanilla client resolves in its
                    // light engine but a per-section wire cannot carry. Without this, a
                    // consumer sampling the never-served air above/beside terrain (leaf
                    // tops, cliff sides across a chunk border against a lower column)
                    // reads light 0 and shades those faces black. Mirror vanilla's rule
                    // client-side: every absent section ABOVE the column's top served
                    // section becomes explicit all-air with full-bright sky.
                    // MUST run BEFORE the resync air-fill: this pass derives the column's
                    // top from the SERVED sections, and after an air-fill every section-Y
                    // is present so it finds nothing above the top. The shipped v0.8.0
                    // order ran it second, which silently disabled it on every resync
                    // delivery — re-served columns went dark above the terrain (M1 of the
                    // 2026-07-28 review round).
                    sections = withImplicitSkyAbove(sections, levelSectionCount, minSectionY, factory);
                }
                if (queued.resync()) {
                    // The client already held data here; the server sends the current column
                    // (which OMITS air sections) authoritatively. Fill every absent section-Y
                    // with an all-air section so the consumer overwrites terrain the server
                    // cleared (WorldEdit clears, fully-emptied 0-section columns). Only on
                    // resync — a first serve had nothing, so absent == air == no-op. Runs
                    // AFTER the sky fill, so the Ys still absent here are below/among the
                    // served band, where unserved air is dark (correct for normal terrain;
                    // the serializer's band rule already approximates floating-island
                    // below-band sky air away, unchanged by this ordering).
                    // The ONE exception: a 0-section CLEAR in a sky dimension fills BRIGHT —
                    // vanilla sky light in an all-air column is 15 top to bottom (vertical
                    // propagation is undiminished), so a dark fill turned a WorldEdit-cleared
                    // column into a light-0 volume that shaded every neighbouring face black.
                    boolean brightClear = hasSkyLight && sections.length == 0;
                    sections = withAirFilledAbsentSections(sections, levelSectionCount, minSectionY,
                            factory, brightClear);
                }
                var columnData = new VoxelColumnData(sections, payload.columnTimestamp());
                if (delivery != null && (epoch != this.sessionEpoch || !delivery.isActive())) continue;
                LSSApi.withIngestFailureHandle(payload.dimension(), payload.chunkX(), payload.chunkZ(),
                        delivery, () -> dispatcher.dispatch(payload.dimension(),
                                payload.chunkX(), payload.chunkZ(), columnData));
            } catch (Throwable t) {
                // Throwable, not Exception: an OOME allocating section buffers (or a
                // LinkageError from MC internals) escaping here would consume the column —
                // polled and decremented — with no dispatch AND no report, leaving a
                // permanent false received-stamp that persists to the cache (the exact hole
                // class the delivery-honesty refactor eliminated; LSSApi.dispatchColumn one
                // frame downstream already treats Throwable this way). Report FIRST so the
                // stamp is forgotten, then let true Errors propagate.
                long n = PROCESS_FAIL_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (n > 0) {
                    LSSLogger.error("Failed to process voxel column at "
                            + payload.chunkX() + "," + payload.chunkZ() + " (" + n
                            + " failure(s) since the last report)", t);
                }
                reportFailure(queued);
                if (t instanceof Error err && !(t instanceof AssertionError)) throw err;
            }
            } finally {
                if (delivery != null) delivery.complete();
            }
        }
    }

    /**
     * Append an all-air {@link VoxelColumnData.SectionData} for every section-Y in
     * {@code [minSectionY, minSectionY + levelSectionCount)} that the decoded column does not
     * already carry, so a resync overwrites ghost terrain the server cleared. A fresh all-air
     * {@link LevelChunkSection} per absent Y — {@code SectionConstruction.empty(factory)} is all-air
     * until written — with null light layers (dark), consistent with the absent-means-all-zero
     * light default.
     */
    /** Full-bright nibble template. Every DataLayer built from it takes a clone():
     *  DataLayer stores the array by reference and DataLayer.set() writes in place, and
     *  the same VoxelColumnData goes to EVERY registered consumer — one consumer mutating
     *  a shared array would corrupt the sky of every later fill section process-wide. */
    private static final byte[] FULL_BRIGHT_SKY = fullBrightNibbles();

    private static byte[] fullBrightNibbles() {
        byte[] b = new byte[2048];
        java.util.Arrays.fill(b, (byte) 0xFF);
        return b;
    }

    /**
     * Append an all-air section with FULL-BRIGHT sky light for every absent section-Y
     * ABOVE the column's top served section (up to the level top). Sky-lit dimensions
     * only — the caller gates on {@code hasSkyLight}; below/among the served band nothing
     * changes (unserved air there is dark air). MUST run before the resync air-fill —
     * {@code top} is derived from the sections present, so it is only the honest served
     * top while no synthetic fill has run; every Y above it is absent by definition
     * (which is also why this is a plain append with no per-Y presence check).
     */
    static VoxelColumnData.SectionData[] withImplicitSkyAbove(
            VoxelColumnData.SectionData[] present, int levelSectionCount, int minSectionY,
            PalettedContainerFactory factory) {
        if (present.length == 0) return present;   // a CLEAR stays a clear
        int top = Integer.MIN_VALUE;
        for (var s : present) top = Math.max(top, s.sectionY());
        int levelTop = minSectionY + levelSectionCount - 1;
        if (top >= levelTop) return present;
        var out = new java.util.ArrayList<VoxelColumnData.SectionData>(
                present.length + (levelTop - top));
        out.addAll(java.util.Arrays.asList(present));
        // Lower clamp: a server world deeper than the client level delivers sections below
        // minSectionY (a supported case — see decodeTruncatesSectionsBeyondLevelHeight);
        // fill must stay inside the client level like the air-fill does.
        for (int y = Math.max(top + 1, minSectionY); y <= levelTop; y++) {
            out.add(new VoxelColumnData.SectionData(y, dev.vox.lss.platform.SectionConstruction.empty(factory),
                    null, new DataLayer(FULL_BRIGHT_SKY.clone())));
        }
        return out.toArray(new VoxelColumnData.SectionData[0]);
    }

    static VoxelColumnData.SectionData[] withAirFilledAbsentSections(
            VoxelColumnData.SectionData[] present, int levelSectionCount, int minSectionY,
            PalettedContainerFactory factory, boolean brightSky) {
        var seen = new java.util.HashSet<Integer>(present.length * 2);
        for (var s : present) seen.add(s.sectionY());

        var out = new java.util.ArrayList<VoxelColumnData.SectionData>(levelSectionCount);
        java.util.Collections.addAll(out, present);
        for (int i = 0; i < levelSectionCount; i++) {
            int y = minSectionY + i;
            if (!seen.contains(y)) {
                // brightSky is set ONLY for the whole-column clear in a sky dimension —
                // below/among-band fills on a non-empty resync must stay dark.
                out.add(new VoxelColumnData.SectionData(y, dev.vox.lss.platform.SectionConstruction.empty(factory), null,
                        brightSky ? new DataLayer(FULL_BRIGHT_SKY.clone()) : null));
            }
        }
        return out.toArray(new VoxelColumnData.SectionData[0]);
    }

    /**
     * Resolve the shipped bytes to raw section bytes for decode. Codec 0 returns them
     * as-is. Codec 1 runs the decompression-bomb guard FIRST — the frame's declared
     * content size must be in {@code (0, MAX_SECTIONS_SIZE]} before any allocation —
     * then an exact-size zstd decompress whose output length must match the declaration
     * (the store's own integrity discipline). Anything else — unknown codec, undeclared
     * or lying size, truncated/corrupt frame, missing native — throws, which the drain
     * converts into an ingest-failure report for the column.
     */
    private static byte[] decompressForDecode(byte codec, byte[] shipped) {
        if (codec == dev.vox.lss.common.LSSConstants.COLUMN_CODEC_RAW) return shipped;
        if (codec != dev.vox.lss.common.LSSConstants.COLUMN_CODEC_ZSTD) {
            throw new IllegalStateException("unknown column codec " + codec);
        }
        long declared = dev.vox.lss.networking.payloads.ZstdWireSupport
                .declaredContentSize(shipped);
        if (declared <= 0 || declared > dev.vox.lss.common.LSSConstants.MAX_SECTIONS_SIZE) {
            throw new IllegalStateException("column frame declares " + declared
                    + " bytes (bomb guard: allowed 1.."
                    + dev.vox.lss.common.LSSConstants.MAX_SECTIONS_SIZE + ")");
        }
        byte[] raw = dev.vox.lss.networking.payloads.ZstdWireSupport
                .decompress(shipped, (int) declared);
        if (raw.length != declared) {
            throw new IllegalStateException("column frame decompressed to " + raw.length
                    + " bytes, declared " + declared);
        }
        return raw;
    }

    /**
     * True if these column bytes carry ZERO sections — the wire form of an authoritative
     * content-&gt;air CLEAR (the server sends it only to a data-claiming client). Reads just the
     * leading section-count varint. A malformed header reads as not-a-clear; the decode path
     * reports that column's failure separately.
     */
    static boolean isClearColumn(byte[] decompressed) {
        if (decompressed == null || decompressed.length == 0) return false;
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
        try {
            return buf.readVarInt() == 0;
        } catch (RuntimeException e) {
            return false;
        } finally {
            buf.release(); // heap wrapper — GC-safe either way, but leak detectors flag it
        }
    }

    /**
     * Decode one column's wire bytes into section data. The claimed section count never
     * sizes the allocation: it is clamped to {@code [0, levelSectionCount]} — supporting
     * tall/modded worlds up to the client level's height while capping what a hostile
     * peer can make us allocate. Sections beyond the clamp are silently truncated (their
     * bytes are left unread). Bytes that lie about their own content throw, which the
     * drain converts into an ingest-failure report for the column.
     */
    static VoxelColumnData.SectionData[] decodeSections(byte[] decompressed, int levelSectionCount,
                                                        PalettedContainerFactory factory) {
        var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(decompressed));
        try {
            int sectionCount = Math.max(0, Math.min(buf.readVarInt(), levelSectionCount));
            var sectionDatas = new VoxelColumnData.SectionData[sectionCount];

            for (int i = 0; i < sectionCount; i++) {
                int sectionY = buf.readByte();

                var section = dev.vox.lss.platform.SectionConstruction.empty(factory);
                section.read(buf);

                DataLayer blockLight = null;
                if (buf.readBoolean()) {
                    byte[] lightBytes = new byte[2048];
                    buf.readBytes(lightBytes);
                    blockLight = new DataLayer(lightBytes);
                }

                DataLayer skyLight = null;
                if (buf.readBoolean()) {
                    byte[] lightBytes = new byte[2048];
                    buf.readBytes(lightBytes);
                    skyLight = new DataLayer(lightBytes);
                }

                sectionDatas[i] = new VoxelColumnData.SectionData(
                        sectionY, section, blockLight, skyLight);
            }
            return sectionDatas;
        } finally {
            buf.release();
        }
    }

    /**
     * Drain columns still queued at disconnect and report each as an ingest failure so the
     * manager forgets their received-stamps BEFORE the cache flush — otherwise the cache
     * persists stamps for columns no consumer ever saw and the next session resyncs as
     * up-to-date over the holes. The epoch bump stops any in-progress drain at its next
     * check; a column it already polled still dispatches normally (and its stamp is then
     * truthful). Main client thread, from the DISCONNECT handler.
     */
    void reportUndispatched(LodRequestManager manager) {
        this.sessionEpoch++;
        manager.cancelOutstandingDeliveries();
        QueuedColumn queued;
        while ((queued = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-queued.charge());
            var payload = queued.payload();
            if (queued.delivery() == null) {
                manager.onIngestFailure(payload.dimension(),
                        PositionUtil.packPosition(payload.chunkX(), payload.chunkZ()));
            }
        }
    }

    /**
     * Bounded wait for the in-flight decode drain to finish (v0.11.0 stage D — the
     * /lss reset sequence). {@link #reportUndispatched} drains the QUEUE, but a column
     * the drain already polled "still dispatches normally" on the LSS-ColumnProcessor
     * thread — concurrently with the Voxy teardown that follows, where a late dispatch
     * can open a fresh store handle INSIDE the directory being wiped (the Windows
     * partial-delete trap). A polled column decodes in milliseconds, so this await is
     * cheap and closes the race deterministically. Main client thread; returns false on
     * timeout (the caller proceeds — the wipe is IO-contained regardless).
     */
    boolean awaitDecodeIdle(long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (this.processing.get()) {
            if (System.nanoTime() - deadline > 0) return false;
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** End the current session: any in-flight drain self-terminates at its next epoch check. */
    void shutdown() {
        var resolver = this.identityResolver;
        if (resolver != null && resolver.fallbackCount() > 0) {
            // The §3 fallbacks= operator signal (review C1-5): one summary line per
            // session, at teardown — the only client-side surface that needs no schema.
            dev.vox.lss.common.LSSLogger.info("Session identity fallbacks: "
                    + resolver.fallbackCount()
                    + " (cross-version content rendered as fallback blocks)");
        }
        this.identityResolver = null;
        this.identityResolverKey = null;
        this.sessionEpoch++;
        // Drain with the same poll+decrement discipline as the decode loop rather than
        // clear()+set(0): a set(0) races a decode iteration that already polled an item but
        // has not yet decremented, permanently driving the lifetime-singleton's queueSize to
        // -1 (drift accumulates across sessions). Pairing every poll with one decrement keeps
        // the counter self-consistent no matter which thread removes each item.
        QueuedColumn drained;
        while ((drained = this.columnQueue.poll()) != null) {
            this.queueSize.decrementAndGet();
            this.queuedBytes.addAndGet(-drained.charge());
        }
    }

    int getQueuedCount() { return this.queueSize.get(); }
    long getQueuedBytes() { return this.queuedBytes.get(); }
    long getColumnsDropped() { return this.columnsDropped.get(); }

    /** Current session epoch, for tests driving the drain loop directly. */
    int sessionEpochForTest() { return this.sessionEpoch; }

    void resetStats() {
        this.columnsDropped.set(0);
        this.lastDropWarnMs = 0;
    }
}
