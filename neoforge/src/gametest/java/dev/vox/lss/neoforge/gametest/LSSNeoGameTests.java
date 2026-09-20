package dev.vox.lss.neoforge.gametest;

import dev.vox.lss.common.processing.RequestRegistration;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.HandshakeC2SPayload;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.server.ChunkDiskReader;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.networking.server.SectionSerializer;
import dev.vox.lss.networking.server.ServerReceiverGlue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The N-2 gametest smoke subset (neoforge-support-plan.md §5.2): ~8 tests, not
 * the fabric module's 71 — best-effort tier. Registration is the 26.2
 * data-driven idiom (bytecode-researched): test FUNCTIONS go into the static
 * {@code TEST_FUNCTION} registry during mod construction (frozen long before
 * the gametest event), and {@code RegisterGameTestsEvent} — fired from the
 * RegistryDataLoader patch on every load-from-resources — registers the
 * {@code FunctionGameTestInstance}s against our own empty ALL_OF environment
 * ({@code minecraft:default} is unreachable from the event, and is itself an
 * empty all_of). Structures use vanilla's {@code minecraft:empty} (the
 * fabric-gametest-api-v1:empty equivalent). Run:
 * {@code ./gradlew :neoforge:runGameTestServer} (exit code = failed count).
 */
@Mod("lsstest")
public final class LSSNeoGameTests {
    private static final java.util.Map<UUID, RequestRegistration> TEST_REGISTRATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static RequestRegistration registration(UUID uuid) {
        return TEST_REGISTRATIONS.computeIfAbsent(uuid, ignored -> new RequestRegistration());
    }


    private record Smoke(String name, int maxTicks, Consumer<GameTestHelper> body) {
    }

    private static final List<Smoke> TESTS = List.of(
            new Smoke("service_activates", 200, LSSNeoGameTests::serviceActivates),
            new Smoke("config_loads_fresh_defaults", 100, LSSNeoGameTests::configLoadsFreshDefaults),
            new Smoke("store_active_on_fresh_world", 200, LSSNeoGameTests::storeActiveOnFreshWorld),
            new Smoke("handshake_caps0_replies_without_register", 200,
                    LSSNeoGameTests::handshakeCapsZeroRepliesWithoutRegistering),
            new Smoke("handshake_foreign_version_is_silent", 200,
                    LSSNeoGameTests::handshakeForeignVersionIsSilent),
            new Smoke("handshake_v20_registers_and_removes", 200,
                    LSSNeoGameTests::handshakeV20RegistersAndRemoves),
            new Smoke("dirty_save_hook_marks_on_content_change", 400,
                    LSSNeoGameTests::dirtySaveHookMarksOnContentChange),
            new Smoke("disk_read_bytes_match_live_bytes", 1200,
                    LSSNeoGameTests::diskReadBytesMatchLiveBytes));

    public LSSNeoGameTests(IEventBus modBus) {
        modBus.addListener(RegisterEvent.class, LSSNeoGameTests::registerFunctions);
        modBus.addListener(RegisterGameTestsEvent.class, LSSNeoGameTests::registerTests);
        // The N-3 executable client gate (plan §0.1): the "throwaway LSSApi consumer
        // test mod" — with -Dlss.smoke.consumer=true on a CLIENT run, register a
        // consumer that logs decoded column receipts. Proves the whole client half
        // (handshake, capability bit, want-set, decode, dispatch) with no renderer.
        if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()
                && Boolean.getBoolean("lss.smoke.consumer")) {
            SmokeConsumer.register();
        }
    }

    /** Logs every 200th column receipt (and the first) — the end-to-end evidence line. */
    static final class SmokeConsumer implements dev.vox.lss.api.VoxelColumnConsumer {
        private final java.util.concurrent.atomic.AtomicLong count =
                new java.util.concurrent.atomic.AtomicLong();

        static void register() {
            dev.vox.lss.api.LSSApi.registerColumnConsumer(new SmokeConsumer());
            dev.vox.lss.common.LSSLogger.info("[lss-smoke] column consumer registered");
        }

        @Override
        public void onVoxelColumnReceived(net.minecraft.client.multiplayer.ClientLevel level,
                                          net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                                          int chunkX, int chunkZ,
                                          dev.vox.lss.api.VoxelColumnData columnData) {
            long n = count.incrementAndGet();
            if (n == 1 || n % 200 == 0) {
                dev.vox.lss.common.LSSLogger.info("[lss-smoke] columns received=" + n
                        + " last=" + chunkX + "," + chunkZ
                        + " sections=" + columnData.sections().length);
            }
        }
    }

    private static void registerFunctions(RegisterEvent event) {
        for (Smoke t : TESTS) {
            event.register(Registries.TEST_FUNCTION,
                    Identifier.fromNamespaceAndPath("lsstest", t.name()),
                    t::body);
        }
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        // Fires per datapack load (server start AND /reload) with FRESH registries —
        // this listener is deliberately re-runnable and touches no other state.
        var env = event.registerEnvironment(Identifier.fromNamespaceAndPath("lsstest", "default"));
        for (Smoke t : TESTS) {
            ResourceKey<Consumer<GameTestHelper>> fn = ResourceKey.create(Registries.TEST_FUNCTION,
                    Identifier.fromNamespaceAndPath("lsstest", t.name()));
            event.registerTest(Identifier.fromNamespaceAndPath("lsstest", t.name()),
                    td -> new FunctionGameTestInstance(fn, td),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"),
                            t.maxTicks(), 1, true));
        }
    }

    // ---- bodies ----

    private static void serviceActivates(GameTestHelper helper) {
        helper.succeedWhen(() -> helper.assertTrue(
                LSSServerNetworking.getRequestService() != null,
                "RequestProcessingService should be active on the gametest (dedicated) server"));
    }

    private static void configLoadsFreshDefaults(GameTestHelper helper) {
        var config = LSSServerConfig.CONFIG;
        helper.assertTrue(config != null, "server config must load");
        helper.assertTrue(config.enabled, "fresh config must default enabled");
        helper.assertTrue(config.lodDistanceChunks == 512,
                "fresh config must carry the shipped 512 distance, got " + config.lodDistanceChunks);
        helper.succeed();
    }

    private static void storeActiveOnFreshWorld(GameTestHelper helper) {
        helper.succeedWhen(() -> {
            var service = LSSServerNetworking.getRequestService();
            helper.assertTrue(service != null, "service must be up first");
            // Fresh install (no config file existed) generates lodStore "on" via the
            // fresh-create hook; the SQLite engine must come up through the SHADED
            // natives — a degrade would leave this null (store=off honesty).
            helper.assertTrue(service.getLodStore() != null,
                    "the fresh-create store must arm through the shaded sqlite natives");
        });
    }

    private static void handshakeCapsZeroRepliesWithoutRegistering(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "service must be up");
        var player = helper.makeMockServerPlayerInLevel();
        List<SessionConfigS2CPayload> replies = new ArrayList<>();
        ServerReceiverGlue.handleHandshake(
                new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION, 0),
                player, service, replies::add);
        helper.assertTrue(replies.size() == 1,
                "caps=0 must reply (the NO_CONSUMER advertisement), got " + replies.size());
        helper.assertTrue(!service.getPlayers().containsKey(player.getUUID()),
                "caps=0 must NOT register the player");
        helper.succeed();
    }

    private static void handshakeForeignVersionIsSilent(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "service must be up");
        var player = helper.makeMockServerPlayerInLevel();
        List<SessionConfigS2CPayload> replies = new ArrayList<>();
        ServerReceiverGlue.handleHandshake(
                new HandshakeC2SPayload(9999, LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                player, service, replies::add);
        helper.assertTrue(replies.isEmpty(),
                "a foreign-version handshake must produce ZERO reply frames (replying"
                        + " would kick the client), got " + replies.size());
        helper.assertTrue(!service.getPlayers().containsKey(player.getUUID()),
                "a foreign-version handshake must not register");
        helper.succeed();
    }

    private static void handshakeV20RegistersAndRemoves(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "service must be up");
        var player = helper.makeMockServerPlayerInLevel();
        List<SessionConfigS2CPayload> replies = new ArrayList<>();
        try {
            ServerReceiverGlue.handleHandshake(
                    new HandshakeC2SPayload(LSSConstants.PROTOCOL_VERSION,
                            LSSConstants.CAPABILITY_VOXEL_COLUMNS),
                    player, service, replies::add);
            helper.assertTrue(replies.size() == 1, "a v20 handshake must reply the SessionConfig");
            helper.assertTrue(replies.get(0).protocolVersion() == LSSConstants.PROTOCOL_VERSION,
                    "the reply must echo the native protocol");
            helper.assertTrue(service.getPlayers().containsKey(player.getUUID()),
                    "a v20 consumer handshake must register the player");
        } finally {
            // Failure-path ghost guard (N-2 review NIT): the mock must never stay
            // registered in the LIVE service past this test.
            service.removePlayer(player.getUUID());
        }
        helper.assertTrue(!service.getPlayers().containsKey(player.getUUID()),
                "removePlayer must clean the registration up");
        helper.succeed();
    }

    private static void dirtySaveHookMarksOnContentChange(GameTestHelper helper) {
        var service = LSSServerNetworking.getRequestService();
        helper.assertTrue(service != null, "service must be up");
        ServerLevel level = helper.getLevel();
        // Drive the FILTER directly (the hook pipeline's decision core): observe the
        // chunk containing the edit, seed its hash, edit, observe again — the second
        // observation must read changed. Diagnostic-rich assertion (first N-2 runs
        // failed opaquely).
        var filter = service.getDirtyContentFilter();
        var editPos = helper.absolutePos(new BlockPos(1, 2, 1));
        var chunk = level.getChunkAt(editPos);
        String dim = level.dimension().identifier().toString();
        var first = filter.observeSave(level, chunk, dim);
        boolean placed = level.setBlock(editPos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        var second = filter.observeSave(level, chunk, dim);
        var probe = SectionSerializer.serializeColumn(level, chunk,
                chunk.getPos().x(), chunk.getPos().z());
        helper.assertTrue(second.changed(),
                "a content edit must hash as changed: first.changed=" + first.changed()
                        + " placed=" + placed
                        + " liveLen=" + (probe == null || probe.serializedSections() == null
                                ? -1 : probe.serializedSections().length)
                        + " editPos=" + editPos + " chunk=" + chunk.getPos());
        // And the hook body routes a changed observation into the tracker.
        // DRAIN FIRST (hardened 2026-08-21, first surfaced on the 1.21.1 line once its
        // smoke actually ran): totalMarked counts NEW set-adds only, and a background
        // batch-save through the real mixin can have already marked THIS chunk (the
        // structure placement is a content change) — the hook's re-mark of a pending
        // position then legitimately does not count, and the global-counter assertion
        // reds on save-cadence luck. Draining consumes any background mark; drain,
        // edit, and hook call share one server-thread tick, so nothing can re-mark
        // in between and the increment is deterministic.
        service.getDirtyTracker().drainDirty(dim);
        long before = service.getDirtyTracker().getTotalMarked();
        level.setBlock(editPos.above(), Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        LSSServerNetworking.onChunkSaveData(level, chunk);
        helper.assertTrue(service.getDirtyTracker().getTotalMarked() > before,
                "the hook body must mark the changed column (totalMarked stuck at " + before + ")");
        helper.succeed();
    }

    /**
     * THE cross-loader correctness pin (plan §5.2): a column served from disk must be
     * byte-identical to the same column served live after reload — the fabric
     * {@code SerializerParityGameTests} core, ported onto the same xplat serializers
     * this loader ships. Torch dance + reload rationale as on fabric: the light
     * engine's allocated-but-zero BlockLight array is the classic disk/live asymmetry,
     * and vanilla's save re-palettizes containers, so the comparison must run against
     * the reloaded chunk.
     */
    private static void diskReadBytesMatchLiveBytes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var origin = ChunkPos.containing(helper.absolutePos(BlockPos.ZERO));
        int cx = origin.x() + 120;
        int cz = origin.z() + 7;
        var chunkPos = new ChunkPos(cx, cz);
        var chunkSource = level.getChunkSource();
        var torchPos = new BlockPos(cx * 16 + 8, -60, cz * 16 + 8);

        chunkSource.addTicketWithRadius(TicketType.PLAYER_LOADING, chunkPos, 0);
        level.getChunk(cx, cz);
        level.setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
        helper.runAfterDelay(4, () -> level.setBlock(torchPos, Blocks.AIR.defaultBlockState(), 3));
        helper.runAfterDelay(8, () -> chunkSource.removeTicketWithRadius(
                TicketType.PLAYER_LOADING, chunkPos, 0));

        var reader = new ChunkDiskReader(1, false);
        var readerId = UUID.randomUUID();
        reader.registerPlayer(readerId, registration(readerId));
        var step = new AtomicInteger();
        var diskBytes = new AtomicReference<byte[]>();

        helper.succeedWhen(() -> {
            helper.assertTrue(helper.getTick() >= 10, "waiting for the torch dance to finish");
            switch (step.get()) {
                case 0 -> {
                    helper.assertTrue(chunkSource.getChunkNow(cx, cz) == null,
                            "waiting for the chunk to unload");
                    level.save(null, true, false);
                    reader.submitReadDirect(readerId, registration(readerId), LSSConstants.DIM_STR_OVERWORLD,
                            level, cx, cz, 0, 0L);
                    step.set(1);
                    helper.assertTrue(false, "disk read submitted, awaiting result");
                }
                case 1 -> {
                    var result = reader.getPlayerQueue(readerId).poll();
                    helper.assertTrue(result != null, "waiting for the disk read result");
                    // Assert BEFORE shutdown so a real read failure names itself instead
                    // of surfacing as a poll-timeout (N-2 review NIT; also restores the
                    // fabric original's saturation assert).
                    boolean notFound = result.notFound();
                    boolean saturated = result.saturated();
                    byte[] bytes = result.sectionBytes();
                    reader.shutdown();
                    helper.assertTrue(!notFound,
                            "generated chunk must exist on disk after unload");
                    helper.assertTrue(!saturated,
                            "single read on a fresh reader must not saturate");
                    helper.assertTrue(bytes != null,
                            "superflat chunk must have non-air content on disk");
                    diskBytes.set(bytes);
                    step.set(2);
                    helper.assertTrue(false, "disk bytes captured, reloading chunk");
                }
                case 2 -> {
                    var chunk = level.getChunk(cx, cz);
                    var live = SectionSerializer.serializeColumn(level, chunk, cx, cz)
                            .serializedSections();
                    helper.assertTrue(live != null,
                            "reloaded superflat chunk must serialize live content");
                    helper.assertTrue(Arrays.equals(diskBytes.get(), live),
                            "disk and live bytes must be identical (disk="
                                    + (diskBytes.get() == null ? -1 : diskBytes.get().length)
                                    + " live=" + live.length + ")");
                }
                default -> helper.fail("unexpected parity test step " + step.get());
            }
        });
    }
}
