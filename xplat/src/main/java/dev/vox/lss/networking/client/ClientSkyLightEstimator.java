package dev.vox.lss.networking.client;

import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.voxel.SkyLightEstimate;
import dev.vox.lss.platform.SectionConstruction;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;

/**
 * Sky light for a column that arrived with terrain and no sky light at all (issue #382: a
 * chunk saved without light, which the server's disk path serves as it is). The rule is
 * vanilla's and lives in {@link SkyLightEstimate}; this class is the Minecraft side of it:
 * which sections take part, what a block state's light properties are, and where the
 * result goes.
 *
 * <p>A block state's properties are asked from the state itself, once per state and column.
 * They are never kept in a table indexed by state id: client ids are remapped by registry
 * sync, and a state's cached light values are rebuilt when tags reload. A state whose own
 * light or shape method throws is treated as opaque. Each state also gets an identity for
 * the length of the column (its place in the column's map), so the routine compares two
 * touching partial faces once per pair of states and axis, not once per pair of cells.
 *
 * <p>Nothing here trusts a section's block count: see {@link #holdsOnlyAir}.
 *
 * <p>One instance per decode thread (it holds the working buffers).
 *
 * <p>Per-line surface: {@link #VANILLA}, the three calls on a block state (light dampening
 * is {@code getLightDampening()} on 26.x, {@code getLightBlock()} on 1.21.10/1.21.11 and
 * the two-argument {@code getLightBlock} on 1.21.1; the face-occlusion call takes a getter
 * and position on 1.21.1), and the section constructor seam.
 */
final class ClientSkyLightEstimator implements SkyLightEstimate.Column {
    /**
     * What {@link #estimate} did to a column. {@code sections} is the column to use;
     * {@code work} is what the estimate cost in {@link SkyLightEstimate}'s units and
     * {@code capped} says it ran into its budget (the column may then be darker than the
     * rule gives).
     */
    record Result(VoxelColumnData.SectionData[] sections, int litSections, int addedSections,
                  int work, boolean capped) {}

    /** What vanilla's light engine reads from a block state. */
    interface StateLight {
        /** {@code LightEngine.getOpacity}'s input, 0..15. */
        int dampening(BlockState state);

        /** The negation of {@code LightEngine.isEmptyShape}: the state's occlusion shape counts. */
        boolean occludesByShape(BlockState state);

        /** The state's occlusion shape on one face. */
        VoxelShape faceShape(BlockState state, Direction direction);
    }

    /** This line's calls. */
    static final StateLight VANILLA = new StateLight() {
        @Override public int dampening(BlockState state) {
            return state.getLightBlock();
        }

        @Override public boolean occludesByShape(BlockState state) {
            return state.canOcclude() && state.useShapeForLightOcclusion();
        }

        @Override public VoxelShape faceShape(BlockState state, Direction direction) {
            return state.getFaceOcclusionShape(direction);
        }
    };

    private static final Direction[] DIRECTIONS = directionsByDataValue();
    private static final java.util.function.Predicate<BlockState> NOT_AIR = state -> !state.isAir();
    private static final byte[] DARK_LAYER = new byte[SkyLightEstimate.LAYER_BYTES];
    private static final int UNKNOWN = -1;

    private final StateLight stateLight;
    private final SkyLightEstimate estimate = new SkyLightEstimate();
    private final Reference2IntOpenHashMap<BlockState> descriptors = new Reference2IntOpenHashMap<>();
    /** The working range, top section first; null is an absent or all-air section. */
    private LevelChunkSection[] slots = new LevelChunkSection[32];
    private boolean[] present = new boolean[32];
    private BlockState lastState;
    private int lastDescriptor;

    ClientSkyLightEstimator() {
        this(VANILLA);
    }

    /** Test seam: a state reader other than this line's vanilla calls. */
    ClientSkyLightEstimator(StateLight stateLight) {
        this.stateLight = stateLight;
        this.descriptors.defaultReturnValue(UNKNOWN);
    }

    /**
     * Whether a section really holds only air. {@code hasOnlyAir()} is the block count in
     * the section's header, and that came from the wire: a section with blocks and a count
     * of zero (a faulty or hostile server, a store row from an odd build) would pass for
     * air and let the sky through a roof. So a zero count is confirmed against the block
     * container's palette. The palette can list a state no cell uses; such a section is
     * then read cell by cell, which costs time and nothing else.
     */
    static boolean holdsOnlyAir(LevelChunkSection section) {
        return section.hasOnlyAir() && !section.getStates().maybeHas(NOT_AIR);
    }

    /**
     * True when the column has blocks and none of its sections carries a non-zero sky
     * nibble. A layer that is present and all zero counts as none: a correctly lit column
     * with any open-sky cell has a non-zero nibble somewhere, so a column without one is
     * either unlit or truly dark, and for a truly dark one the estimate is all zero too.
     */
    static boolean lacksSkyLight(VoxelColumnData.SectionData[] sections) {
        boolean content = false;
        for (var s : sections) {
            var sky = s.skyLight();
            if (sky != null && !Arrays.equals(sky.getData(), DARK_LAYER)) return false;
            content |= !holdsOnlyAir(s.section());
        }
        return content;
    }

    /**
     * Estimate sky light for a column that {@link #lacksSkyLight}. Content sections, and
     * all-air sections that were sent, receive their layer. An all-air section is ADDED
     * only directly above or below a content section (what vanilla stores), inside the
     * level's own section range, and only when it received light.
     *
     * @return null when nothing received light (the column is used unchanged)
     */
    Result estimate(VoxelColumnData.SectionData[] sections, int levelSectionCount, int minSectionY,
                    PalettedContainerFactory factory) {
        int top = Integer.MIN_VALUE, bottom = Integer.MAX_VALUE;
        for (var s : sections) {
            int y = s.sectionY();
            boolean content = !holdsOnlyAir(s.section());
            // One section of air above and below the content takes part in the spread.
            top = Math.max(top, content ? y + 1 : y);
            bottom = Math.min(bottom, content ? y - 1 : y);
        }
        int count = top - bottom + 1;
        if (count <= 0 || count > SkyLightEstimate.MAX_SECTIONS) return null;
        if (this.slots.length < count) {
            this.slots = new LevelChunkSection[count];
            this.present = new boolean[count];
        }
        Arrays.fill(this.slots, 0, count, null);
        Arrays.fill(this.present, 0, count, false);
        for (var s : sections) {
            int slot = top - s.sectionY();
            this.present[slot] = true;
            if (!holdsOnlyAir(s.section())) this.slots[slot] = s.section();
        }
        this.descriptors.clear();
        this.lastState = null;
        try {
            if (!this.estimate.estimate(this, count)) return null;
            var out = new java.util.ArrayList<VoxelColumnData.SectionData>(sections.length + 2);
            int lit = 0, added = 0;
            for (var s : sections) {
                byte[] layer = this.estimate.layer(top - s.sectionY());
                if (layer == null) {
                    out.add(s);
                } else {
                    out.add(new VoxelColumnData.SectionData(s.sectionY(), s.section(), s.blockLight(), new DataLayer(layer)));
                    lit++;
                }
            }
            int levelTop = minSectionY + levelSectionCount - 1;
            for (int slot = 0; slot < count; slot++) {
                if (this.present[slot]) continue;
                boolean besideContent = slot + 1 < count && this.slots[slot + 1] != null
                        || slot > 0 && this.slots[slot - 1] != null;
                int y = top - slot;
                if (!besideContent || y < minSectionY || y > levelTop) continue;
                byte[] layer = this.estimate.layer(slot);
                if (layer == null) continue;
                out.add(new VoxelColumnData.SectionData(y, SectionConstruction.empty(factory), null, new DataLayer(layer)));
                lit++;
                added++;
            }
            // Light only in air that is not sent (a column solid up to the level top): unchanged.
            if (lit == 0) return null;
            return new Result(out.toArray(new VoxelColumnData.SectionData[0]), lit, added,
                    this.estimate.work(), this.estimate.exhausted());
        } finally {
            Arrays.fill(this.slots, 0, count, null);   // do not pin the column's sections
            this.lastState = null;
        }
    }

    @Override
    public boolean allAir(int section) {
        return this.slots[section] == null;
    }

    @Override
    public int descriptor(int section, int cell) {
        BlockState state = this.slots[section].getBlockState(cell & 15, cell >> 8, cell >> 4 & 15);
        if (state == this.lastState) return this.lastDescriptor;
        int descriptor = this.descriptors.getInt(state);
        if (descriptor == UNKNOWN) {
            // The state's place in this column's map is its identity: the routine then
            // decides a pair of partial faces once per pair of states and axis.
            descriptor = SkyLightEstimate.withIdentity(describe(state, this.stateLight), this.descriptors.size() + 1);
            this.descriptors.put(state, descriptor);
        }
        this.lastState = state;
        this.lastDescriptor = descriptor;
        return descriptor;
    }

    @Override
    public boolean partialFacesClose(int sectionA, int cellA, int sectionB, int cellB, int direction) {
        try {
            BlockState a = this.slots[sectionA].getBlockState(cellA & 15, cellA >> 8, cellA >> 4 & 15);
            BlockState b = this.slots[sectionB].getBlockState(cellB & 15, cellB >> 8, cellB >> 4 & 15);
            Direction towards = DIRECTIONS[direction];
            return Shapes.faceShapeOccludes(this.stateLight.faceShape(a, towards),
                    this.stateLight.faceShape(b, towards.getOpposite()));
        } catch (RuntimeException e) {
            return true;   // closed: darker at worst
        }
    }

    /**
     * A state's light properties as vanilla's light engine reads them
     * ({@code LightEngine.getOpacity} / {@code getOcclusionShape}): the dampening, and
     * for a state that occludes by shape (slabs, stairs, snow layers, farmland...) which
     * faces its occlusion shape fills or partly covers.
     */
    static int describe(BlockState state, StateLight stateLight) {
        try {
            int dampening = stateLight.dampening(state);
            int full = 0, partial = 0;
            if (stateLight.occludesByShape(state)) {
                for (int direction = 0; direction < 6; direction++) {
                    VoxelShape face = stateLight.faceShape(state, DIRECTIONS[direction]);
                    if (face.isEmpty()) continue;
                    // Alone against an empty face it closes the gap exactly when it fills the face.
                    if (Shapes.faceShapeOccludes(face, Shapes.empty())) full |= 1 << direction;
                    else partial |= 1 << direction;
                }
            }
            return SkyLightEstimate.descriptor(dampening, full, partial);
        } catch (RuntimeException e) {
            // A modded block whose light or shape method fails off the main thread or
            // without a world: no light through it, which can only make the result darker.
            return SkyLightEstimate.OPAQUE;
        }
    }

    private static Direction[] directionsByDataValue() {
        var out = new Direction[6];
        for (Direction direction : Direction.values()) out[direction.get3DDataValue()] = direction;
        return out;
    }
}
