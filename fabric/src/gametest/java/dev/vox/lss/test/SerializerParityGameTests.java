package dev.vox.lss.test;

import net.minecraft.network.chat.Component;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.SharedBandwidthLimiter;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.common.processing.TickDiagnostics;
import dev.vox.lss.common.processing.TickSnapshot;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.server.ChunkDiskReader;
import dev.vox.lss.networking.server.DirtyContentFilter;
import dev.vox.lss.networking.server.FabricOffThreadProcessor;
import dev.vox.lss.networking.server.RequestProcessingService;
import dev.vox.lss.networking.server.SectionSerializer;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Content-level serializer truth on a real dedicated server:
 *
 * <ul>
 *   <li><b>Disk/live parity</b> — the wire bytes {@code NbtSectionSerializer} produces from a
 *       chunk's region NBT must byte-equal what {@code SectionSerializer} produces from the same
 *       chunk loaded back into memory. Divergence silently breaks the up-to-date economy (every
 *       disk-served column re-sends after its next save) and blocks seeding the
 *       {@code DirtyContentFilter} from disk serves.</li>
 *   <li><b>Dirty-content gating</b> — {@code DirtyContentFilter.contentChanged} on real chunks:
 *       first observation marks, identical re-saves stay quiet (the filter's entire reason to
 *       exist), edits re-mark, and the End-void ALL_AIR sentinel is stable and consistent with
 *       {@code seed}.</li>
 *   <li><b>All-air End disk read</b> — a FULL all-air End chunk on disk resolves as found
 *       (all-air, real timestamp), not "not found"; the pre-761b3fb regression triaged it as
 *       missing and caused endless re-generation storms in the End void.</li>
 * </ul>
 */
public class SerializerParityGameTests {

    /** Distinct far-away chunk offsets per test so concurrently running batch tests never share state. */
    private static final int PARITY_CHUNK_OFFSET = 64;
    private static final int DIRTY_FILTER_CHUNK_OFFSET = 96;
    private static final int READ_AFTER_SAVE_CHUNK_OFFSET = 104;
    private static final int DISK_SEED_CHUNK_OFFSET = 112;
    private static final int ALL_AIR_TRANSITION_CHUNK_OFFSET = 128;

    /** Deprecated upstream without a replacement; it is the only factory that places a real
     *  ServerPlayer (player list entry + embedded-channel connection) inside a gametest. */
    @SuppressWarnings("removal")
    private static net.minecraft.server.level.ServerPlayer placeMockServerPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }
    // End void: between the main island (~chunk 22) and the outer islands (chunk 64+) the island
    // density function contributes nothing (max |chunk/2 + 12|^2 sum < 4096), so chunks there are
    // guaranteed all-air in every vanilla seed. The disk-read test may use a fixed position
    // because it never modifies blocks (the gametest world PERSISTS across runs — block-writing
    // tests must derive per-run positions instead, see the sentinel test).
    private static final int END_VOID_DISK_CX = 44;
    private static final int END_VOID_DISK_CZ = 8;

    /**
     * A column served from disk must be byte-identical to the same column served live after the
     * chunk loads back from that disk state. The chunk is generated, given a torch that is placed
     * and removed (leaving the light engine's allocated-but-all-zero BlockLight array in the
     * non-air section — the classic source of disk/live light asymmetry), then unloaded so the
     * save normalizes it; the comparison runs against the reloaded chunk because vanilla's save
     * path re-palettizes containers (first-appearance order), so a never-reloaded chunk differs
     * from its own save for reasons outside LSS's serializers.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void diskReadBytesMatchLiveBytesForDiskLoadedColumn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + PARITY_CHUNK_OFFSET;
        int cz = origin.z + 7;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        // Superflat surface: bedrock -64, dirt -63/-62, grass -61; first air block is -60.
        var torchPos = new BlockPos(cx * 16 + 8, -60, cz * 16 + 8);

        // Hold the chunk loaded for the torch dance (plain getChunk tickets expire after 1 tick).
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);
        level.setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
        helper.runAfterDelay(4, () -> level.setBlock(torchPos, Blocks.AIR.defaultBlockState(), 3));
        helper.runAfterDelay(8, () -> chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0));

        var reader = new ChunkDiskReader(1);
        var readerId = UUID.randomUUID();
        reader.registerPlayer(readerId);
        var step = new AtomicInteger();
        var diskBytes = new AtomicReference<byte[]>();

        // succeedWhen re-runs every tick until no assertion throws; assertTrue(false, ...) is the
        // "not yet, retry next tick" idiom and its message names the stuck phase on timeout.
        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 10, Component.literal("waiting for the torch dance to finish"));
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(chunkSource.getChunkNow(cx, cz) == null,
                            Component.literal("waiting for the chunk to unload"));
                    // The unload save may still sit in the unload queue; saveAllChunks drains it
                    // and flushes storage so the region state is final before the read.
                    level.save(null, true, false);
                    reader.submitReadDirect(readerId, LSSConstants.DIM_STR_OVERWORLD, level, cx, cz, 0);
                    step.set(1);
                    helper.assertTrue(false, Component.literal("disk read submitted, awaiting result"));
                }
                case 1 -> {
                    var result = reader.getPlayerQueue(readerId).poll();
                    helper.assertTrue(result != null, Component.literal("waiting for the disk read result"));
                    reader.shutdown();
                    helper.assertTrue(!result.notFound(), Component.literal("generated chunk must exist on disk after unload"));
                    helper.assertTrue(!result.saturated(), Component.literal("single read on a fresh reader must not saturate"));
                    helper.assertTrue(result.sectionBytes() != null,
                            Component.literal("superflat chunk must have non-air content on disk"));
                    diskBytes.set(result.sectionBytes());
                    step.set(2);
                    helper.assertTrue(false, Component.literal("disk bytes captured, reloading chunk"));
                }
                // Re-evaluated until equal or timeout, so one tick of post-load light settling
                // only delays success instead of failing the test.
                case 2 -> {
                    var chunk = level.getChunk(cx, cz);
                    var live = SectionSerializer.serializeColumn(level, chunk, cx, cz).serializedSections();
                    helper.assertTrue(live != null, Component.literal("reloaded superflat chunk must serialize live content"));
                    helper.assertTrue(Arrays.equals(diskBytes.get(), live),
                            Component.literal(describeMismatch(diskBytes.get(), live)));
                }
                default -> helper.fail(Component.literal("unexpected parity test step " + step.get()));
            }
        });
    }

    /**
     * {@code DirtyContentFilter.contentChanged} ladder on a real superflat chunk:
     * first observation marks dirty, identical content stays quiet within a tick and across
     * ticks (pins SectionSerializer's call-to-call determinism — the suppress direction that
     * makes the filter worth having), a real block edit re-marks, and the post-edit baseline
     * suppresses again.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void dirtyContentFilterSuppressesIdenticalResavesAndCatchesEdits(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + DIRTY_FILTER_CHUNK_OFFSET;
        int cz = origin.z + 13;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        var filter = new DirtyContentFilter();
        // Grass surface block of the default superflat preset.
        var editPos = new BlockPos(cx * 16 + 4, -61, cz * 16 + 4);

        // Keep the chunk loaded across the ladder so every step hashes the same live chunk
        // (a getChunk ticket lasts 1 tick; an unload+reload between steps would re-palettize
        // the sections and fake a content change).
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);

        // Tick 2: generation-time light has settled; take the baseline and pin same-tick quiet.
        helper.runAfterDelay(2, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(filter.contentChanged(level, chunk, dim),
                    Component.literal("first observation of a column is always a change"));
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    Component.literal("identical re-save in the same tick must stay quiet"));
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    Component.literal("suppression must hold across repeated identical saves"));
        });

        // Tick 4: cross-tick quiet on untouched content, then edit the surface.
        helper.runAfterDelay(4, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    Component.literal("identical content two ticks later must still stay quiet (cross-tick determinism)"));
            level.setBlock(editPos, Blocks.STONE.defaultBlockState(), 3);
        });

        // Tick 6: the edit must mark dirty exactly once, then suppression resumes.
        helper.runAfterDelay(6, () -> {
            var chunk = level.getChunk(cx, cz);
            helper.assertTrue(filter.contentChanged(level, chunk, dim),
                    Component.literal("a real block edit must re-mark the column dirty"));
            helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                    Component.literal("the save after the edit is absorbed must stay quiet again"));
            chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
            helper.succeed();
        });
    }

    /**
     * End-void ALL_AIR sentinel: all-air columns serialize to null bytes, which the filter must
     * hash as a stable sentinel — air-to-air saves stay quiet, an all-air serve seed agrees with
     * the next all-air save, and an air-to-built transition still marks dirty.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void dirtyContentFilterAllAirSentinelInEndVoid(GameTestHelper helper) {
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(endLevel != null, Component.literal("the End dimension must exist on the gametest server"));
        var dim = LSSConstants.DIM_STR_THE_END;

        // This test builds a block, and the gametest world persists across runs — so derive the
        // chunk from the per-run random batch position (cx 28..43, cz -16..-9: inside the void
        // guarantee band, disjoint from the disk-read test's chunk) and scan down-z past any
        // column a previous run already built in.
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int salt = Math.floorMod(origin.x * 31 + origin.z, 256);
        int cx = 28 + (salt & 15);
        int baseCz = -16 + ((salt >> 4) & 7);
        int cz = baseCz;
        var chunk = endLevel.getChunk(cx, cz);
        for (int remaining = 8; remaining > 0
                && SectionSerializer.serializeColumn(endLevel, chunk, cx, cz).serializedSections() != null;
                remaining--) {
            cz--;
            chunk = endLevel.getChunk(cx, cz);
        }
        var live = SectionSerializer.serializeColumn(endLevel, chunk, cx, cz);
        helper.assertTrue(live.serializedSections() == null,
                Component.literal("premise: no all-air End void column found near chunk (" + cx + ", " + baseCz + ")"));

        var filter = new DirtyContentFilter();
        helper.assertTrue(filter.contentChanged(endLevel, chunk, dim),
                Component.literal("first observation of the all-air column is a change"));
        helper.assertTrue(!filter.contentChanged(endLevel, chunk, dim),
                Component.literal("air-to-air save must stay quiet (ALL_AIR sentinel is stable)"));

        // A serve of this all-air column seeds null bytes; the next save of the same all-air
        // chunk must hash to the same sentinel, or every void column re-marks after every save.
        var seededFilter = new DirtyContentFilter();
        seededFilter.seed(dim, cx, cz, live.serializedSections());
        helper.assertTrue(!seededFilter.contentChanged(endLevel, chunk, dim),
                Component.literal("all-air save after an all-air serve seed must stay quiet"));

        endLevel.setBlock(new BlockPos(cx * 16 + 8, 80, cz * 16 + 8), Blocks.END_STONE.defaultBlockState(), 3);
        helper.assertTrue(filter.contentChanged(endLevel, chunk, dim),
                Component.literal("air-to-built transition must mark dirty (the sentinel must not swallow it)"));
        helper.succeed();
    }

    /**
     * The 761b3fb End-void chain at disk-reader level: an all-air FULL End chunk on disk resolves
     * as found (all-air triage, null section bytes, real timestamp for the up-to-date economy),
     * never as "not found" — which would re-trigger generation forever.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
    public void allAirEndChunkDiskReadResolvesFoundNotMissing(GameTestHelper helper) {
        ServerLevel endLevel = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(endLevel != null, Component.literal("the End dimension must exist on the gametest server"));
        int cx = END_VOID_DISK_CX;
        int cz = END_VOID_DISK_CZ;

        var chunk = endLevel.getChunk(cx, cz);
        helper.assertTrue(
                SectionSerializer.serializeColumn(endLevel, chunk, cx, cz).serializedSections() == null,
                Component.literal("premise: the End void chunk serializes as all-air"));
        // Flush the freshly generated chunk to its region file so the read below hits real disk state.
        endLevel.save(null, true, false);

        var reader = new ChunkDiskReader(1);
        var readerId = UUID.randomUUID();
        reader.registerPlayer(readerId);
        reader.submitReadDirect(readerId, LSSConstants.DIM_STR_THE_END, endLevel, cx, cz, 0);

        var result = new AtomicReference<dev.vox.lss.common.processing.ChunkReadResult>();
        helper.succeedWhen(() -> {
            if (result.get() == null) {
                var polled = reader.getPlayerQueue(readerId).poll();
                helper.assertTrue(polled != null, Component.literal("waiting for the async disk read to complete"));
                result.set(polled);
                reader.shutdown();
            }
            var r = result.get();
            helper.assertTrue(!r.notFound(),
                    Component.literal("all-air FULL chunk on disk must resolve as found, not not-found (not-found re-triggers generation forever)"));
            helper.assertTrue(!r.saturated(), Component.literal("single read on a fresh reader must not saturate"));
            helper.assertTrue(r.sectionBytes() == null, Component.literal("all-air result carries null section bytes (nothing to send)"));
            helper.assertTrue(r.columnTimestamp() > 0,
                    Component.literal("all-air result must carry a real timestamp so the client can mark the column up-to-date"));
            helper.assertTrue(reader.getDiag().getAllAirCount() == 1, Component.literal("diagnostics must triage the read as all-air"));
            helper.assertTrue(reader.getDiag().getNotFoundCount() == 0, Component.literal("diagnostics must not count the read as not-found"));
        });
    }

    /**
     * FP-028: a disk read fired right after a forced save — WITHOUT unloading the chunk —
     * must observe the saved edit, never silently-stale bytes (the IO-worker pending-write
     * visibility window). Baseline bytes are read after a first save, the chunk is edited
     * and force-saved while still loaded, and the second read must differ from the
     * baseline. Both reads run against the same settled-light chunk, so the edit is the
     * only delta between them.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
    public void readAfterSaveWithoutUnloadSeesTheLatestBytes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + READ_AFTER_SAVE_CHUNK_OFFSET;
        int cz = origin.z + 3;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var editPos = new BlockPos(cx * 16 + 4, -61, cz * 16 + 4);

        // Held for the whole test: the read must hit disk state while the chunk is loaded.
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);

        var reader = new ChunkDiskReader(1);
        var readerId = UUID.randomUUID();
        reader.registerPlayer(readerId);
        var step = new AtomicInteger();
        var baseline = new AtomicReference<byte[]>();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 4, Component.literal("waiting for generation light to settle"));
            switch (step.get()) {
                case 0 -> {
                    level.save(null, true, false);
                    reader.submitReadDirect(readerId, LSSConstants.DIM_STR_OVERWORLD, level, cx, cz, 0);
                    step.set(1);
                    helper.assertTrue(false, Component.literal("baseline read submitted"));
                }
                case 1 -> {
                    var result = reader.getPlayerQueue(readerId).poll();
                    helper.assertTrue(result != null, Component.literal("waiting for the baseline read"));
                    helper.assertTrue(!result.notFound() && result.sectionBytes() != null,
                            Component.literal("premise: the saved superflat chunk must read back with content"));
                    baseline.set(result.sectionBytes());
                    // Edit while loaded, force-save, read again — all within one callback so
                    // nothing else can touch the chunk between the save and the read.
                    var edit = level.getBlockState(editPos).is(Blocks.STONE)
                            ? Blocks.COBBLESTONE : Blocks.STONE;
                    level.setBlock(editPos, edit.defaultBlockState(), 3);
                    level.save(null, true, false);
                    reader.submitReadDirect(readerId, LSSConstants.DIM_STR_OVERWORLD, level, cx, cz, 1);
                    step.set(2);
                    helper.assertTrue(false, Component.literal("post-edit read submitted"));
                }
                case 2 -> {
                    var result = reader.getPlayerQueue(readerId).poll();
                    helper.assertTrue(result != null, Component.literal("waiting for the post-edit read"));
                    reader.shutdown();
                    helper.assertTrue(!result.notFound() && result.sectionBytes() != null,
                            Component.literal("the post-edit read must resolve with content"));
                    helper.assertTrue(!Arrays.equals(baseline.get(), result.sectionBytes()),
                            Component.literal("a read fired right after a forced save of a still-loaded chunk "
                                    + "must see the edit — byte-identical results mean the "
                                    + "read silently served stale pre-save state"));
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                }
                default -> helper.fail(Component.literal("unexpected read-after-save step " + step.get()));
            }
        });
    }

    /**
     * FP-042: disk-read serves do NOT seed the {@code DirtyContentFilter} — a conscious pin
     * of current behavior. The consequence is the warm-rejoin re-send wave: the first save
     * after a disk serve counts as "first observed save" and re-marks the column even
     * though the client already holds identical bytes. If seeding is ever added (the fix),
     * this test fails and must be flipped deliberately. The reloaded chunk hashes equal to
     * the disk-served bytes (pinned by the parity test), so a seeded filter would return
     * false here.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 1200)
    public void diskReadServesDoNotSeedTheDirtyContentFilter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + DISK_SEED_CHUNK_OFFSET;
        int cz = origin.z + 9;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        long packed = PositionUtil.packPosition(cx, cz);

        // Generate, then let the chunk unload so the serve must come from disk.
        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);
        helper.runAfterDelay(4, () -> chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0));

        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var step = new AtomicInteger();
        var settle = new AtomicInteger();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 6, Component.literal("waiting for the ticket release"));
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(chunkSource.getChunkNow(cx, cz) == null,
                            Component.literal("waiting for the chunk to unload"));
                    level.save(null, true, false);
                    state.addRequest(packed, -1L);
                    step.set(1);
                    helper.assertTrue(false, Component.literal("request queued, awaiting the disk serve"));
                }
                case 1 -> {
                    service.tick();
                    helper.assertTrue(state.getTotalSectionsSent() >= 1,
                            Component.literal("waiting for the disk serve to flush"));
                    helper.assertTrue(
                            service.getDiskReader().getDiag().getSuccessfulReadCount() == 1
                                    && service.getOffThreadProcessor().getDiagnostics().getTotalInMemory() == 0,
                            Component.literal("premise: the serve must come from DISK, not the in-memory probe"));
                    // Reload for the filter check; settle light before hashing.
                    chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    level.getChunk(cx, cz);
                    step.set(2);
                    helper.assertTrue(false, Component.literal("chunk reloading for the filter probe"));
                }
                case 2 -> {
                    helper.assertTrue(settle.incrementAndGet() >= 2,
                            Component.literal("waiting for post-reload light to settle (byte determinism)"));
                    var chunk = level.getChunk(cx, cz);
                    var filter = service.getDirtyContentFilter();
                    helper.assertTrue(filter.contentChanged(level, chunk, dim),
                            Component.literal("PINNED GAP: a disk-read serve must NOT seed the dirty filter "
                                    + "(today's behavior — the warm-rejoin re-send wave source). "
                                    + "If seeding was added intentionally, flip this pin."));
                    helper.assertTrue(!filter.contentChanged(level, chunk, dim),
                            Component.literal("control: the check above must have baselined the live filter"));
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    service.shutdown();
                    playerList.remove(mock);
                }
                default -> helper.fail(Component.literal("unexpected disk-seed step " + step.get()));
            }
        });
    }

    /**
     * FP-054: a served column that becomes ALL-AIR re-resolves — after the dirty
     * invalidation + done-bit clear the broadcaster performs — as a single UP_TO_DATE
     * status with NO column payload: the server never ships an empty-section
     * VoxelColumn frame ({@code enqueueLoadedColumn} rejects null/empty bytes). Pinned at
     * the captured SendAction; consumer-visible emptiness therefore depends entirely on
     * the client's handling of up-to-date for a dirty position (cross-ref CL-074, the
     * H-11/H-25 stale-geometry class). The send-action drain is captured manually — the
     * service is never ticked, so the recorder is the only drainer.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 600)
    public void becomesAllAirReServeSendsClearingColumnWhenClientClaimsData(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var playerList = server.getPlayerList();
        var origin = new ChunkPos(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x + ALL_AIR_TRANSITION_CHUNK_OFFSET;
        int cz = origin.z + 11;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var dim = LSSConstants.DIM_STR_OVERWORLD;
        long packed = PositionUtil.packPosition(cx, cz);

        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);

        var mock = placeMockServerPlayer(helper);
        var service = new RequestProcessingService(server);
        var state = service.registerPlayer(mock, LSSConstants.CAPABILITY_VOXEL_COLUMNS);
        var proc = (FabricOffThreadProcessor) service.getOffThreadProcessor();
        var uuid = mock.getUUID();
        var limiter = new SharedBandwidthLimiter(1_073_741_824L);
        var flushDiag = new TickDiagnostics();
        var recordedTypes = new ArrayList<Byte>();
        var recordedPositions = new ArrayList<Long>();
        var step = new AtomicInteger();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 2, Component.literal("waiting for generation light to settle"));
            switch (step.get()) {
                case 0 -> {
                    var chunk = level.getChunk(cx, cz);
                    var built = SectionSerializer.serializeColumn(level, chunk, cx, cz);
                    helper.assertTrue(built.serializedSections() != null,
                            Component.literal("premise: the superflat column serves non-air content first"));
                    state.addRequest(packed, -1L);
                    Long2ObjectMap<LoadedColumnData> probes = new Long2ObjectOpenHashMap<>();
                    probes.put(packed, built);
                    proc.postSnapshot(new TickSnapshot(Map.of(uuid, dim), Map.of(uuid, probes),
                            LSSServerConfig.CONFIG.sendQueueLimitPerPlayer, false), List.of());
                    step.set(1);
                    helper.assertTrue(false, Component.literal("first serve posted, awaiting flush"));
                }
                case 1 -> {
                    state.flushSendQueue(1_073_741_824L, limiter, flushDiag, p -> {});
                    helper.assertTrue(state.getTotalSectionsSent() == 1,
                            Component.literal("waiting for the built column to serve and flush"));
                    // The column becomes all-air: strip every superflat layer.
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int y = -64; y <= -61; y++) {
                                level.setBlock(new BlockPos(cx * 16 + x, y, cz * 16 + z),
                                        Blocks.AIR.defaultBlockState(), 3);
                            }
                        }
                    }
                    var chunk = level.getChunk(cx, cz);
                    var emptied = SectionSerializer.serializeColumn(level, chunk, cx, cz);
                    helper.assertTrue(emptied.serializedSections() == null,
                            Component.literal("premise: the stripped column must serialize as all-air"));
                    // Broadcaster-equivalent dirty events, then the client's re-request with
                    // its stored stamp; all posted before one snapshot = one mailbox take.
                    proc.invalidateTimestamps(dim, new long[]{packed});
                    proc.clearDiskReadDone(uuid, new long[]{packed});
                    state.addRequest(packed, LSSConstants.epochSeconds() + 10_000);
                    Long2ObjectMap<LoadedColumnData> probes = new Long2ObjectOpenHashMap<>();
                    probes.put(packed, emptied);
                    proc.postSnapshot(new TickSnapshot(Map.of(uuid, dim), Map.of(uuid, probes),
                            LSSServerConfig.CONFIG.sendQueueLimitPerPlayer, false), List.of());
                    step.set(2);
                    helper.assertTrue(false, Component.literal("all-air re-serve posted, awaiting the answer"));
                }
                case 2 -> {
                    proc.drainSendActions((st, types, positions, count) -> {
                        for (int i = 0; i < count; i++) {
                            recordedTypes.add(types[i]);
                            recordedPositions.add(positions[i]);
                        }
                    });
                    // WS3: a ts>0 all-air re-serve (the client claims data) now ships a CLEARING
                    // 0-section column so the client drops ghost terrain — NOT an up_to_date
                    // status. Emptiness for a data-claiming client travels as a column payload.
                    helper.assertTrue(recordedPositions.isEmpty(),
                            Component.literal("the clearing re-serve travels as a column payload, not a batch status; "
                                    + "got types=" + recordedTypes + " positions=" + recordedPositions));
                    helper.assertTrue(state.hasEnqueuedColumn(packed),
                            Component.literal("waiting for the all-air re-serve to enqueue its clearing 0-section column"));
                    state.flushSendQueue(1_073_741_824L, limiter, flushDiag, p -> {});
                    helper.assertTrue(state.getTotalSectionsSent() == 2,
                            Component.literal("the clearing column flushes as a second payload (present serve + "
                                    + "clearing re-serve), got " + state.getTotalSectionsSent()));
                    helper.assertTrue(proc.getDiagnostics().getTotalInMemory() == 2,
                            Component.literal("premise: both requests must have taken the probe route, got "
                                    + proc.getDiagnostics().getTotalInMemory()));
                    chunkSource.removeTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
                    service.shutdown();
                    playerList.remove(mock);
                }
                default -> helper.fail(Component.literal("unexpected all-air transition step " + step.get()));
            }
        });
    }

    private static String describeMismatch(byte[] disk, byte[] live) {
        if (disk == null || live == null) {
            return "disk/live serialization mismatch: disk=" + (disk == null ? "null" : disk.length + " bytes")
                    + ", live=" + (live == null ? "null" : live.length + " bytes");
        }
        int at = Arrays.mismatch(disk, live);
        return "disk-read wire bytes diverge from live-serialized bytes (serializer asymmetry breaks the "
                + "up-to-date economy): lengths disk=" + disk.length + " live=" + live.length
                + ", first mismatch at byte " + at
                + ", disk[..]=" + hexWindow(disk, at) + ", live[..]=" + hexWindow(live, at);
    }

    private static String hexWindow(byte[] bytes, int around) {
        int from = Math.max(0, around - 4);
        int to = Math.min(bytes.length, around + 8);
        var sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append(' ');
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
