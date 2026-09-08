package dev.vox.lss.paper;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.processing.ColumnBytes;
import dev.vox.lss.common.processing.OffThreadProcessor;
import dev.vox.lss.common.processing.QueuedPayload;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper-specific off-thread processor. Produces encoded byte[] payloads
 * that will be sent via Plugin Messaging on the main thread.
 */
public class PaperOffThreadProcessor extends OffThreadProcessor<PaperPlayerRequestState> {

    // Log-sweep hygiene (2026-08-13): per-column/per-event conditions aggregate to one
    // line/min — self-healing paths must not flood operator consoles.
    private static final dev.vox.lss.common.LogThrottle OVERSIZED_WARN =
            new dev.vox.lss.common.LogThrottle(60_000);
    private final PaperChunkDiskReader diskReader;

    // Maps a dimension id to its live ServerLevel for disk-read submission. Refreshed every
    // tick (put, not putIfAbsent): a Paper world unloaded and recreated under the same name
    // (Multiverse/arena resets) reuses the dimension id, so a stale putIfAbsent entry would
    // aim every disk read at the dead world's closed ChunkMap (mass not-found). Cleared on
    // shutdown. (Residual: a permanently-unloaded, never-recreated world's level stays until
    // shutdown — bounded, and vanilla dimensions never unload.)
    private final ConcurrentHashMap<String, ServerLevel> dimensionLevelMap = new ConcurrentHashMap<>();

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    boolean generationAvailable,
                                    Path dataDir, int perDimensionTimestampCacheSizeMB,
                                    int missMemoTtlSeconds) {
        super(players,
                diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds);
        this.diskReader = diskReader;
    }

    public PaperOffThreadProcessor(Map<UUID, PaperPlayerRequestState> players,
                                    PaperChunkDiskReader diskReader,
                                    boolean generationAvailable,
                                    Path dataDir, int perDimensionTimestampCacheSizeMB,
                                    int missMemoTtlSeconds, int diskReadDoneSweepRadiusChunks) {
        super(players,
                diskReader, generationAvailable, dataDir, perDimensionTimestampCacheSizeMB,
                missMemoTtlSeconds, diskReadDoneSweepRadiusChunks);
        this.diskReader = diskReader;
    }

    // The session-dialect source for the C2 legacy egress translation (XVER §4.2, placed
    // at the ENQUEUE choke point — twin of the Fabric processor's field). Attached by the
    // service after construction; defaults to an EMPTY tracker (every session CURRENT)
    // rather than null so the branch never NPE-branches — but note an unattached tracker
    // fails toward shipping v20 bodies to legacy clients, which is why the attach call
    // is source-pinned (PaperLegacyEgressTest, review MAJOR-1). Volatile: written at
    // service init, read on the processing thread.
    private volatile dev.vox.lss.common.compat.WireDialectTracker dialects =
            new dev.vox.lss.common.compat.WireDialectTracker();
    /** Warn-once latch for legacy egress translation failures (processing thread only). */
    private boolean legacyTranslateWarned;

    public void attachDialectTracker(dev.vox.lss.common.compat.WireDialectTracker dialects) {
        this.dialects = dialects;
    }

    public void updateDimensionContext(String dimension, ServerLevel level) {
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
    protected boolean buildAndEnqueueColumnPayload(PaperPlayerRequestState state, int cx, int cz,
                                                    String dimension,
                                                    long columnTimestamp, long submissionOrder,
                                                    ColumnBytes bytes, int estimatedBytes,
                                                    byte source) {
        // RAW-size guard (twin of the Fabric build; load-bearing for store-frame hits
        // whose rows can legally exceed the send cap — plan §3).
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
            // Drop just this column (like an oversized one): without the guard
            // encodeVoxelColumnPreEncoded's writeUtf throws out of this method and aborts the
            // WHOLE processing cycle. No real dimension id is this long; the !sent path answers
            // the client up-to-date so it stops asking.
            long dn = OVERSIZED_WARN.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (dn > 0) {
                LSSLogger.warn("Dropping column [" + cx + ", " + cz + "] with oversized dimension id ("
                        + dimension.length() + " chars > " + LSSConstants.MAX_DIMENSION_STRING_LENGTH
                        + ") (" + dn + " oversized drop(s) since the last report)");
            }
            return false;
        }
        // C2 legacy egress translation (XVER §4.2), at THIS per-recipient choke point —
        // twin of the Fabric build, same rationale: every queued size (gauges, bandwidth
        // budget, diag books, soak law A2) must derive from the bytes the legacy client
        // actually decodes, and the CPU lands on the processing thread. The pump's
        // routeColumnFrame keeps only the v18/v16 HEADER splices.
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
            byte[] legacyEncoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                    cx, cz, dimension, columnTimestamp, source, build.codecTag(), build.shipped());
            state.addReadyPayload(new QueuedPayload<>(legacyEncoded,
                    build.rawSize() + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    build.shipped().length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES,
                    submissionOrder, PositionUtil.packPosition(cx, cz)));
            getDiagnostics().incrementColumnCodec(build.codecTag() == LSSConstants.COLUMN_CODEC_ZSTD);
            // Probe hashes stay v20-denominated (the native branch's bytes.raw() too):
            // probe verdicts are server-side cross-leg CONTENT comparisons. Under the
            // dialect lever the recorded hash is deliberately not the delivered bytes —
            // any future client-side probe under SOAK_DIALECT must account for that.
            if (PaperSoakProbeBridge.armed()) PaperSoakProbeBridge.recordServed(cx, cz, bytes.raw());
            return true;
        }

        // Per-recipient codec choice off the shared holder — twin of the Fabric build:
        // frame() only for capable sessions, memoized across the dedup fan-out; a v16
        // session's flag is derived false at registration, so its frames encode raw and
        // the egress splice stays two-byte-removable.
        byte[] frame = state.wantsCompressedColumns() ? bytes.frame() : null;
        byte codecTag = frame != null ? LSSConstants.COLUMN_CODEC_ZSTD
                : LSSConstants.COLUMN_CODEC_RAW;
        byte[] shipped = frame != null ? frame : bytes.raw();
        byte[] encoded = PaperPayloadHandler.encodeVoxelColumnPreEncoded(
                cx, cz, dimension, columnTimestamp, source, codecTag, shipped);
        int wireBytes = shipped.length + LSSConstants.ESTIMATED_COLUMN_OVERHEAD_BYTES;
        state.addReadyPayload(new QueuedPayload<>(encoded, estimatedBytes, wireBytes,
                submissionOrder, PositionUtil.packPosition(cx, cz)));
        getDiagnostics().incrementColumnCodec(frame != null);
        // Soak probe hashes (dev-only, no-op unless -Dlss.soak.probes): the RAW bytes —
        // pinned (plan §0.6); the armed() gate keeps the unarmed production path from
        // materializing raw for it. Twin of the Fabric hook.
        if (PaperSoakProbeBridge.armed()) PaperSoakProbeBridge.recordServed(cx, cz, bytes.raw());
        return true;
    }

    /** Translate a v20 raw body for a legacy session and choose its codec (textual twin
     *  of the Fabric build): v19 sessions keep their compression capability (recompress,
     *  gated by the same min-bytes + must-shrink rules as the shared holder's frame() —
     *  incl. keeping the 1-byte ghost clear structurally raw); v18/v16 sessions arrive
     *  forced-RAW. Throws on any malformed/unresolvable body AND on a translated body
     *  over the send cap (review MAJOR-3: the enqueue guard checked the V20 size, but
     *  native can be LARGER — wide-palette sections repack from v20's ≤12-bit dictionary
     *  indices to native DIRECT at ~15-16 registry bits — and an over-cap body kills the
     *  legacy client's connection at readByteArray). The caller contains every throw as
     *  up_to_date. */
    static dev.vox.lss.common.processing.LegacyColumnBuild buildLegacyColumn(byte[] v20Raw,
                                                     net.minecraft.core.RegistryAccess registryAccess,
                                                     boolean wantsCompressed,
                                                     dev.vox.lss.common.store.StoreCodec zstd) {
        byte[] nativeBody = PaperNbtSectionSerializer.fromV20(v20Raw, registryAccess);
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
