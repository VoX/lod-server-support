package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.voxel.SkyLightEstimate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #382, the Minecraft side of the sky-light estimate. The rule itself is pinned on
 * hand-built columns in {@code SkyLightEstimateTest} (common); here the same routine runs
 * on real block states and is compared with vanilla's own light engine: a single chunk,
 * lit by {@link LevelLightEngine} with no neighbours, is exactly "vanilla's rule
 * restricted to the column", so every stored sky layer must be equal nibble for nibble.
 */
class ClientSkyLightEstimatorTest {
    private static PalettedContainerFactory FACTORY;
    /** The test level: sections -2..5 (blocks -32..95). */
    private static final int MIN_SECTION = -2, SECTIONS = 8, MIN_Y = MIN_SECTION * 16, HEIGHT = SECTIONS * 16;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var source = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        biomes.register(Biomes.PLAINS, source.getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        biomes.freeze();
        FACTORY = PalettedContainerFactory.create(new RegistryAccess.ImmutableRegistryAccess(List.of(biomes)));
    }

    // ---- a chunk both sides can read ----

    private static final class Chunk {
        final ProtoChunk chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY,
                LevelHeightAccessor.create(MIN_Y, HEIGHT), FACTORY, null);

        Chunk set(int x, int y, int z, BlockState state) {
            chunk.getSection(chunk.getSectionIndex(y)).setBlockState(x, y & 15, z, state, false);
            return this;
        }

        Chunk box(int x0, int x1, int y0, int y1, int z0, int z1, BlockState state) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) set(x, y, z, state);
            return this;
        }

        Chunk layers(int y0, int y1, BlockState state) { return box(0, 15, y0, y1, 0, 15, state); }

        /** The column as the client decodes it: the sections that hold blocks, no light. */
        VoxelColumnData.SectionData[] served() {
            var out = new ArrayList<VoxelColumnData.SectionData>();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                LevelChunkSection section = chunk.getSection(i);
                if (!section.hasOnlyAir()) {
                    out.add(new VoxelColumnData.SectionData(chunk.getSectionYFromSectionIndex(i), section, null, null));
                }
            }
            return out.toArray(new VoxelColumnData.SectionData[0]);
        }

        /** Vanilla's sky layers for this chunk alone, by section Y (only the stored ones). */
        Map<Integer, DataLayer> vanillaSky() {
            chunk.initializeLightSources();
            var getter = new LightChunkGetter() {
                @Override public LightChunk getChunkForLighting(int x, int z) { return x == 0 && z == 0 ? chunk : null; }
                @Override public BlockGetter getLevel() { return chunk; }
            };
            var engine = new LevelLightEngine(getter, false, true);
            ChunkPos pos = chunk.getPos();
            // ThreadedLevelLightEngine.initializeLight, then lightChunk, for an unlit chunk.
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!chunk.getSection(i).hasOnlyAir()) {
                    engine.updateSectionStatus(SectionPos.of(pos, chunk.getSectionYFromSectionIndex(i)), false);
                }
            }
            engine.runLightUpdates();
            engine.setLightEnabled(pos, false);
            engine.propagateLightSources(pos);
            for (int i = 0; i < 64 && engine.hasLightWork(); i++) engine.runLightUpdates();
            assertFalse(engine.hasLightWork(), "vanilla's engine must settle");
            var out = new HashMap<Integer, DataLayer>();
            for (int y = MIN_SECTION - 1; y <= MIN_SECTION + SECTIONS; y++) {
                DataLayer layer = engine.getLayerListener(LightLayer.SKY).getDataLayerData(SectionPos.of(pos, y));
                if (layer != null) out.put(y, layer);
            }
            return out;
        }
    }

    private static Map<Integer, VoxelColumnData.SectionData> byY(VoxelColumnData.SectionData[] sections) {
        var out = new HashMap<Integer, VoxelColumnData.SectionData>();
        for (var s : sections) out.put(s.sectionY(), s);
        return out;
    }

    private static ClientSkyLightEstimator.Result estimate(Chunk chunk) {
        var served = chunk.served();
        assertTrue(ClientSkyLightEstimator.lacksSkyLight(served));
        return new ClientSkyLightEstimator().estimate(served, SECTIONS, MIN_SECTION, FACTORY);
    }

    /** Every section vanilla stores inside the level equals the estimate, nibble for nibble. */
    private static void assertEqualsVanilla(String label, Chunk chunk) {
        var vanilla = chunk.vanillaSky();
        var result = estimate(chunk);
        var mine = result == null ? Map.<Integer, VoxelColumnData.SectionData>of() : byY(result.sections());
        for (int y = MIN_SECTION; y < MIN_SECTION + SECTIONS; y++) {
            DataLayer want = vanilla.get(y);
            var section = mine.get(y);
            DataLayer got = section == null ? null : section.skyLight();
            if (want == null) {
                assertNull(got, label + ": section " + y + " is not stored by vanilla, so nothing may be sent for it");
                continue;
            }
            for (int cy = 0; cy < 16; cy++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int expected = want.get(x, cy, z);
                int actual = got == null ? 0 : got.get(x, cy, z);
                if (expected != actual) {
                    assertEquals(expected, actual, label + ": section " + y + " x=" + x + " y=" + cy + " z=" + z
                            + " (" + chunk.chunk.getBlockState(new BlockPos(x, y * 16 + cy, z)) + ")");
                }
            }
        }
        if (result != null) assertFalse(result.capped(), label);
    }

    private static BlockState stairs(Direction facing, Half half) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
    }

    private static BlockState slab(SlabType type) {
        return Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, type);
    }

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // ---- vanilla parity ----

    @Test
    void handBuiltShapesMatchVanillasLightEngine() {
        assertEqualsVanilla("flat ground", new Chunk().layers(MIN_Y, 7, STONE));
        assertEqualsVanilla("leaf canopy", new Chunk().layers(MIN_Y, 3, STONE)
                .box(2, 13, 20, 22, 2, 13, Blocks.OAK_LEAVES.defaultBlockState()));
        assertEqualsVanilla("pool", new Chunk().layers(MIN_Y, 20, STONE)
                .box(3, 12, 9, 20, 3, 12, Blocks.WATER.defaultBlockState()));
        assertEqualsVanilla("ice on water", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(4, 12, Blocks.WATER.defaultBlockState()).layers(13, 13, Blocks.ICE.defaultBlockState()));
        assertEqualsVanilla("overhang", new Chunk().layers(MIN_Y, 3, STONE).box(0, 7, 20, 20, 0, 15, STONE));
        assertEqualsVanilla("bottom slab roof", new Chunk().layers(MIN_Y, 3, STONE).layers(20, 20, slab(SlabType.BOTTOM)));
        assertEqualsVanilla("top slab roof with a hole", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(20, 20, slab(SlabType.TOP)).set(8, 20, 8, AIR));
        assertEqualsVanilla("top slabs beside bottom slabs", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(10, 10, slab(SlabType.TOP)).box(8, 15, 10, 10, 0, 15, slab(SlabType.BOTTOM)));
        assertEqualsVanilla("slab on a section floor over an all-air section", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(32, 32, slab(SlabType.BOTTOM)));
        assertEqualsVanilla("glass roof", new Chunk().layers(MIN_Y, 3, STONE).layers(20, 20, Blocks.GLASS.defaultBlockState()));
        assertEqualsVanilla("tinted glass roof with a hole", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(20, 20, Blocks.TINTED_GLASS.defaultBlockState()).set(3, 20, 12, AIR));
        assertEqualsVanilla("cave mouth", new Chunk().layers(MIN_Y, 39, STONE)
                .box(8, 8, 20, 39, 8, 8, AIR).box(1, 14, 16, 19, 8, 8, AIR).box(4, 4, 16, 19, 1, 14, AIR));
        assertEqualsVanilla("blocks in the level's top section", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(95, 95, Blocks.OAK_LEAVES.defaultBlockState()).box(0, 7, 90, 90, 0, 15, STONE));
        assertEqualsVanilla("blocks in the level's bottom section only", new Chunk()
                .box(0, 7, MIN_Y, MIN_Y + 2, 0, 15, STONE).box(8, 15, MIN_Y + 6, MIN_Y + 6, 0, 15, slab(SlabType.TOP)));
        assertEqualsVanilla("sky platform three sections above the ground", new Chunk().layers(MIN_Y, 3, STONE)
                .box(0, 9, 70, 70, 0, 9, STONE).box(3, 4, 70, 70, 3, 4, Blocks.OAK_LEAVES.defaultBlockState()));
        assertEqualsVanilla("stairs that pair up", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(10, 10, STONE).box(4, 11, 10, 10, 4, 11, AIR)
                .box(4, 11, 9, 9, 4, 11, stairs(Direction.EAST, Half.BOTTOM))
                .box(4, 11, 9, 9, 6, 6, stairs(Direction.WEST, Half.TOP))
                .box(4, 11, 9, 9, 9, 9, stairs(Direction.NORTH, Half.TOP))
                .box(6, 6, 9, 9, 4, 11, stairs(Direction.SOUTH, Half.BOTTOM)));
        assertEqualsVanilla("snow, farmland and path", new Chunk().layers(MIN_Y, 3, STONE)
                .layers(4, 4, Blocks.FARMLAND.defaultBlockState()).box(0, 7, 4, 4, 0, 15, Blocks.DIRT_PATH.defaultBlockState())
                .box(0, 15, 5, 5, 0, 7, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3))
                .box(0, 15, 5, 5, 8, 11, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8)));
    }

    @Test
    void randomTerrainMatchesVanillasLightEngine() {
        var random = new Random(382);
        var palette = new ArrayList<BlockState>(List.of(STONE, STONE, STONE, AIR, AIR,
                Blocks.OAK_LEAVES.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.ICE.defaultBlockState(),
                Blocks.TINTED_GLASS.defaultBlockState(), Blocks.COBWEB.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(), Blocks.TORCH.defaultBlockState(),
                Blocks.FARMLAND.defaultBlockState(), Blocks.DIRT_PATH.defaultBlockState(),
                Blocks.HONEY_BLOCK.defaultBlockState(), Blocks.SLIME_BLOCK.defaultBlockState(),
                Blocks.MANGROVE_ROOTS.defaultBlockState(), Blocks.SCAFFOLDING.defaultBlockState(),
                Blocks.ENCHANTING_TABLE.defaultBlockState(), Blocks.DAYLIGHT_DETECTOR.defaultBlockState(),
                Blocks.CHEST.defaultBlockState(), Blocks.SPAWNER.defaultBlockState(),
                Blocks.POWDER_SNOW.defaultBlockState(), Blocks.LAVA.defaultBlockState(),
                slab(SlabType.BOTTOM), slab(SlabType.TOP), slab(SlabType.DOUBLE),
                slab(SlabType.BOTTOM).setValue(BlockStateProperties.WATERLOGGED, true)));
        for (int layers = 1; layers <= 8; layers++) {
            palette.add(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
        }
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (Half half : Half.values()) palette.add(stairs(facing, half));
        }
        for (int round = 0; round < 40; round++) {
            var chunk = new Chunk();
            int base = MIN_Y + 8 + random.nextInt(HEIGHT - 24);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int surface = Math.max(MIN_Y, Math.min(MIN_Y + HEIGHT - 1, base + random.nextInt(11) - 5));
                for (int y = MIN_Y; y <= surface; y++) chunk.set(x, y, z, STONE);
            }
            int features = 6 + random.nextInt(24);
            for (int feature = 0; feature < features; feature++) {
                int x0 = random.nextInt(16), z0 = random.nextInt(16), y0 = MIN_Y + random.nextInt(HEIGHT);
                int x1 = Math.min(15, x0 + random.nextInt(9)), z1 = Math.min(15, z0 + random.nextInt(9));
                int y1 = Math.min(MIN_Y + HEIGHT - 1, y0 + random.nextInt(5));
                chunk.box(x0, x1, y0, y1, z0, z1, palette.get(random.nextInt(palette.size())));
            }
            assertEqualsVanilla("random chunk " + round, chunk);
        }
    }

    // ---- what a block state says about itself ----

    private static int describe(BlockState state) {
        return ClientSkyLightEstimator.describe(state, ClientSkyLightEstimator.VANILLA);
    }

    private static final int SIDES = 1 << SkyLightEstimate.NORTH | 1 << SkyLightEstimate.SOUTH
            | 1 << SkyLightEstimate.WEST | 1 << SkyLightEstimate.EAST;

    @Test
    void descriptorsCarryTheStatesOwnLightProperties() {
        assertEquals(SkyLightEstimate.AIR, describe(AIR));
        assertEquals(SkyLightEstimate.descriptor(15, 0, 0), describe(STONE), "a full cube stops light by dampening, not by shape");
        assertEquals(SkyLightEstimate.descriptor(1, 0, 0), describe(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals(SkyLightEstimate.descriptor(1, 0, 0), describe(Blocks.WATER.defaultBlockState()));
        assertEquals(SkyLightEstimate.descriptor(0, 0, 0), describe(Blocks.GLASS.defaultBlockState()));
        assertEquals(SkyLightEstimate.descriptor(15, 0, 0), describe(Blocks.TINTED_GLASS.defaultBlockState()));
        assertEquals(SkyLightEstimate.descriptor(0, 1 << SkyLightEstimate.DOWN, SIDES), describe(slab(SlabType.BOTTOM)),
                "a bottom slab: no dampening, a closed bottom face, half side faces, an open top");
        assertEquals(SkyLightEstimate.descriptor(0, 1 << SkyLightEstimate.UP, SIDES), describe(slab(SlabType.TOP)));
        assertEquals(15, describe(slab(SlabType.DOUBLE)) & 15);
        int stair = describe(stairs(Direction.EAST, Half.BOTTOM));
        assertEquals(0, stair & 15);
        assertEquals(1 << SkyLightEstimate.DOWN | 1 << SkyLightEstimate.EAST, stair >>> 4 & 0x3F,
                "a bottom stair facing east closes its bottom and its east face");
        assertEquals(1 << SkyLightEstimate.UP | 1 << SkyLightEstimate.NORTH | 1 << SkyLightEstimate.SOUTH
                | 1 << SkyLightEstimate.WEST, stair >>> 10 & 0x3F, "and covers part of the other four");
    }

    @Test
    void aStateWhoseLightOrShapeMethodThrowsIsTreatedAsOpaque() {
        var glass = Blocks.GLASS.defaultBlockState();
        var chunk = new Chunk().layers(MIN_Y, 3, STONE).layers(20, 20, glass);
        var plain = byY(estimate(chunk).sections());
        assertEquals(15, plain.get(0).skyLight().get(5, 10, 5), "premise: light passes the glass roof");

        for (boolean inShape : new boolean[]{false, true}) {
            var throwing = new ClientSkyLightEstimator.StateLight() {
                @Override public int dampening(BlockState state) {
                    if (!inShape && state == glass) throw new IllegalStateException("modded light method");
                    return ClientSkyLightEstimator.VANILLA.dampening(state);
                }
                @Override public boolean occludesByShape(BlockState state) {
                    return state == glass || ClientSkyLightEstimator.VANILLA.occludesByShape(state);
                }
                @Override public VoxelShape faceShape(BlockState state, Direction direction) {
                    if (inShape && state == glass) throw new UnsupportedOperationException("modded shape method");
                    return ClientSkyLightEstimator.VANILLA.faceShape(state, direction);
                }
            };
            assertEquals(SkyLightEstimate.OPAQUE, ClientSkyLightEstimator.describe(glass, throwing));
            var result = new ClientSkyLightEstimator(throwing).estimate(chunk.served(), SECTIONS, MIN_SECTION, FACTORY);
            var lit = byY(result.sections());
            assertEquals(15, lit.get(1).skyLight().get(5, 5, 5), "above the roof (y=21) is open sky");
            assertEquals(0, lit.get(1).skyLight().get(5, 4, 5), "the failing block takes no light");
            assertNull(lit.get(0).skyLight(), "and passes none: the room below stays dark");
            assertNull(lit.get(-2).skyLight());
        }
    }

    // ---- which sections receive a layer ----

    @Test
    void airIsAddedOnlyDirectlyBesideContentAndOnlyWhenItReceivedLight() {
        // Ground in sections -2..0, a platform in section 4 (y 70): sections 1..3 are an air gap.
        var chunk = new Chunk().layers(MIN_Y, 3, STONE).box(0, 9, 70, 70, 0, 9, STONE);
        var result = estimate(chunk);
        var lit = byY(result.sections());
        assertEquals(java.util.Set.of(-2, -1, 0, 1, 3, 4, 5), lit.keySet(),
                "air sections 1 (above the ground), 3 (under the platform) and 5 (above it) are added; 2 is not");
        assertEquals(3, result.addedSections());
        assertEquals(5, result.litSections(), "sections 0, 1, 3, 4 and 5 carry light");
        assertNull(lit.get(-2).skyLight(), "deep stone receives nothing");
        assertNull(lit.get(-1).skyLight());
        for (int y : new int[]{1, 3, 5}) {
            assertTrue(lit.get(y).section().hasOnlyAir());
            assertNull(lit.get(y).blockLight());
            assertNotNull(lit.get(y).skyLight());
        }
        for (byte b : lit.get(5).skyLight().getData()) assertEquals((byte) 0xFF, b, "above the top content: uniform 15");
        assertEquals(15, lit.get(3).skyLight().get(12, 3, 12), "beside the platform's shadow");
        assertEquals(14, lit.get(3).skyLight().get(9, 3, 9), "one step into the shadow");
        assertEquals(5, lit.get(3).skyLight().get(0, 3, 0), "the far corner under the platform: 15 - 10");
    }

    @Test
    void noSectionIsAddedOutsideTheLevel() {
        // Blocks in the level's top and bottom sections: the air above and below is outside the level.
        var chunk = new Chunk().box(0, 7, MIN_Y, MIN_Y + 2, 0, 15, STONE).box(0, 15, 90, 90, 0, 7, STONE);
        var lit = byY(estimate(chunk).sections());
        assertFalse(lit.containsKey(MIN_SECTION - 1));
        assertFalse(lit.containsKey(MIN_SECTION + SECTIONS));
        assertEquals(java.util.Set.of(-2, -1, 4, 5), lit.keySet(), "content, plus the air beside it inside the level");
        assertEquals(15, lit.get(5).skyLight().get(3, 15, 3), "the level's top block row is open to the sky above it");
        assertEquals(0, lit.get(5).skyLight().get(3, 10, 3), "the stone itself");
        assertEquals(10, lit.get(5).skyLight().get(3, 9, 3), "under it, lit from its open side: 15 - 5");
        assertEquals(15, lit.get(5).skyLight().get(3, 9, 12));
    }

    @Test
    void aColumnWithoutAnyOpenCellIsLeftUnchanged() {
        var chunk = new Chunk().layers(MIN_Y, MIN_Y + HEIGHT - 1, STONE);
        assertNull(estimate(chunk), "solid to the level top: true sky light is zero, nothing to add");
        var hollow = new Chunk().layers(MIN_Y, 3, STONE).layers(95, 95, STONE);
        assertNull(estimate(hollow), "a solid lid in the top block row: the room below is dark, as in vanilla");
    }

    @Test
    void aSectionOutsideTheClientLevelStillShadesTheColumn() {
        // A server world taller than the client level delivers a section above the level top.
        var above = new LevelChunkSection(FACTORY);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) above.setBlockState(x, 0, z, STONE, false);
        var served = new ArrayList<>(List.of(new Chunk().layers(MIN_Y, 3, STONE).served()));
        served.add(new VoxelColumnData.SectionData(MIN_SECTION + SECTIONS, above, null, null));
        var sections = served.toArray(new VoxelColumnData.SectionData[0]);
        assertTrue(ClientSkyLightEstimator.lacksSkyLight(sections));
        var result = new ClientSkyLightEstimator().estimate(sections, SECTIONS, MIN_SECTION, FACTORY);
        var lit = byY(result.sections());
        assertEquals(15, lit.get(MIN_SECTION + SECTIONS).skyLight().get(4, 1, 4), "the section that was sent is lit above its lid");
        assertNull(lit.get(0).skyLight(), "the ground under the lid stays dark");
        assertFalse(lit.containsKey(1), "no air section is added under it either: it received nothing");
    }

    // ---- review fold: the wire's block count is not trusted ----

    /** The same blocks with the header's block count set to zero, as a faulty server could send them. */
    private static LevelChunkSection withZeroBlockCount(LevelChunkSection section) {
        var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            section.write(buf);
            buf.setShort(0, 0);   // the header starts with the non-empty block count
            var lying = new LevelChunkSection(FACTORY);
            lying.read(buf);
            return lying;
        } finally {
            buf.release();
        }
    }

    @Test
    void aSectionWithBlocksAndAZeroBlockCountIsNotLitThrough() {
        var roof = new LevelChunkSection(FACTORY);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) roof.setBlockState(x, 5, z, STONE, false);
        var lying = withZeroBlockCount(roof);
        assertTrue(lying.hasOnlyAir(), "premise: the header says the section is empty");
        assertEquals(STONE, lying.getBlockState(3, 5, 3), "premise: the blocks are there");
        assertFalse(ClientSkyLightEstimator.holdsOnlyAir(lying));
        assertTrue(ClientSkyLightEstimator.holdsOnlyAir(new LevelChunkSection(FACTORY)));

        // Ground in sections -2..0 and the roof two sections above it.
        var served = new ArrayList<>(List.of(new Chunk().layers(MIN_Y, 3, STONE).served()));
        served.add(new VoxelColumnData.SectionData(2, lying, null, null));
        var sections = served.toArray(new VoxelColumnData.SectionData[0]);
        var lit = byY(new ClientSkyLightEstimator().estimate(sections, SECTIONS, MIN_SECTION, FACTORY).sections());
        assertEquals(15, lit.get(2).skyLight().get(3, 6, 3), "above the roof");
        assertEquals(0, lit.get(2).skyLight().get(3, 5, 3), "the roof");
        assertEquals(0, lit.get(2).skyLight().get(3, 4, 3), "under it");
        assertNull(lit.get(0).skyLight(), "the ground under the roof stays dark: the sky does not come through it");
        assertFalse(lit.containsKey(1), "and no lit air section is added between them");
        assertTrue(lit.get(3).section().hasOnlyAir(), "the air section above the roof is added, as above any content");
        for (byte b : lit.get(3).skyLight().getData()) assertEquals((byte) 0xFF, b);

        // The predicate agrees: a column whose only blocks hide behind a zero count has blocks.
        assertTrue(ClientSkyLightEstimator.lacksSkyLight(new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(2, lying, null, null)}));
    }

    @Test
    void anEmptySectionWhosePaletteStillListsABlockIsReadCellByCellAndStaysAir() {
        // A block set and removed again: the count is back at zero, the palette keeps the entry.
        var emptied = new LevelChunkSection(FACTORY);
        emptied.setBlockState(1, 1, 1, STONE, false);
        emptied.setBlockState(1, 1, 1, AIR, false);
        assertTrue(emptied.hasOnlyAir());
        assertFalse(ClientSkyLightEstimator.holdsOnlyAir(emptied), "the palette cannot rule a block out");
        var served = new ArrayList<>(List.of(new Chunk().layers(MIN_Y, 3, STONE).served()));
        served.add(new VoxelColumnData.SectionData(2, emptied, null, null));
        var lit = byY(new ClientSkyLightEstimator().estimate(served.toArray(new VoxelColumnData.SectionData[0]),
                SECTIONS, MIN_SECTION, FACTORY).sections());
        assertEquals(15, lit.get(0).skyLight().get(3, 4, 3), "the light still reaches the ground: every cell of it is air");
        for (byte b : lit.get(2).skyLight().getData()) assertEquals((byte) 0xFF, b);
    }

    // ---- review fold: a pair of partial faces is compared once per pair of states ----

    @Test
    void touchingPartialFacesAreComparedOncePerPairOfStatesInAColumn() {
        var faceCalls = new int[1];
        var counting = new ClientSkyLightEstimator.StateLight() {
            @Override public int dampening(BlockState state) { return ClientSkyLightEstimator.VANILLA.dampening(state); }
            @Override public boolean occludesByShape(BlockState state) { return ClientSkyLightEstimator.VANILLA.occludesByShape(state); }
            @Override public VoxelShape faceShape(BlockState state, Direction direction) {
                faceCalls[0]++;
                return ClientSkyLightEstimator.VANILLA.faceShape(state, direction);
            }
        };
        // A top-slab roof with one hole: the light walks from slab to slab, hundreds of pairs.
        var chunk = new Chunk().layers(MIN_Y, 3, STONE).layers(20, 20, slab(SlabType.TOP)).set(8, 20, 8, AIR);
        var result = new ClientSkyLightEstimator(counting).estimate(chunk.served(), SECTIONS, MIN_SECTION, FACTORY);
        var lit = byY(result.sections());
        assertEquals(13, lit.get(1).skyLight().get(6, 4, 8), "premise: slab to slab is open (y=20 is local 4 of section 1)");
        // Six faces to describe the slab, then two faces for each decided pair: slab beside
        // slab along x, and along z. Stone and air have no light-occluding shape.
        assertEquals(6 + 2 * 2, faceCalls[0], "every further pair of slabs is remembered, not compared again");
    }

    @Test
    void lacksSkyLightLooksForOneNonZeroNibbleInAColumnWithBlocks() {
        var ground = new Chunk().layers(MIN_Y, 3, STONE).served();
        assertTrue(ClientSkyLightEstimator.lacksSkyLight(ground));
        assertFalse(ClientSkyLightEstimator.lacksSkyLight(new VoxelColumnData.SectionData[0]), "a clear has no blocks");
        var airOnly = new VoxelColumnData.SectionData[]{
                new VoxelColumnData.SectionData(0, new LevelChunkSection(FACTORY), null, null)};
        assertFalse(ClientSkyLightEstimator.lacksSkyLight(airOnly), "an all-air column has no blocks");

        var zeroLayer = ground.clone();
        zeroLayer[0] = new VoxelColumnData.SectionData(ground[0].sectionY(), ground[0].section(), null,
                new DataLayer(new byte[2048]));
        assertTrue(ClientSkyLightEstimator.lacksSkyLight(zeroLayer), "a present all-zero layer counts as none");

        byte[] oneNibble = new byte[2048];
        oneNibble[2047] = 0x10;
        var lit = ground.clone();
        lit[0] = new VoxelColumnData.SectionData(ground[0].sectionY(), ground[0].section(), null, new DataLayer(oneNibble));
        assertFalse(ClientSkyLightEstimator.lacksSkyLight(lit), "one non-zero nibble is real light");
    }
}
