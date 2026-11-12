package dev.vox.lss.test;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.server.RequestProcessingService;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FP-027: real corrupt-region-file tolerance through the live disk-read pipeline. A region
 * file with a VALID header but garbage zlib chunk data is read through the server's own
 * region storage. On this line the default read fetches the raw record and inflates it on
 * the reader pool, where the garbage fails ({@code ZipException}) and the read resolves
 * as ERROR; other read rungs and other Minecraft lines can label the same read not-found.
 * (The file is staged under {@code <world>/region}, the Overworld's own region folder on
 * this line, so the corrupt record was always read here. 26.2 and later keep the
 * Overworld's regions elsewhere and never read that folder: there the "corrupt" read was
 * a missing region until 2026-11-12, and the not-found label recorded for 26.x came from
 * that.)
 * What FP-027 actually guarantees, and what this test pins, is CONTAINMENT: the corrupt
 * read completes through the pool without going thread-fatal, frees its slot, leaks
 * nothing, and the SAME reader pool then serves a valid disk read. The
 * error-vs-not-found classification is a vanilla-internal detail, so the assertion
 * accepts EITHER label (V-1/T3b, adopted from the support branches — where the 1.21.11
 * line confirmed the error flavor empirically). A failure of this test is now a REAL
 * containment/pool regression, never a label flake.
 *
 * <p>The corrupt chunk sits ~1900 chunks away in a region no IO worker has ever opened
 * (region file handles are cached per region; writing under an open handle would race).
 * Requests are seeded as one declared want-set via {@link GameTestSeeding} — the distance
 * guard is not under test.
 *
 * <p>The second test stages a VALID region file holding a FULL chunk whose blocks this
 * server does not know (undecodable-chunks plan, 2026-11-12) and pins the end-to-end
 * rule: no air answer, no generation ticket, the chunk never loaded and its region file
 * left byte-identical.
 */
public class RegionFaultGameTests {

    private static final int VALID_CHUNK_OFFSET = 220;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    /** How many batch-response statuses of {@code type} for {@code packed} were written
     *  to the mock player's (embedded) channel. */
    private static int countStatuses(net.minecraft.server.level.ServerPlayer player, byte type, long packed) {
        var connection = ((dev.vox.lss.mixin.AccessorServerCommonPacketListener) player.connection)
                .lss$getConnection();
        var channel = (io.netty.channel.embedded.EmbeddedChannel)
                ((dev.vox.lss.mixin.AccessorConnection) connection).lss$getChannel();
        int count = 0;
        for (Object message : channel.outboundMessages()) {
            if (message instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet
                    && packet.payload() instanceof dev.vox.lss.networking.payloads.BatchResponseS2CPayload batch) {
                for (int i = 0; i < batch.count(); i++) {
                    if (batch.responseTypes()[i] == type && batch.packedPositions()[i] == packed) count++;
                }
            }
        }
        return count;
    }

    // Own batch: unrelated generation/save traffic must not consume the disk-read deadline.
    @GameTest(environment = "lss:region_fault", structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void corruptRegionChunkResolvesAsContainedErrorAndReaderSurvives(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();

        // Region-aligned corrupt chunk (local index 0 in its region), far west/south of
        // anything any test or the spawn ever touches.
        int corruptCx = ((pcx >> 5) - 60) << 5;
        int corruptCz = ((pcz >> 5) + 60) << 5;
        long corruptPacked = PositionUtil.packPosition(corruptCx, corruptCz);
        try {
            // LINE FLAVOR: <world>/region is the Overworld's own region folder on this
            // line. (26.2 moved it under dimensions/, and a file staged here was then
            // never read: the "corrupt" read was a plain missing region, and the test
            // pinned nothing about corruption.) The raw-serves assertion below fails if
            // this ever stops being the folder the server reads.
            var regionDir = server.getWorldPath(LevelResource.ROOT).resolve("region");
            Files.createDirectories(regionDir);
            var regionFile = regionDir.resolve(
                    "r." + (corruptCx >> 5) + "." + (corruptCz >> 5) + ".mca");
            // 8 KiB header (entry 0: offset sector 2, 1 sector; timestamps zero) + one data
            // sector: declared length 255, compression 2 (zlib), garbage bytes — the header
            // parses, the inflate throws.
            var bytes = ByteBuffer.allocate(3 * 4096);
            bytes.putInt(0, (2 << 8) | 1);
            bytes.position(2 * 4096);
            bytes.putInt(255);
            bytes.put((byte) 2);
            for (int i = 0; i < 254; i++) {
                bytes.put((byte) 0xDE);
            }
            Files.write(regionFile, bytes.array());
        } catch (Exception e) {
            Gt.fail(helper, "could not stage the corrupt region file: " + e);
            return;
        }

        // Valid disk target: generated now, then unloaded + saved so its serve hits disk
        // through the same reader pool the corrupt read errors on.
        var validPos = new ChunkPos(pcx - VALID_CHUNK_OFFSET, pcz + 2);
        long validPacked = PositionUtil.packPosition(validPos.x, validPos.z);
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, validPos, 0);
        level.getChunk(validPos.x, validPos.z);
        helper.runAfterDelay(4, () ->
                chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, validPos, 0));

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            Gt.assertTrue(helper, helper.getTick() >= 6, "waiting for the ticket release");
            if (step.get() == 0) {
                Gt.assertTrue(helper, chunkSource.getChunkNow(validPos.x, validPos.z) == null,
                        "waiting for the valid chunk to unload");
                level.save(null, true, false);
                // ONE batch: two sequential offers would supersede the corrupt position and
                // the pool-survival premise (both reads through the same pool) would evaporate.
                GameTestSeeding.seedRequests(state,
                        new long[]{corruptPacked, validPacked}, new long[]{-1L, -1L});
                step.set(1);
                Gt.assertTrue(helper, false, "requests queued, awaiting both disk resolutions");
            }
            service.tick();
            var diskDiag = service.getDiskReader().getDiag();
            // Containment: both reads ran through the same pool to completion; the corrupt one
            // resolved empty (error or not-found) without saturating, and the valid read still
            // served — proof the pool survived the corrupt read intact. This is the FP-027
            // guarantee; the not-found vs error label depends on the read rung and the line.
            Gt.assertTrue(helper, diskDiag.getSubmittedCount() == 2 && diskDiag.getCompletedCount() == 2
                            && diskDiag.getSuccessfulReadCount() == 1 && state.getTotalSectionsSent() == 1
                            && (diskDiag.getNotFoundCount() + diskDiag.getErrorCount()) == 1
                            && diskDiag.getSaturationCount() == 0,
                    "waiting for both reads to complete with the corrupt one contained as an "
                            + "empty resolution (not-found or error) and the valid one served (errors=" + diskDiag.getErrorCount()
                            + " success=" + diskDiag.getSuccessfulReadCount()
                            + " sent=" + state.getTotalSectionsSent()
                            + " submitted=" + diskDiag.getSubmittedCount()
                            + " completed=" + diskDiag.getCompletedCount()
                            + " not_found=" + diskDiag.getNotFoundCount()
                            + " saturated=" + diskDiag.getSaturationCount() + ")");
            // The corrupt record was really READ: the raw-path counter moves only for a
            // record that is present in the region file, so 2 is the valid chunk plus the
            // corrupt one. A corrupt file staged where the server does not look leaves it
            // at 1 while every assertion above still holds.
            Gt.assertTrue(helper, service.getDiskReader().rawServesForTest() == 2,
                    "both the valid and the CORRUPT record must be fetched from their region"
                            + " files (raw_serves=" + service.getDiskReader().rawServesForTest()
                            + "): the corrupt file must sit where the server reads it");
            Gt.assertTrue(helper, !state.hasDiskReadDone(corruptCx, corruptCz),
                    "a not-found read must not mark the position done — the client's "
                            + "NOT_GENERATED retry path must stay open");
            Gt.assertTrue(helper, state.getHeldSyncSlots() == 0,
                    "the contained corrupt read must free its sync slot (no pending leak)");
            Gt.assertTrue(helper, service.getDiskReader().getPendingResultCount() == 0,
                    "no undrained results may linger after both resolutions");
            service.shutdown();
            playerList.remove(mock);
        });
    }

    /**
     * An undecodable chunk through the live pipeline, generation AVAILABLE: a FULL chunk on
     * disk whose only block is unknown to this server (a removed mod's block, or a chunk
     * format this build cannot read). Before the fix the disk path serialized it as air:
     * booked {@code all_air}, stamped, and answered as an empty column. A plain miss
     * would be worse: it escalates to a generation ticket, and the vanilla load turns the
     * unknown block into air and may save the chunk. Pinned here: the read resolves
     * UNSERVABLE (counted {@code undecodable} under {@code not_found}), the requester is
     * answered without any column or up_to_date, nothing is generated or left pending,
     * the chunk is never loaded, and the region file is byte-identical afterwards.
     * (The gametest tiers run with the LOD store off, so "no store row" is pinned at the
     * processor in {@code OffThreadProcessorStoreTest}.)
     */
    // Own environment, so own batch: the read must not share its ten-second deadline with
    // another test's generation or save traffic (the reason the test above has one too).
    @GameTest(environment = "lss:undecodable_chunk", structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void undecodableChunkIsNeverServedAsAirNorGenerated(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var mock = placeMockServerPlayer(helper);
        int pcx = mock.getBlockX() >> 4;
        int pcz = mock.getBlockZ() >> 4;
        var chunkSource = level.getChunkSource();

        // Region-aligned (local index 0), in a region nothing else ever opens — two
        // regions past the corrupt-region test's.
        int cx = ((pcx >> 5) - 62) << 5;
        int cz = ((pcz >> 5) + 62) << 5;
        long packed = PositionUtil.packPosition(cx, cz);
        java.nio.file.Path regionFile;
        byte[] staged;
        try {
            var chunk = new net.minecraft.nbt.CompoundTag();
            chunk.putInt("DataVersion",
                    net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
            chunk.putInt("xPos", cx);
            chunk.putInt("zPos", cz);
            chunk.putInt("yPos", level.getMinSectionY());
            chunk.putString("Status", "minecraft:full");
            var section = new net.minecraft.nbt.CompoundTag();
            section.putByte("Y", (byte) 0);
            var blockStates = new net.minecraft.nbt.CompoundTag();
            var palette = new net.minecraft.nbt.ListTag();
            var unknown = new net.minecraft.nbt.CompoundTag();
            unknown.putString("Name", "lss:removed_mod_block");
            palette.add(unknown);
            blockStates.put("palette", palette);
            section.put("block_states", blockStates);
            var sections = new net.minecraft.nbt.ListTag();
            sections.add(section);
            chunk.put("sections", sections);

            var compressed = new java.io.ByteArrayOutputStream();
            try (var out = new java.io.DataOutputStream(
                    new java.util.zip.DeflaterOutputStream(compressed))) {
                net.minecraft.nbt.NbtIo.write(chunk, out);
            }
            byte[] payload = compressed.toByteArray();
            if (payload.length > 4096 - 5) {
                Gt.fail(helper, "staged chunk does not fit one sector: " + payload.length);
                return;
            }
            // 8 KiB header (entry 0: offset sector 2, 1 sector) + one data sector:
            // length (payload + the compression byte), compression 2 (zlib), payload.
            var bytes = ByteBuffer.allocate(3 * 4096);
            bytes.putInt(0, (2 << 8) | 1);
            bytes.position(2 * 4096);
            bytes.putInt(payload.length + 1);
            bytes.put((byte) 2);
            bytes.put(payload);
            staged = bytes.array();
            // The level's own region folder (<world>/region for the Overworld on this
            // line; 26.2 moved it under dimensions/): a file staged anywhere else is
            // never read, and the read would resolve as a plain missing chunk.
            var regionDir = net.minecraft.world.level.dimension.DimensionType
                    .getStorageFolder(level.dimension(),
                            server.getWorldPath(LevelResource.ROOT).normalize())
                    .resolve("region");
            Files.createDirectories(regionDir);
            regionFile = regionDir.resolve("r." + (cx >> 5) + "." + (cz >> 5) + ".mca");
            Files.write(regionFile, staged); // replaces a previous run's copy (the run dir persists)
        } catch (Exception e) {
            Gt.fail(helper, "could not stage the undecodable region file: " + e);
            return;
        }

        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            Gt.assertTrue(helper, service.getGenerationService() != null,
                    "premise: generation is AVAILABLE — the miss ladder would escalate");
            if (step.get() == 0) {
                GameTestSeeding.seedRequest(state, packed, -1L);
                step.set(1);
                Gt.assertTrue(helper, false, "request queued, awaiting the disk resolution");
            }
            service.tick();
            var diskDiag = service.getDiskReader().getDiag();
            var diag = service.getOffThreadProcessor().getDiagnostics();
            if (diskDiag.getCompletedCount() == 0 || step.get() > 1) {
                // Wall-denominate the waits (the TwoPlayerGameTests fix): an unthrottled
                // gametest server burns its timeout ticks in well under a second, but the
                // read is real disk work, and the settle window below must outlast a
                // (wrong) generation ticket reaching the service.
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Gt.assertTrue(helper, diskDiag.getCompletedCount() == 1 && diskDiag.getUndecodableCount() == 1
                            && state.getHeldSyncSlots() == 0 && !state.hasPendingRequest(cx, cz),
                    "waiting for the read to resolve undecodable and its slot to free (completed="
                            + diskDiag.getCompletedCount()
                            + " undecodable=" + diskDiag.getUndecodableCount()
                            + " not_found=" + diskDiag.getNotFoundCount()
                            + " all_air=" + diskDiag.getAllAirCount()
                            + " errors=" + diskDiag.getErrorCount()
                            + " held_sync=" + state.getHeldSyncSlots() + ")");
            // A few more ticks so a (wrong) generation ticket or follow-up would surface.
            Gt.assertTrue(helper, step.incrementAndGet() > 6, "settling after the resolution");

            Gt.assertTrue(helper, diskDiag.getSubmittedCount() == 1 && diskDiag.getNotFoundCount() == 1
                            && diskDiag.getAllAirCount() == 0 && diskDiag.getSuccessfulReadCount() == 0
                            && diskDiag.getErrorCount() == 0,
                    "exactly one read, booked undecodable under not_found: never all-air, never"
                            + " served, never an error");
            Gt.assertTrue(helper, service.getDiskReader().getDiagnostics().contains("undecodable=1"),
                    "the disk diag line carries the token: " + service.getDiskReader().getDiagnostics());
            Gt.assertTrue(helper, state.getTotalSectionsSent() == 0 && diag.getTotalUpToDate() == 0,
                    "no column and no up_to_date: never an air answer (sent="
                            + state.getTotalSectionsSent() + " up_to_date=" + diag.getTotalUpToDate() + ")");
            Gt.assertTrue(helper, diag.getTotalSuperseded() == 0 && diag.getTotalMissDropped() == 0,
                    "the miss was ANSWERED (not available), not dropped");
            // ...and answered exactly once, with exactly that: read off the wire the mock
            // player's channel recorded (the server keeps no not_generated counter).
            int notGenerated = countStatuses(mock, LSSConstants.RESPONSE_NOT_GENERATED, packed);
            int upToDate = countStatuses(mock, LSSConstants.RESPONSE_UP_TO_DATE, packed);
            Gt.assertTrue(helper, notGenerated == 1 && upToDate == 0,
                    "exactly one NOT_GENERATED status for the position and no up_to_date"
                            + " (not_generated=" + notGenerated + " up_to_date=" + upToDate + ")");
            Gt.assertTrue(helper, service.getGenerationService().getTotalSubmitted() == 0
                            && state.getHeldGenSlots() == 0,
                    "an undecodable chunk must never become a generation ticket (submitted="
                            + service.getGenerationService().getTotalSubmitted() + ")");
            Gt.assertTrue(helper, !state.hasDiskReadDone(cx, cz), "nothing was served, nothing marked done");
            Gt.assertTrue(helper, chunkSource.getChunkNow(cx, cz) == null,
                    "the chunk must never be loaded: vanilla would turn its unknown block into air");
            Gt.assertTrue(helper, service.getDiskReader().getPendingResultCount() == 0,
                    "no undrained results may linger");
            try {
                Gt.assertTrue(helper, Arrays.equals(staged, Files.readAllBytes(regionFile)),
                        "the region file on disk is byte-identical: the world was not touched");
            } catch (java.io.IOException e) {
                Gt.fail(helper, "could not re-read the staged region file: " + e);
            }
            service.shutdown();
            playerList.remove(mock);
        });
    }
}
