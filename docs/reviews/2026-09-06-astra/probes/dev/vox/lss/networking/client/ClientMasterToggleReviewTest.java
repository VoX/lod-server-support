package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.config.LSSClientConfig;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** External review probes: assert intended UI semantics; expected RED on merged1b544494.
 * No server, cache IO, executor drain, or repository mutations. tickWithContext is the
 * production manager.tick body behind Minecraft player/level guards; production tick
 * and ClientNetGlue.onEndClientTick have no receiveServerLods guard either.
 */
class ClientMasterToggleReviewTest {
    private static final ResourceKey<Level> DIM = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("lss_review:toggle"));

    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static LodRequestManager manager(List<long[]> sent) {
        var m = new LodRequestManager(new RegionScanner());
        m.transferGovernorEnabled = () -> false;
        m.joinSlowStartEnabled = () -> false;
        m.scannerForTest().adaptiveCadenceEnabled = () -> false;
        m.onSessionConfig(new SessionConfigS2CPayload(LSSConstants.PROTOCOL_VERSION,
                true, 32, false), "lss-review04-no-io");
        m.markCacheLoadedForTest();
        m.setLastDimensionForTest(DIM);
        m.setBatchSenderForTest(p -> sent.add(java.util.Arrays.copyOf(p.packedPositions(), p.count())));
        return m;
    }

    private static void tick(LodRequestManager m) {
        m.tickWithContext(0, 0, DIM, 0, 0, 0L, -1, () -> 0);
    }

    @Test void turningOffEstablishedSessionStopsNonemptyDeclarations() {
        boolean oldReceive = LSSClientConfig.CONFIG.receiveServerLods;
        int oldCap = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
        try {
            LSSClientConfig.CONFIG.receiveServerLods = true;
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = 1;
            var sent = new ArrayList<long[]>();
            var m = manager(sent);
            tick(m);
            assertEquals(1, sent.size(), "premise: established session has declared work");
            sent.clear();
            LSSClientConfig.CONFIG.receiveServerLods = false;
            for (int i = 0; i < 40; i++) tick(m);
            long nonempty = sent.stream().filter(p -> p.length > 0).count();
            assertEquals(0, nonempty,
                    "OFF promises to stop downloads, but manager keeps nonempty want-sets flowing");
        } finally {
            LSSClientConfig.CONFIG.receiveServerLods = oldReceive;
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = oldCap;
        }
    }

    @Test void temporaryOffMustNotExhaustIngestRetriesAndStrandTerrainOnReenable() {
        boolean oldReceive = LSSClientConfig.CONFIG.receiveServerLods;
        int oldCap = LSSClientConfig.CONFIG.lodColumnsPerSecondLimit;
        try {
            LSSClientConfig.CONFIG.receiveServerLods = true;
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = 1;
            var sent = new ArrayList<long[]>();
            var m = manager(sent);
            tick(m);
            long pos = sent.get(0)[0];
            var processor = new ClientColumnProcessor((d, x, z) ->
                    m.onIngestFailure(d, PositionUtil.packPosition(x, z)), () -> null);
            LSSClientConfig.CONFIG.receiveServerLods = false;
            for (int delivery = 0; delivery <= ColumnStateMap.MAX_INGEST_FAILURES; delivery++) {
                if (delivery > 0) for (int i = 0; i < 20; i++) tick(m);
                assertTrue(m.trackerForTest().isInFlight(pos), "same failed position is re-declared");
                // Bytes deliberately never decoded: the OFF gate must clear before level lookup.
                ClientNetGlue.handleVoxelColumn(m, processor, new VoxelColumnS2CPayload(
                        PositionUtil.unpackX(pos), PositionUtil.unpackZ(pos), DIM,
                        5000L + delivery, new byte[]{1}));
                processor.scheduleProcessing(true);
                assertEquals(0, processor.getQueuedCount());
            }
            assertEquals(1, m.getIngestParkedCount(), "premise: four OFF discards exhausted retry belt");
            LSSClientConfig.CONFIG.receiveServerLods = true;
            for (int i = 0; i < 20; i++) tick(m);
            assertNotEquals(ColumnStateMap.SATISFIED, m.columnsForTest().classify(pos),
                    "turning ON must not leave an undelivered position parked until rejoin/dirty");
        } finally {
            LSSClientConfig.CONFIG.receiveServerLods = oldReceive;
            LSSClientConfig.CONFIG.lodColumnsPerSecondLimit = oldCap;
        }
    }
}
