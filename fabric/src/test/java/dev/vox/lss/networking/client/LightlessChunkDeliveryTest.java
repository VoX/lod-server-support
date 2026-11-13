package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.compat.VoxyBridgeTestAccess;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import dev.vox.lss.networking.server.DiskSerializerTestAccess;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Issue #382 end to end: one Overworld chunk column through production code, from the
 * region-file chunk tag to what Voxy is handed.
 *
 * <pre>
 * chunk NBT -> NbtSectionSerializer.serializeChunkNbt (the server disk path)
 *           -> ClientColumnProcessor.drainColumnQueue (v20 translate, decode, sky estimate, fills)
 *           -> the real VoxyCompat bridge against the stub Voxy ingest service
 * </pre>
 * "What Voxy renders" follows Voxy's own rule ({@code VoxelIngestService.getLightingSupplier}:
 * a null or {@code isEmpty()} sky layer is sky 0).
 *
 * <p>The column: stone from the world bottom to y=54, grass at y=55 (local y 7 of section 3),
 * air above. A correctly lit vanilla save carries SkyLight on section 3 (15 above the grass)
 * and on the all-air section 4 (uniform 15), plus {@code isLightOn}.
 *
 * <p>Before the fix a chunk without light arrived with sky 0 in every terrain section (the
 * 2026-11-12 reproduction, unit and live). The server still sends it without light layers;
 * the client now gives it the light vanilla would compute, so a stripped chunk and a lit
 * chunk reach Voxy the same.
 */
class LightlessChunkDeliveryTest {

    private static RegistryAccess RA;
    private static PalettedContainerFactory FACTORY;
    private static final int MIN_Y = -4, MAX_Y = 19, SECTIONS = 24;
    private static final int SURFACE = 3;
    private static final ResourceKey<Level> DIM =
            ResourceKey.create(Registries.DIMENSION, Identifier.parse("minecraft:overworld"));

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider provider = VanillaRegistries.createLookup();
        HolderLookup.RegistryLookup<Biome> src = provider.lookupOrThrow(Registries.BIOME);
        MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
        src.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        RA = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        FACTORY = PalettedContainerFactory.create(RA);
    }

    @AfterEach
    void restore() {
        VoxyBridgeTestAccess.reset();
    }

    // ---- chunk NBT, in the shape vanilla's SerializableChunkData.write produces ----

    private static void fill(LevelChunkSection sec, int fromY, int toY, BlockState state) {
        for (int y = fromY; y <= toY; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) sec.setBlockState(x, y, z, state);
    }

    private static byte[] skyFromLocalY(int fromY) {
        var layer = new DataLayer(new byte[2048]);
        for (int y = fromY; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) layer.set(x, y, z, 15);
        return layer.getData();
    }

    private static CompoundTag litOverworldChunk() {
        var root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putString("Status", "minecraft:full");
        root.putInt("xPos", 0);
        root.putInt("zPos", 0);
        root.putInt("yPos", MIN_Y);
        root.putLong("LastUpdate", 0L);
        root.putLong("InhabitedTime", 0L);
        root.put("block_entities", new ListTag());
        root.put("Heightmaps", new CompoundTag());
        root.putBoolean("isLightOn", true);
        var sections = new ListTag();
        for (int y = MIN_Y; y <= MAX_Y; y++) {
            var sec = new LevelChunkSection(FACTORY);
            if (y < SURFACE) {
                fill(sec, 0, 15, Blocks.STONE.defaultBlockState());
            } else if (y == SURFACE) {
                fill(sec, 0, 6, Blocks.STONE.defaultBlockState());
                fill(sec, 7, 7, Blocks.GRASS_BLOCK.defaultBlockState());
            }
            var tag = new CompoundTag();
            tag.putByte("Y", (byte) y);
            tag.put("block_states", FACTORY.blockStatesContainerCodec()
                    .encodeStart(NbtOps.INSTANCE, sec.getStates()).getOrThrow());
            tag.put("biomes", FACTORY.biomeContainerCodec()
                    .encodeStart(NbtOps.INSTANCE, sec.getBiomes()).getOrThrow());
            // vanilla: an allocated-but-dark layer just under the surface, the mixed surface
            // layer, and the uniform-15 layer one section above the terrain (heightmap+1).
            if (y == SURFACE - 1) tag.putByteArray("SkyLight", new byte[2048]);
            if (y == SURFACE) tag.putByteArray("SkyLight", skyFromLocalY(8));
            if (y == SURFACE + 1) tag.putByteArray("SkyLight", skyFromLocalY(0));
            sections.add(tag);
        }
        root.put("sections", sections);
        return root;
    }

    /** Exactly what vanilla's ChunkDeleteLightFix does to a chunk tag. */
    private static CompoundTag stripLikeChunkDeleteLightFix(CompoundTag lit) {
        var c = lit.copy();
        c.remove("isLightOn");
        for (var e : c.getList("sections").orElseThrow()) {
            var s = (CompoundTag) e;
            s.remove("BlockLight");
            s.remove("SkyLight");
        }
        return c;
    }

    /** LINE FLAVOR (lines before 26.3): the same chunk as an older version saved it. The
     *  palette entries here already are the {Name, Properties} compounds every earlier format
     *  wrote (26.3 renamed the keys), so only the DataVersion changes. */
    private static CompoundTag asLegacyFormat(CompoundTag current, int dataVersion) {
        var c = current.copy();
        c.putInt("DataVersion", dataVersion);
        for (var e : c.getList("sections").orElseThrow()) {
            for (var entry : ((CompoundTag) e).getCompound("block_states").orElseThrow()
                    .getList("palette").orElseThrow()) {
                assertTrue(entry instanceof CompoundTag named && named.contains("Name"),
                        "premise: this line writes {Name, Properties} palette entries");
            }
        }
        return c;
    }

    private static boolean anyLightTag(CompoundTag chunk) {
        for (var e : chunk.getList("sections").orElseThrow()) {
            var s = (CompoundTag) e;
            if (s.contains("SkyLight") || s.contains("BlockLight")) return true;
        }
        return false;
    }

    // ---- the pipeline ----

    private record Delivered(byte[] wire, VoxelColumnData decoded, List<VoxelIngestService.IngestCall> voxy,
                             long estimated) {
        VoxelIngestService.IngestCall section(int y) {
            return voxy.stream().filter(c -> c.sectionY() == y).findFirst().orElse(null);
        }
    }

    private static Delivered deliver(String label, CompoundTag chunk, boolean transcode) {
        byte[] v20 = DiskSerializerTestAccess.serialize(chunk, RA, MIN_Y, MAX_Y, transcode);
        assertNotNull(v20, label + ": a FULL chunk must be servable from disk");
        assertTrue(v20.length > 0, label + ": the column has content");

        var processor = new ClientColumnProcessor(
                (d, cx, cz) -> fail(label + ": ingest failure reported"), () -> null);
        var resolver = new ClientIdentityResolver(RA.lookupOrThrow(Registries.BIOME),
                FACTORY.biomeStrategy().globalMap());
        var out = new ArrayList<VoxelColumnData>();
        processor.offer(new VoxelColumnS2CPayload(0, 0, DIM, 1L, v20), false);
        processor.drainColumnQueue(DIM, SECTIONS, MIN_Y, true, FACTORY, resolver::toNative,
                (d, cx, cz, data) -> out.add(data), processor.sessionEpochForTest());
        assertEquals(1, out.size(), label + ": one column dispatched");

        var consumer = VoxyBridgeTestAccess.init();
        consumer.onVoxelColumnReceived(null, DIM, 0, 0, out.get(0));
        var calls = new ArrayList<>(VoxelIngestService.calls);
        calls.sort(Comparator.comparingInt(VoxelIngestService.IngestCall::sectionY));
        VoxyBridgeTestAccess.reset();
        return new Delivered(v20, out.get(0), calls, processor.getSkyEstimatedColumns());
    }

    /** Voxy 0.2.20 VoxelIngestService.getLightingSupplier, sky half. */
    private static int voxySky(VoxelIngestService.IngestCall call, int x, int y, int z) {
        var sla = call.skyLight();
        return sla != null && !sla.isEmpty() ? Math.min(15, sla.get(x, y, z)) : 0;
    }

    private static int maxVoxySky(VoxelIngestService.IngestCall call) {
        int max = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++) max = Math.max(max, voxySky(call, x, y, z));
        return max;
    }

    /** Every section Voxy received carries the same sky light in both deliveries. */
    private static void assertSameSkyAtVoxy(Delivered expected, Delivered actual, String label) {
        assertEquals(expected.voxy().size(), actual.voxy().size(), label + ": the same sections reach Voxy");
        for (var want : expected.voxy()) {
            var got = actual.section(want.sectionY());
            assertNotNull(got, label + ": section " + want.sectionY());
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        assertEquals(voxySky(want, x, y, z), voxySky(got, x, y, z),
                                label + ": section " + want.sectionY() + " x=" + x + " y=" + y + " z=" + z);
                    }
        }
    }

    // ---- 1. control: a correctly lit save reaches Voxy lit, untouched by the estimate ----

    @Test
    void aLitChunkReachesVoxyWithItsOwnLight() {
        for (boolean transcode : new boolean[]{true, false}) {
            var d = deliver("lit control", litOverworldChunk(), transcode);
            assertEquals(15, voxySky(d.section(SURFACE), 8, 8, 8), "air above the grass is sky 15");
            assertEquals(0, voxySky(d.section(SURFACE), 8, 3, 8), "inside the stone is sky 0");
            assertEquals(15, voxySky(d.section(SURFACE + 1), 8, 8, 8), "heightmap+1 layer served");
            assertEquals(15, voxySky(d.section(MAX_Y), 8, 8, 8), "implicit sky above");
            assertEquals(0, d.estimated(), "real light is never estimated over");
        }
    }

    // ---- 2. the same chunk with its light stripped ----

    @Test
    void aChunkStrippedOfItsLightReachesVoxyLitLikeTheLitChunk() {
        var stripped = stripLikeChunkDeleteLightFix(litOverworldChunk());
        assertFalse(stripped.contains("isLightOn"));
        assertFalse(anyLightTag(stripped));
        for (boolean transcode : new boolean[]{true, false}) {
            var lit = deliver("lit control", litOverworldChunk(), transcode);
            var d = deliver("stripped (no SkyLight/BlockLight/isLightOn)", stripped, transcode);

            // The server side is unchanged: the same blocks, and no light on the wire.
            assertTrue(d.wire().length < lit.wire().length, "the stripped column is sent without light layers");
            var content = Arrays.stream(d.decoded().sections())
                    .filter(s -> !s.section().hasOnlyAir()).toList();
            assertEquals(SURFACE - MIN_Y + 1, content.size(), "every terrain section is served");
            for (var s : content) assertNull(s.blockLight(), "no block light is invented, section " + s.sectionY());

            // The client estimate: daylight on the open air above the grass, dark stone.
            assertEquals(1, d.estimated());
            assertEquals(15, voxySky(d.section(SURFACE), 8, 8, 8),
                    "open air above the surface is sky 15 (it was 0 before the fix)");
            assertEquals(15, voxySky(d.section(SURFACE), 0, 15, 15));
            assertEquals(0, voxySky(d.section(SURFACE), 8, 7, 8), "the grass block itself");
            assertEquals(0, voxySky(d.section(SURFACE), 8, 3, 8), "inside the stone");
            for (int y = MIN_Y; y < SURFACE; y++) {
                assertEquals(0, maxVoxySky(d.section(y)), "deep stone section " + y + " stays dark");
            }
            assertEquals(15, voxySky(d.section(SURFACE + 1), 8, 8, 8), "the air section above the terrain");
            assertEquals(15, voxySky(d.section(MAX_Y), 8, 8, 8), "implicit sky above");
            assertSameSkyAtVoxy(lit, d, "stripped vs lit (transcode=" + transcode + ")");
        }
    }

    // ---- 3. vanilla's own DataFixer produces exactly that shape ----

    @Test
    void aChunkUpgradedByVanillasDataFixerFromBefore1_21_9LosesItsLightAndIsLitAgainByTheClient() {
        int current = SharedConstants.getCurrentVersion().dataVersion().version();
        var fixer = DataFixers.getDataFixer();

        // 1.21.8 (DataVersion 4440) is below ChunkDeleteLightFix's second registration (4537):
        // this is what Optimize World / --forceUpgrade writes for such a chunk.
        var from1218 = DataFixTypes.CHUNK.update(fixer, asLegacyFormat(litOverworldChunk(), 4440), 4440, current);
        assertEquals("minecraft:full", from1218.getStringOr("Status", "?"));
        assertFalse(from1218.contains("isLightOn"), "ChunkDeleteLightFix removed isLightOn");
        assertFalse(anyLightTag(from1218), "ChunkDeleteLightFix removed every SkyLight/BlockLight");
        var upgraded = deliver("DFU-upgraded 1.21.8 chunk", from1218, true);
        assertEquals(1, upgraded.estimated());
        assertEquals(15, voxySky(upgraded.section(SURFACE), 8, 8, 8), "lit again (sky 0 before the fix)");
        assertEquals(0, voxySky(upgraded.section(SURFACE), 8, 3, 8));

        // 1.21.10 (DataVersion 4556) is past both registrations: light survives the upgrade
        // and the estimate stays out of it.
        var from12110 = DataFixTypes.CHUNK.update(fixer, asLegacyFormat(litOverworldChunk(), 4556), 4556, current);
        assertTrue(from12110.contains("isLightOn"));
        assertTrue(anyLightTag(from12110));
        var kept = deliver("DFU-upgraded 1.21.10 chunk (control)", from12110, true);
        assertEquals(0, kept.estimated());
        assertEquals(15, voxySky(kept.section(SURFACE), 8, 8, 8));
        assertSameSkyAtVoxy(kept, upgraded, "upgraded-and-stripped vs upgraded-and-lit");
    }

    // ---- 4. isLightOn alone is not a usable signal ----

    @Test
    void aStarlightShapedChunkWithIsLightOnFalseKeepsItsOwnLight() {
        // Starlight / Moonrise / ScalableLux deliberately write isLightOn=false on LIT chunks
        // (so vanilla relights if their data is ever read by it) and keep SkyLight/BlockLight.
        var chunk = litOverworldChunk();
        chunk.putBoolean("isLightOn", false);
        var d = deliver("isLightOn=false but light arrays present", chunk, true);
        assertEquals(15, voxySky(d.section(SURFACE), 8, 8, 8));
        assertEquals(0, d.estimated());
    }
}
