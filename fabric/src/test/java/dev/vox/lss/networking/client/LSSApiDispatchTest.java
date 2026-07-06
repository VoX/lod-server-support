package dev.vox.lss.networking.client;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins consumer isolation in LSSApi.dispatchColumn: the catch must stay catch(Throwable),
 * not catch(Exception) — incompatible LOD mods surface as LinkageError, and one broken
 * consumer must neither starve the others nor kill the LSS-ColumnProcessor drain loop.
 */
class LSSApiDispatchTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private record Received(int chunkX, int chunkZ, ResourceKey<Level> dimension, VoxelColumnData data) {}

    @Test
    void throwingConsumersDoNotStarveOthers() {
        var dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("lss_test:dispatch"));
        var columnData = new VoxelColumnData(new VoxelColumnData.SectionData[0], 42L);

        List<Received> first = new ArrayList<>();
        List<Received> last = new ArrayList<>();
        VoxelColumnConsumer firstRecorder = (level, dimension, cx, cz, data) ->
                first.add(new Received(cx, cz, dimension, data));
        VoxelColumnConsumer throwsException = (level, dimension, cx, cz, data) -> {
            throw new IllegalStateException("consumer bug");
        };
        VoxelColumnConsumer throwsLinkageError = (level, dimension, cx, cz, data) -> {
            throw new LinkageError("incompatible LOD mod");
        };
        VoxelColumnConsumer lastRecorder = (level, dimension, cx, cz, data) ->
                last.add(new Received(cx, cz, dimension, data));

        LSSApi.registerColumnConsumer(firstRecorder);
        LSSApi.registerColumnConsumer(throwsException);
        LSSApi.registerColumnConsumer(throwsLinkageError);
        LSSApi.registerColumnConsumer(lastRecorder);
        try {
            assertDoesNotThrow(() -> LSSApi.dispatchColumn(null, dim, 12, -34, columnData),
                    "dispatch must swallow consumer Throwables, including LinkageError");

            assertEquals(1, first.size(), "consumer before the throwing ones must receive the column");
            assertEquals(1, last.size(), "consumer after the throwing ones must still receive the column");
            var received = last.get(0);
            assertEquals(12, received.chunkX());
            assertEquals(-34, received.chunkZ());
            assertSame(dim, received.dimension());
            assertSame(columnData, received.data());
        } finally {
            LSSApi.removeColumnConsumer(firstRecorder);
            LSSApi.removeColumnConsumer(throwsException);
            LSSApi.removeColumnConsumer(throwsLinkageError);
            LSSApi.removeColumnConsumer(lastRecorder);
        }
    }
}
