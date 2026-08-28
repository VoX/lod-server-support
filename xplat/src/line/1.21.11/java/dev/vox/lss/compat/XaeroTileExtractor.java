// OVERLAY OF xplat/src/main/java/dev/vox/lss/compat/XaeroTileExtractor.java @ 3f4820ea208a4d5cc5838d88c5793fa44a597153ef6d4918eff021d6d47a2bec
// 1.21.11 line overlay (single-branch-consolidation-plan.md §3.2).
package dev.vox.lss.compat;

import dev.vox.lss.api.VoxelColumnData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure section→map-pixel computation for the Xaero's World Map bridge
 * (docs/planning/xaero-map-bridge-plan.md §5) — zero Xaero types, fully
 * unit-testable. Mirrors the decompiled {@code MapWriter.loadPixel} surface-mode
 * semantics (cave=false) against LSS's received {@link VoxelColumnData} instead of
 * a loaded {@code LevelChunk}:
 * <ul>
 *   <li>scan each (x,z) top-down from the highest non-air section's top;</li>
 *   <li>{@code topHeight} = Y of the first VISIBLE block (overlay or floor —
 *       invisible-skipped blocks never raise it; a void pixel keeps world
 *       bottom, the native shape);</li>
 *   <li>translucent runs (water, {@code TransparentBlock}s) become overlay runs
 *       with per-block light-dampening opacity, one run per contiguous same-state
 *       stretch, capped at {@value #MAX_OVERLAYS} — including the native "≥5
 *       transparent blocks → extend the run to the bottom of the transparency in
 *       one step" fast path;</li>
 *   <li>the floor is the first opaque block with a nonzero vanilla map color;
 *       its Y is the pixel height, block light at floor+1 is the pixel light
 *       (sky light is cave-mode-only in the native writer, so it never
 *       contributes here);</li>
 *   <li>biome sampled at {@code topHeight}; void pixels write AIR at world
 *       bottom with light 0 — which is also what ERASES stale map terrain when
 *       a resync serves an all-air column.</li>
 * </ul>
 * Two documented approximations of the native behavior (accepted by the plan
 * review): translucency is decided by {@code instanceof HalfTransparentBlock}
 * (ice/slime/honey + the glass family) / water-by-fluid-TYPE instead of Xaero's
 * model-quad sprite-alpha analysis (which needs the live model manager) — a
 * translucent-rendered block outside that family (nether portal is the vanilla
 * case) floors the pixel here where native overlays it; and Xaero's "flowers"
 * map option is mirrored at its default (flowers visible). Sky light is never
 * read — the native writer uses it only in cave mode — so no-sky dimensions
 * (nether/End) need no special casing.
 */
final class XaeroTileExtractor {

    /** Native {@code OverlayBuilder.MAX_OVERLAYS}. */
    static final int MAX_OVERLAYS = 10;
    /** Native threshold: a transparent run this deep extends to its bottom in one step. */
    private static final int EXTEND_RUN_DEPTH = 5;

    /** One translucent run above the floor — the inputs of Xaero's {@code Overlay}. */
    record OverlayRun(BlockState state, byte light, boolean glowing, int opacity) {}

    /**
     * One extracted chunk column, ready for a main-thread commit. Arrays are
     * 256-entry, x-major ({@code index = x * 16 + z}); {@code overlays} rows are
     * null for overlay-free pixels. Immutable by discipline — built once on the
     * decode thread, read once on the main client thread.
     */
    record PreparedTile(int chunkX, int chunkZ, int worldBottomY,
                        BlockState[] floorState, short[] floorY, short[] topY,
                        ResourceKey<Biome>[] biome, byte[] light, boolean[] glowing,
                        OverlayRun[][] overlays) {}

    private XaeroTileExtractor() {}

    /**
     * Extract a {@link PreparedTile} from a delivered column. Decode thread; touches
     * only the column's own sections/light. An all-air column yields a full tile of
     * void pixels (never null — the erase case is load-bearing).
     */
    @SuppressWarnings("unchecked")
    static PreparedTile extract(int chunkX, int chunkZ, int worldBottomY, int worldTopY,
                                VoxelColumnData columnData) {
        // sectionY-indexed lookup (sparse columns are normal — air sections are culled).
        int minSecY = worldBottomY >> 4;
        int maxSecY = (worldTopY - 1) >> 4;
        var byY = new VoxelColumnData.SectionData[maxSecY - minSecY + 1];
        int highestNonAir = Integer.MIN_VALUE;
        for (var s : columnData.sections()) {
            if (s == null || s.section() == null) continue;
            int idx = s.sectionY() - minSecY;
            if (idx < 0 || idx >= byY.length) continue;
            byY[idx] = s;
            if (!s.section().hasOnlyAir() && s.sectionY() > highestNonAir) {
                highestNonAir = s.sectionY();
            }
        }

        var floorState = new BlockState[256];
        var floorY = new short[256];
        var topY = new short[256];
        var biome = (ResourceKey<Biome>[]) new ResourceKey[256];
        var light = new byte[256];
        var glowing = new boolean[256];
        var overlays = new OverlayRun[256][];

        // Void column: startY < lowY, the scan body never runs, every pixel is void.
        int startY = highestNonAir == Integer.MIN_VALUE
                ? worldBottomY - 1
                : (highestNonAir << 4) + 15;

        var column = new ColumnAccess(byY, minSecY);
        var scan = new PixelScan();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                scan.reset(worldBottomY);
                scanPixel(column, x, z, startY, scan);
                int i = x * 16 + z;
                BlockState finalState = scan.floorState == null ? AIR : scan.floorState;
                floorState[i] = finalState;
                floorY[i] = (short) (scan.floorState == null ? worldBottomY : scan.floorY);
                // Xaero persists topHeight as ONE UNSIGNED BYTE (MapSaveLoad.savePixel) — a
                // negative topY (a visible block below y=0, common in far End/void terrain)
                // reloads wrong there. Native's own writer feeds the same field; cosmetic
                // (slope shading only) — sweep B m6, recorded, not worked around.
                topY[i] = (short) scan.topH;
                biome[i] = column.biomeAt(x, scan.topH, z);
                light[i] = scan.floorState == null ? 0 : scan.floorLight;
                glowing[i] = isGlowing(finalState);
                overlays[i] = scan.finishedRuns();
            }
        }
        return new PreparedTile(chunkX, chunkZ, worldBottomY,
                floorState, floorY, topY, biome, light, glowing, overlays);
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Top-down scan of one pixel — the loadPixel loop with the cave branches folded away. */
    private static void scanPixel(ColumnAccess column, int x, int z, int startY, PixelScan scan) {
        int lowY = scan.lowY;
        boolean extendedLastStep = false;
        int h = startY;
        while (h >= lowY) {
            BlockState state = column.blockAt(x, h, z);
            FluidState fluid = state.getFluidState();
            BlockState fluidLegacyBlock = fluid.isEmpty() ? null : fluid.createLegacyBlock();

            // The native ONE-SHOT deep-run extension (the !shouldExtendTillTheBottom
            // term): once the pixel has overlays and the run is ≥5 blocks deep, trace
            // to the bottom of the transparency and account the remainder's opacity in
            // one step. One-shot matters: without it the pixel stays in extend mode for
            // the rest of the column and no new overlay run can ever open below the
            // first extension (review MINOR — deep-ocean multi-run collapse).
            boolean extendThisStep = !extendedLastStep && scan.hasOverlays()
                    && scan.firstTransparentY - h >= EXTEND_RUN_DEPTH;
            extendedLastStep = extendThisStep;
            int skipY = h;
            if (extendThisStep) {
                skipY = h - 1;
                while (skipY >= lowY) {
                    BlockState trace = column.blockAt(x, skipY, z);
                    FluidState traceFluid = trace.getFluidState();
                    if (!traceFluid.isEmpty()) {
                        if (!fluidOverlayable(traceFluid)) break;
                        if (!(trace.getBlock() instanceof AirBlock)
                                && trace.getBlock() == traceFluid.createLegacyBlock().getBlock()) {
                            skipY--;
                            continue;
                        }
                    }
                    if (!blockOverlayable(trace)) break;
                    skipY--;
                }
            }

            byte blockLight = column.lightAt(x, h + 1, z);

            if (fluidLegacyBlock != null) {
                // Native fluid branch: the overlay/floor decision runs on the fluid's
                // legacy BLOCK state, but a floor hit records the ORIGINAL state.
                if (scan.step(fluidLegacyBlock, fluidOverlayable(fluid), blockLight, h,
                        extendThisStep, skipY)) {
                    scan.floor(state, h, blockLight);
                    return;
                }
            }
            Block b = state.getBlock();
            if (!(b instanceof AirBlock)
                    && (fluidLegacyBlock == null || b != fluidLegacyBlock.getBlock())) {
                if (scan.step(state, blockOverlayable(state), blockLight, h,
                        extendThisStep, skipY)) {
                    scan.floor(state, h, blockLight);
                    return;
                }
            }
            h = extendThisStep ? skipY : h - 1;
        }
    }

    /** Mutable per-pixel scan state — reset and reused across the 256 pixels. */
    private static final class PixelScan {
        int lowY;
        int topH;
        BlockState floorState;
        int floorY;
        byte floorLight;
        private List<OverlayRun> runs;
        private BlockState runState;
        private byte runLight;
        private int runOpacity;
        private boolean capReached;
        int firstTransparentY;

        void reset(int lowY) {
            this.lowY = lowY;
            this.topH = lowY;
            this.floorState = null;
            this.floorY = 0;
            this.floorLight = 0;
            this.runs = null;
            this.runState = null;
            this.runOpacity = 0;
            this.capReached = false;
            this.firstTransparentY = 0;
        }

        boolean hasOverlays() {
            return this.runState != null || this.runs != null && !this.runs.isEmpty();
        }

        /**
         * One scan step for a candidate state (the loadPixelHelp ladder, surface
         * mode). Returns true when the state is the pixel's FLOOR (stop the scan);
         * false to keep scanning (invisible/colorless skip, or overlay recorded).
         */
        boolean step(BlockState state, boolean overlayable, byte blockLight, int h,
                     boolean extendThisStep, int skipY) {
            if (isInvisible(state)) return false;
            if (overlayable) {
                if (h > this.topH) this.topH = h;
                if (extendThisStep) {
                    // Deep-run remainder, accounted onto the CURRENT run in one step —
                    // native uses the RUN state's dampening, not the current block's.
                    if (this.runState != null) {
                        this.runOpacity += this.runState.getLightBlock() * (h - skipY);
                    }
                    return false;
                }
                if (this.runState != state && !this.capReached) {
                    if (!this.hasOverlays()) this.firstTransparentY = h;
                    flushRun();
                    if (this.runs != null && this.runs.size() >= MAX_OVERLAYS) {
                        this.capReached = true; // native builder caps at 10; extras are dropped
                    } else {
                        this.runState = state;
                        this.runLight = blockLight;
                        this.runOpacity = 0;
                    }
                }
                if (this.runState != null) {
                    this.runOpacity += state.getLightBlock();
                }
                return false;
            }
            if (!hasVanillaColor(state)) return false;
            if (h > this.topH) this.topH = h;
            return true;
        }

        void floor(BlockState originalState, int h, byte blockLight) {
            this.floorState = originalState;
            this.floorY = h;
            this.floorLight = blockLight;
        }

        private void flushRun() {
            if (this.runState == null) return;
            if (this.runs == null) this.runs = new ArrayList<>(4);
            this.runs.add(new OverlayRun(this.runState, this.runLight,
                    isGlowing(this.runState), this.runOpacity));
            this.runState = null;
        }

        OverlayRun[] finishedRuns() {
            flushRun();
            return this.runs == null || this.runs.isEmpty()
                    ? null : this.runs.toArray(new OverlayRun[0]);
        }
    }

    /** Section-indexed access to one column's blocks/light/biomes; absent sections read as air. */
    private record ColumnAccess(VoxelColumnData.SectionData[] byY, int minSecY) {

        private LevelChunkSection sectionFor(int y) {
            int idx = (y >> 4) - this.minSecY;
            if (idx < 0 || idx >= this.byY.length || this.byY[idx] == null) return null;
            return this.byY[idx].section();
        }

        BlockState blockAt(int x, int y, int z) {
            var section = sectionFor(y);
            if (section == null) return AIR;
            var state = section.getBlockState(x, y & 15, z);
            return state == null ? AIR : state;
        }

        byte lightAt(int x, int y, int z) {
            int idx = (y >> 4) - this.minSecY;
            if (idx < 0 || idx >= this.byY.length || this.byY[idx] == null) return 0;
            DataLayer blockLight = this.byY[idx].blockLight();
            return blockLight == null ? 0 : (byte) blockLight.get(x, y & 15, z);
        }

        ResourceKey<Biome> biomeAt(int x, int y, int z) {
            var section = sectionFor(y);
            if (section == null) return null;
            try {
                Holder<Biome> holder = section.getNoiseBiome(x >> 2, (y & 15) >> 2, z >> 2);
                return holder == null ? null : holder.unwrapKey().orElse(null);
            } catch (Throwable t) {
                return null; // a biome-less section degrades to "no biome", never fails the tile
            }
        }
    }

    /** Mirror of the native {@code MapWriter.isInvisible} (flowers option at its default). */
    private static boolean isInvisible(BlockState state) {
        Block b = state.getBlock();
        if (!(b instanceof LiquidBlock) && state.getRenderShape() == RenderShape.INVISIBLE) return true;
        if (b == Blocks.TORCH || b == Blocks.SHORT_GRASS || b == Blocks.GLASS || b == Blocks.GLASS_PANE) {
            return true;
        }
        boolean isFlower = b instanceof PitcherCropBlock || b instanceof TallFlowerBlock
                || b instanceof FlowerBlock
                || taggedFlowerNotLeaves(state);
        return b instanceof DoublePlantBlock && !isFlower;
    }

    /** The native flower-tag term, contained: unbound tags (possible mid-reload, and
     *  the fabric-loader-junit state) degrade to the instanceof checks above. */
    private static boolean taggedFlowerNotLeaves(BlockState state) {
        try {
            return state.is(BlockTags.FLOWERS) && !state.is(BlockTags.LEAVES);
        } catch (IllegalStateException tagsNotBound) {
            return false;
        }
    }

    /** Approximation of Xaero's model-alpha translucency (see class javadoc).
     *  HalfTransparentBlock is the 26.2 family root: ice, slime, honey, and (via
     *  the TransparentBlock subclass) plain + stained glass. */
    private static boolean blockOverlayable(BlockState state) {
        Block b = state.getBlock();
        return b instanceof AirBlock || b instanceof HalfTransparentBlock;
    }

    private static boolean fluidOverlayable(FluidState fluid) {
        // Water by TYPE, not tag (tag lookups throw while tags are unbound): the
        // approximation of Xaero's fluid-translucency test — water overlays, lava floors.
        var type = fluid.getType();
        return type == Fluids.WATER || type == Fluids.FLOWING_WATER;
    }

    /** The native buggedStates memo: a throwing getMapColor is remembered so a bugged
     *  block in a wall costs one exception, not 256 per column on the decode thread.
     *  Bounded (a hostile registry can't grow it unboundedly); decode-thread-confined
     *  today, CHM-backed so a future second reader stays safe. */
    private static final java.util.Set<BlockState> BUGGED_STATES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_BUGGED_STATES = 256;

    private static boolean hasVanillaColor(BlockState state) {
        if (BUGGED_STATES.contains(state)) return false;
        try {
            var color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return color != null && color.col != 0;
        } catch (Throwable t) {
            // Native adds throwing states to a bugged list and skips them (the scan
            // continues to the next block down, exactly like a colorless block).
            if (BUGGED_STATES.size() < MAX_BUGGED_STATES) BUGGED_STATES.add(state);
            return false;
        }
    }

    private static boolean isGlowing(BlockState state) {
        return (double) state.getLightEmission() >= 0.5;
    }
}
