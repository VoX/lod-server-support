package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ColumnBytes;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-specific off-thread processor. Produces CustomPacketPayload objects
 * that will be sent via Fabric networking on the main thread.
 */
public class FabricOffThreadProcessor extends OffThreadProcessor<PlayerRequestState> {

    // Log-sweep hygiene (2026-08-13): per-column/per-event conditions aggregate to one
    // line/min — self-healing paths must not flood operator consoles.
    private static final dev.vox.lss.common.LogThrottle OVERSIZED_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    private final ChunkDiskReader diskReader;

    // Stored references for disk read submission. Grows but never prunes — acceptable because
    // vanilla only has 3 permanent dimensions, and the map is cleared on shutdown.
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    // Cache parsed ResourceKeys to avoid Identifier.parse per column payload (3 entries for vanilla)
    private final ConcurrentHashMap<String, ResourceKey<Level>> dimensionKeyCache = new ConcurrentHashMap<>();

    // The session-dialect source for the C2 legacy egress translation (XVER §4.2, placed
    // at the ENQUEUE choke point — see buildAndEnqueueColumnPayload). Attached by the
    // service after construction; defaults to an EMPTY tracker (every session CURRENT)
    // rather than null so the branch never NPE-branches — but note an unattached tracker
    // fails toward shipping v20 bodies to legacy clients, which is why the attach call
    // is source-pinned (LegacyColumnEgressTest, review MAJOR-1). Volatile: written
    // main-thread at service init, read on the processing thread.
    private volatile dev.vox.lss.common.compat.WireDialectTracker dialects =
            new dev.vox.lss.common.compat.WireDialectTracker();
    /** Warn-once latch for legacy egress translation failures (processing thread only). */
    private boolean legacyTranslateWarned;

    public void attachDialectTracker(dev.vox.lss.common.compat.WireDialectTracker dialects) {
        this.dialects = dialects;
    }

    public FabricOffThreadProcessor(Map<UUID, PlayerRequestState> players,
                                     ChunkDiskReader diskReader,
                                     boolean generationAvailable,
                                     Path dataDir, int perDimensionTimestampCacheSizeMB,
                                     int missMemoTtlSeconds) {
        super(players, diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds);
        this.diskReader = diskReader;
    }

    public FabricOffThreadProcessor(Map<UUID, PlayerRequestState> players,
                                     ChunkDiskReader diskReader,
                                     boolean generationAvailable,
                                     Path dataDir, int perDimensionTimestampCacheSizeMB,
                                     int missMemoTtlSeconds, int diskReadDoneSweepRadiusChunks) {
        super(players, diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds, diskReadDoneSweepRadiusChunks);
        this.diskReader = diskReader;
    }

    /** Register a dimension context for disk read submission (called from main thread). */
    public void updateDimensionContext(String dimension, ServerLevel level) {
        // put, not putIfAbsent: refresh to the current ServerLevel so a recreated dimension
        // (e.g. an integrated world re-published to LAN) doesn't leave a stale level cached.
        // Matches PaperOffThreadProcessor.updateDimensionContext so the twins can't drift.
        this.dimensionLevelMap.put(dimension, level);
    }

    @Override
    protected boolean submitDiskRead(UUID playerUuid, RequestRegistration registration, String dimension,
                                   int cx, int cz,
                                   long submissionOrder, long clientTimestamp) {
        if (this.diskReader == null || registration.isRetired()) return false;
        var level = this.dimensionLevelMap.get(dimension);
        if (level == null) {
            LSSLogger.debug("No dimension context for " + dimension + ", skipping disk read for " + cx + "," + cz);
            return false;
        }
        this.diskReader.submitReadDirect(playerUuid, registration, dimension, level,
                cx, cz, submissionOrder, clientTimestamp);
        return true;
    }

    @Override
    protected boolean buildAndEnqueueColumnPayload(PlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    ColumnBytes bytes, int estimatedBytes,
                                                    byte source) {
        // The guard checks RAW size (the client decode cap is raw-denominated; a codec-1
        // frame is strictly smaller than its raw or the holder refuses it — INCLUDING
        // pre-built store frames, whose non-shrinking degenerates ship raw) — load-bearing
        // for store-frame hits too, whose rows can legally exceed the send cap (plan §3).
        if (bytes.rawSize() > LSSConstants.MAX_SEND_SECTIONS_SIZE) {
            {
            long n = OVERSIZED_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (n > 0) LSSLogger.warn("Dropping oversized column [" + cx + ", " + cz + "] in " + dimension
                    + ": " + bytes.rawSize() + " bytes exceeds send limit "
                    + LSSConstants.MAX_SEND_SECTIONS_SIZE + " (netty frame cap would kill the connection)"
                    + " (" + n + " oversized drop(s) since the last report — the client"
                    + " re-asks and is answered up-to-date)");
            return false;
        }
        }
        if (dimension.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) {
            // Drop just this column (like an oversized one) rather than letting writeUtf throw
            // at send time and nuke the whole send queue. No real dimension id is this long;
            // the !sent path answers the client up-to-date so it stops asking.
            long dn = OVERSIZED_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (dn > 0) {
                LSSLogger.warn("Dropping column [" + cx + ", " + cz + "] with oversized dimension id ("
                        + dimension.length() + " chars > " + LSSConstants.MAX_DIMENSION_STRING_LENGTH
                        + ") (" + dn + " oversized drop(s) since the last report)");
            }
            return false;
        }
        var dimensionKey = this.dimensionKeyCache.computeIfAbsent(dimension,
                d -> ResourceKey.create(Registries.DIMENSION, Identifier.parse(d)));

        // C2 legacy egress translation (XVER §4.2), placed at THIS per-recipient choke
        // point rather than the flush seam so every downstream size — queue gauges,
        // bandwidth budget, diag books, soak law A2 — derives from the bytes the legacy
        // client actually decodes (the first dialect-19 soak redded A2 by exactly the
        // v20-vs-native rawSize delta when translation ran at flush), and the CPU lands
        // on the processing thread instead of main. The flush ladder keeps only the
        // v18/v16 HEADER splices. A dialect flip between enqueue and flush ships one
        // queue of wrong-layout bodies — client decode fails, ingest-failure re-declares,
        // the retranslated serves heal it (the same bounded window as the codec-1
        // downgrade guard at the splices).
        boolean legacySession = this.dialects.dialectOf(state.getPlayerUUID())
                != dev.vox.lss.common.HandshakeGate.WireDialect.CURRENT;
        if (legacySession) {
            var level = this.dimensionLevelMap.get(dimension);
            dev.vox.lss.common.processing.LegacyColumnBuild build;
            try {
                if (level == null) {
                    throw new IllegalStateException("no dimension context for " + dimension);
                }
                // Memoized on the shared holder (review MAJOR-2): a dedup fan-out costs
                // ONE translate per column, never one per recipient.
                boolean wantsCompressed = state.wantsCompressedColumns();
                build = bytes.legacyBuild(wantsCompressed, () -> buildLegacyColumn(
                        bytes.raw(), level.registryAccess(), wantsCompressed, wireCodec()));
            } catch (Exception e) {
                if (!this.legacyTranslateWarned) {
                    this.legacyTranslateWarned = true;
                    LSSLogger.error("legacy-compat: column build refused for "
                            + state.getPlayerName() + " — resolving up_to_date (a persistent "
                            + "failure here is a registry-table bug or an over-limit "
                            + "translation; further failures are silent)", e);
                }
                // false = the oversized-column semantics: the caller answers up_to_date
                // (or, on the all-air clear path, skips the clear — the client keeps its
                // stale view until a dirty broadcast or rejoin; acceptable because this
                // is a persistent-bug containment, not a per-column condition).
                return false;
            }
            var legacyPayload = new VoxelColumnS2CPayload(cx, cz, dimensionKey,
                    columnTimestamp, source, build.codecTag(), build.shipped(), build.rawSize());
            state.addReadyPayload(new QueuedPayload<>(legacyPayload,
                    build.rawSize() + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    build.shipped().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    submissionOrder, PositionUtil.packPosition(cx, cz)));
            getDiagnostics().incrementColumnCodec(build.codecTag() == LSSConstants.COLUMN_CODEC_ZSTD);
            // Probe hashes stay v20-denominated (the native branch's bytes.raw() too):
            // probe verdicts are server-side cross-leg CONTENT comparisons. Under the
            // dialect lever the recorded hash is deliberately not the delivered bytes —
            // any future client-side probe under SOAK_DIALECT must account for that.
            if (SoakProbeBridge.armed()) SoakProbeBridge.recordServed(cx, cz, bytes.raw());
            return true;
        }

        // Per-recipient codec choice off the shared holder (plan §0.3/§0.4): frame() is
        // asked only for capable sessions and memoizes across the dedup fan-out; null
        // means "ship raw" (no codec, below threshold, or the frame didn't shrink).
        byte[] frame = state.wantsCompressedColumns() ? bytes.frame() : null;
        byte codecTag = frame != null ? LSSConstants.COLUMN_CODEC_ZSTD
                : LSSConstants.COLUMN_CODEC_RAW;
        byte[] shipped = frame != null ? frame : bytes.raw();
        var payload = new VoxelColumnS2CPayload(cx, cz, dimensionKey, columnTimestamp,
                source, codecTag, shipped, bytes.rawSize());
        int wireBytes = shipped.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        state.addReadyPayload(new QueuedPayload<>(payload, estimatedBytes, wireBytes,
                submissionOrder, PositionUtil.packPosition(cx, cz)));
        getDiagnostics().incrementColumnCodec(frame != null);
        // Soak probe hashes (dev-only, no-op unless -Dlss.soak.probes): the RAW bytes —
        // pinned (plan §0.6): probes compare CONTENT across serve legs, and the armed()
        // gate keeps the unarmed production path from ever materializing raw for it.
        if (SoakProbeBridge.armed()) SoakProbeBridge.recordServed(cx, cz, bytes.raw());
        return true;
    }

    /** Translate a v20 raw body for a legacy session and choose its codec: v19 sessions
     *  keep their compression capability (recompress, gated by the same min-bytes +
     *  must-shrink rules as the shared holder's frame() — incl. keeping the 1-byte
     *  ghost clear structurally raw); v18/v16 sessions arrive forced-RAW. Throws on any
     *  malformed/unresolvable body AND on a translated body over the send cap (review
     *  MAJOR-3: the enqueue guard checked the V20 size, but native can be LARGER —
     *  wide-palette sections repack from v20's ≤12-bit dictionary indices to native
     *  DIRECT at ~15-16 registry bits — and an over-cap body kills the legacy client's
     *  connection at readByteArray). The caller contains every throw as up_to_date. */
    static dev.vox.lss.common.processing.LegacyColumnBuild buildLegacyColumn(byte[] v20Raw,
                                               net.minecraft.core.RegistryAccess registryAccess,
                                               boolean wantsCompressed,
                                               dev.vox.lss.common.store.StoreCodec zstd) {
        byte[] nativeBody = NbtSectionSerializer.fromV20(v20Raw, registryAccess);
        if (nativeBody.length > LSSConstants.MAX_SEND_SECTIONS_SIZE) {
            throw new IllegalStateException("translated column body " + nativeBody.length
                    + " bytes exceeds send limit " + LSSConstants.MAX_SEND_SECTIONS_SIZE
                    + " (v20 body was " + v20Raw.length + " — the admission guard's size)");
        }
        if (wantsCompressed && zstd != null
                && nativeBody.length >= LSSConstants.COLUMN_COMPRESS_MIN_BYTES) {
            byte[] frame = zstd.compress(nativeBody);
            if (frame.length < nativeBody.length) {
                return new dev.vox.lss.common.processing.LegacyColumnBuild(frame,
                        LSSConstants.COLUMN_CODEC_ZSTD, nativeBody.length);
            }
        }
        return new dev.vox.lss.common.processing.LegacyColumnBuild(nativeBody,
                LSSConstants.COLUMN_CODEC_RAW, nativeBody.length);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        this.dimensionLevelMap.clear();
    }
}
