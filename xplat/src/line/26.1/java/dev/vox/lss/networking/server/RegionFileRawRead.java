// OVERLAY OF xplat/src/main/java/dev/vox/lss/networking/server/RegionFileRawRead.java @ 4789751af23fd38888b66f80d454d2a5785119d241cd25edab00c3a54b9ac5df
// 26.1 line overlay (single-branch-consolidation-plan.md §3.2). Refresh the stamp when the shared file changes.
package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.LogThrottle;
import dev.vox.lss.mixin.AccessorRegionFile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The EXECUTOR-side half of the Phase 3 background-read split (perf-round plan R1):
 * resolve a chunk's raw compressed record — bytes, not streams — so the IOWorker's
 * single-threaded executor does only file IO, and inflate + NBT parse move to the LSS
 * reader pool ({@link NbtSectionSerializer#parseRawChunk}).
 *
 * <p>This mirrors vanilla's {@code RegionFile.getChunkDataInputStream} record-resolution
 * shapes exactly (26.2 bytecode, decompiled and verified — the shape census is in the
 * v0.10.0 progress doc's B3 recon entry; 26.1-line note: the R-7 re-port check found the
 * shadowed members and all three branches identical on 26.1.2 — Tier 2 byte parity
 * re-pins them per-line): offset-0, truncated header, missing stream,
 * truncated/negative stream all resolve {@code Optional.empty()} = authoritative
 * not-found, matching the {@code null} vanilla's read returns for them today. External
 * ({@code .mcc}) chunks are read into the buffer HERE, on the executor — file IO stays
 * on the executor, and reader/writer of a given {@code .mcc} stay serialized exactly as
 * today (the withdrawn v1 "lazy pool IO for externals" broke that serialization). All
 * sector/header arithmetic goes through {@link AccessorRegionFile}'s shadowed vanilla
 * helpers — nothing here re-derives the region format.
 *
 * <p>NOT loadable under fabric-loader-junit (it references the mixin-package accessor);
 * covered end-to-end by {@code SerializerParityGameTests}' disk-vs-live byte parity and
 * {@code RegionFaultGameTests}' corrupt-region containment. The corrupt-record warns are
 * throttled — vanilla logs these per read, and a corrupt column re-reads at the scan
 * cadence until its generation ticket serves it (#32-class flood otherwise).
 */
final class RegionFileRawRead {
    private RegionFileRawRead() {}

    private static final LogThrottle CORRUPT_WARN_THROTTLE = new LogThrottle(60_000);

    static Optional<NbtSectionSerializer.RawChunkRecord> read(RegionFile regionFile, ChunkPos pos)
            throws IOException {
        var acc = (AccessorRegionFile) (Object) regionFile;
        // Instance monitor mirroring vanilla's synchronized reader — but note (review C5)
        // the monitor covers getChunkDataInputStream and write only; clear/close/flush
        // are unsynchronized in vanilla. The LOAD-BEARING guarantee is executor
        // confinement: this runs inside the IOWorker's own consecutive-executor task,
        // the same sequencing point every vanilla load/save/clear/close uses — the
        // monitor is belt-and-braces on top.
        synchronized (regionFile) {
            int offset = acc.lss$getOffset(pos);
            if (offset == 0) {
                return Optional.empty();
            }
            int sectorNumber = AccessorRegionFile.lss$getSectorNumber(offset);
            int numSectors = AccessorRegionFile.lss$getNumSectors(offset);
            int sectorBytes = AccessorRegionFile.lss$sectorBytes();
            ByteBuffer buffer = ByteBuffer.allocate(numSectors * sectorBytes);
            acc.lss$getFile().read(buffer, (long) sectorNumber * sectorBytes);
            buffer.flip();
            if (buffer.remaining() < AccessorRegionFile.lss$chunkHeaderSize()) {
                return corrupt("truncated header for chunk " + pos);
            }
            int length = buffer.getInt();
            byte versionId = buffer.get();
            if (length == 0) {
                return corrupt("chunk " + pos + " is allocated but its stream is missing");
            }
            int streamLength = length - 1;
            if (AccessorRegionFile.lss$isExternalStreamChunk(versionId)) {
                // External .mcc — mirrored from vanilla incl. the dual-stream warn and
                // the external preference. The whole-file read happens HERE, under the
                // monitor and on the executor: bounded work holds for internal records
                // (<= 255 sectors); the external branch is unbounded exactly as it is in
                // vanilla, and reading it here closes the non-ATOMIC_MOVE replace window
                // the withdrawn lazy-pool-IO design would have re-opened (review C7).
                if (streamLength != 0) {
                    // Throttled like every other corrupt-region line in this file (the
                    // log sweep found this one bare — an affected chunk is re-read on
                    // every re-declaration).
                    long n = CORRUPT_WARN_THROTTLE.recordAndTryAcquire(
                            System.nanoTime() / 1_000_000);
                    if (n > 0) {
                        LSSLogger.warn("Chunk " + pos + " has both internal and external"
                                + " streams (" + n + " since the last report)");
                    }
                }
                Path external = acc.lss$getExternalChunkPath(pos);
                if (!Files.isRegularFile(external)) {
                    return corrupt("external chunk path " + external + " is not a file");
                }
                byte[] payload = Files.readAllBytes(external);
                return Optional.of(new NbtSectionSerializer.RawChunkRecord(
                        payload, AccessorRegionFile.lss$getExternalChunkVersion(versionId)));
            }
            if (streamLength > buffer.remaining()) {
                return corrupt("truncated stream for chunk " + pos);
            }
            if (streamLength < 0) {
                return corrupt("negative declared size for chunk " + pos);
            }
            byte[] payload = new byte[streamLength];
            buffer.get(payload);
            return Optional.of(new NbtSectionSerializer.RawChunkRecord(payload, versionId));
        }
    }

    private static Optional<NbtSectionSerializer.RawChunkRecord> corrupt(String detail) {
        long released = CORRUPT_WARN_THROTTLE.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (released > 0) {
            LSSLogger.warn("Raw region read: " + detail + " — resolved as not-found"
                    + (released > 1 ? " (+" + (released - 1) + " more)" : ""));
        }
        return Optional.empty();
    }
}
