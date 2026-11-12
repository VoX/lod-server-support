package dev.vox.lss.common.voxel;

import java.util.Arrays;

/**
 * Sky light for one 16x16 column that arrived without any (chunks saved without light, for
 * example after a world upgrade: vanilla's {@code ChunkDeleteLightFix} removes every light
 * array and the server's disk path serves the chunk as it is).
 *
 * <p>The rule is vanilla's, restricted to the column:
 * <ul>
 * <li><b>Sources</b> ({@code ChunkSkyLightSources.findLowestSourceY}): per (x, z), from the
 * top of the range down, a cell is a source (15) while its light dampening is 0 and the
 * faces between it and the cell above do not close the gap. An all-air section is passed
 * without that face check, as vanilla does.</li>
 * <li><b>Spread</b> ({@code SkyLightEngine.propagateIncrease}): from a lit cell into each of
 * the six neighbours, the step costs {@code max(1, dampening of the cell entered)} and is
 * blocked when the two touching faces close the gap ({@code Shapes.faceShapeOccludes}: one
 * of them is a full face, or both are partial and together cover it).</li>
 * </ul>
 * Nothing outside the column is read. Light that vanilla would bring in across the chunk
 * border is missing, so a result can be darker than vanilla near the border and is never
 * brighter: every value here is reached along a path vanilla's engine also has.
 *
 * <p>Cells are described by a small descriptor, {@link #descriptor(int, int, int)}: the
 * light dampening (0..15) and, per face, whether the occlusion shape fills the face or only
 * part of it. A block without a light-occluding shape (stone, leaves, water, glass, air)
 * has no face bits; its dampening alone decides. This class has no Minecraft types; the
 * caller supplies the descriptors.
 *
 * <p>Not thread-safe: one instance holds the working buffers and is reused column after
 * column by one thread. Nothing is allocated per cell; a layer array is allocated only for
 * a section the caller asks for with {@link #layer(int)}.
 */
public final class SkyLightEstimate {
    /** Directions, in vanilla's {@code Direction} data-value order. */
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    /** The descriptor of air: no dampening, no faces. */
    public static final int AIR = 0;
    /** A cell light can neither enter nor leave (also used for a block whose own light or
     *  shape method failed). */
    public static final int OPAQUE = 15 | 0x3F << 4;
    /** Bytes in one section's nibble array (vanilla {@code DataLayer} layout). */
    public static final int LAYER_BYTES = 2048;
    /** Most sections one call accepts (a level is at most 254 sections tall). */
    public static final int MAX_SECTIONS = 512;
    /**
     * Spread steps one column may take. Real terrain needs a few thousand at most (see the
     * measurement in the plan's as-built section); a hostile column of maximum height and a
     * comb of shadows would otherwise cost a million. Past the budget the spread stops:
     * what is lit so far stays, the rest stays dark.
     */
    static final int DEFAULT_SPREAD_BUDGET = 1 << 17;

    private static final int CELLS = 4096;
    private static final int FULL_SHIFT = 4, PARTIAL_SHIFT = 10;
    private static final int FACES = 0xFFF0;
    private static final int ALL_DIRECTIONS = 0x3F;
    private static final int INITIAL_QUEUE = 1024, RETAINED_QUEUE = 1 << 16;

    /** The cells of one column, sections counted from the top (0) down. */
    public interface Column {
        /** True when the section holds only air (vanilla {@code hasOnlyAir}); an absent
         *  section is air. Its cells are never asked for. */
        boolean allAir(int section);

        /** The descriptor of one cell; {@code cell} is {@link #cell(int, int, int)}. */
        int descriptor(int section, int cell);

        /**
         * Whether two PARTIAL faces that touch close the gap between cell A and its
         * neighbour B in {@code direction} (from A to B). Asked only when both touching
         * faces are partial. The default says closed, which can only make a result darker.
         */
        default boolean partialFacesClose(int sectionA, int cellA, int sectionB, int cellB, int direction) {
            return true;
        }
    }

    /**
     * @param dampening    light dampening, 0..15
     * @param fullFaces    bit per direction: the occlusion shape fills that face
     * @param partialFaces bit per direction: the occlusion shape covers part of that face
     */
    public static int descriptor(int dampening, int fullFaces, int partialFaces) {
        int full = fullFaces & ALL_DIRECTIONS;
        return Math.max(0, Math.min(15, dampening)) | full << FULL_SHIFT
                | (partialFaces & ALL_DIRECTIONS & ~full) << PARTIAL_SHIFT;
    }

    /** Cell index inside a section, the order of vanilla's {@code DataLayer}. */
    public static int cell(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    private final int spreadBudget;
    /** One byte per cell, slot 0 = the BOTTOM section, so a step down is {@code -256}. */
    private byte[] light = new byte[0];
    /** Pending spread steps: {@code index << 10 | directions << 4 | level}. */
    private long[] queue = new long[INITIAL_QUEUE];
    private boolean[] air = new boolean[0];
    private boolean[] lit = new boolean[0];
    /** Per (x, z): the lowest source cell's height in cells from the range bottom. */
    private final int[] lowest = new int[256];
    /** Per (x, z) during the source pass: the descriptor of the cell above. */
    private final int[] above = new int[256];
    private Column column;
    private int sections;
    /** Slots at or above this one hold this column's values; lower slots are stale. */
    private int cleared;
    private int head, tail, steps;
    private boolean exhausted;

    public SkyLightEstimate() {
        this(DEFAULT_SPREAD_BUDGET);
    }

    SkyLightEstimate(int spreadBudget) {
        this.spreadBudget = spreadBudget;
    }

    /**
     * Light one column. The result stays readable through {@link #layer(int)} until the
     * next call.
     *
     * @param sectionCount sections in the range, the top one first
     * @return whether any cell received light
     */
    public boolean estimate(Column column, int sectionCount) {
        if (sectionCount < 0 || sectionCount > MAX_SECTIONS) {
            throw new IllegalArgumentException("section count " + sectionCount);
        }
        this.column = column;
        this.sections = sectionCount;
        this.cleared = sectionCount;
        this.head = this.tail = this.steps = 0;
        this.exhausted = false;
        if (this.queue.length > RETAINED_QUEUE) this.queue = new long[INITIAL_QUEUE];
        if (this.light.length < sectionCount * CELLS) {
            this.light = new byte[sectionCount * CELLS];
            this.air = new boolean[sectionCount];
            this.lit = new boolean[sectionCount];
        }
        Arrays.fill(this.lit, 0, sectionCount, false);
        for (int section = 0; section < sectionCount; section++) this.air[section] = column.allAir(section);
        if (!sources()) return false;
        seedSides();
        spread();
        return true;
    }

    /** The nibble array of one section (a fresh array), or null when it received no light. */
    public byte[] layer(int section) {
        int slot = this.sections - 1 - section;
        if (section < 0 || slot < 0 || !this.lit[slot]) return null;
        byte[] out = new byte[LAYER_BYTES];
        int base = slot << 12;
        for (int i = 0; i < LAYER_BYTES; i++) {
            out[i] = (byte) (this.light[base + 2 * i] | this.light[base + 2 * i + 1] << 4);
        }
        return out;
    }

    /** The light of one cell after {@link #estimate}. */
    public int lightAt(int section, int cell) {
        int slot = this.sections - 1 - section;
        return slot >= this.cleared ? this.light[slot << 12 | cell] : 0;
    }

    /** True when the last column used up the spread budget and may be darker than the rule gives. */
    public boolean exhausted() {
        return this.exhausted;
    }

    /** Spread steps the last column took. */
    public int steps() {
        return this.steps;
    }

    /** The source pass. Fills {@link #lowest} and takes the one step down from each lowest source. */
    private boolean sources() {
        Arrays.fill(this.above, AIR);
        Arrays.fill(this.lowest, -1);
        int alive = 256;
        boolean any = false;
        for (int section = 0; section < this.sections && alive > 0; section++) {
            int slot = this.sections - 1 - section, base = slot << 12;
            this.cleared = slot;
            if (this.air[section]) {
                if (alive == 256) {
                    Arrays.fill(this.light, base, base + CELLS, (byte) 15);
                } else {
                    Arrays.fill(this.light, base, base + CELLS, (byte) 0);
                    for (int c = 0; c < 256; c++) {
                        if (this.lowest[c] >= 0) continue;
                        for (int i = base | c; i < base + CELLS; i += 256) this.light[i] = 15;
                    }
                }
                // Vanilla passes an all-air section without looking at the face above it.
                Arrays.fill(this.above, AIR);
                this.lit[slot] = true;
                any = true;
                continue;
            }
            Arrays.fill(this.light, base, base + CELLS, (byte) 0);
            for (int c = 0; c < 256; c++) {
                if (this.lowest[c] >= 0) continue;
                int up = this.above[c], y = 15, entered = AIR;
                boolean closed = false;
                for (; y >= 0; y--) {
                    entered = this.column.descriptor(section, y << 8 | c);
                    if ((entered & 15) != 0) break;
                    if (closedBelow(up, entered, section, y << 8 | c)) {
                        closed = true;
                        break;
                    }
                    this.light[base | y << 8 | c] = 15;
                    up = entered;
                }
                if (y < 15) {
                    this.lit[slot] = true;
                    any = true;
                }
                if (y < 0) {
                    this.above[c] = up;
                    continue;
                }
                this.lowest[c] = (slot << 4) + y + 1;
                alive--;
                // The lowest source's step down, taken here instead of through the queue: the
                // cell that ended the run dampens (1..14) but may still take light.
                int level = 15 - (entered & 15);
                if (!closed && level > 0 && !closedBelow(up, entered, section, y << 8 | c)) {
                    int index = base | y << 8 | c;
                    this.light[index] = (byte) level;
                    this.lit[slot] = true;
                    any = true;
                    if (level > 1) push(index, level, ALL_DIRECTIONS & ~(1 << UP));
                }
            }
        }
        for (int c = 0; c < 256; c++) if (this.lowest[c] < 0) this.lowest[c] = 0;
        return any;
    }

    /** Whether the faces between a cell and the cell above it close the gap. */
    private boolean closedBelow(int up, int entered, int section, int cell) {
        if (((up | entered) & FACES) == 0) return false;
        if ((up >>> FULL_SHIFT + DOWN & 1) != 0 || (entered >>> FULL_SHIFT + UP & 1) != 0) return true;
        if ((up >>> PARTIAL_SHIFT + DOWN & 1) == 0 || (entered >>> PARTIAL_SHIFT + UP & 1) == 0) return false;
        boolean sameSection = cell < 15 << 8;
        return this.column.partialFacesClose(sameSection ? section : section - 1,
                sameSection ? cell + 256 : cell & 255, section, cell, DOWN);
    }

    /**
     * Queue every source cell that has a darker cell beside it (vanilla
     * {@code propagateLightSources}): the cells of a source run that lie below a
     * neighbouring run's lowest source.
     */
    private void seedSides() {
        int top = this.sections << 4;
        for (int c = 0; c < 256; c++) {
            int x = c & 15, z = c >> 4;
            int north = z > 0 ? this.lowest[c - 16] : Integer.MIN_VALUE;
            int south = z < 15 ? this.lowest[c + 16] : Integer.MIN_VALUE;
            int west = x > 0 ? this.lowest[c - 1] : Integer.MIN_VALUE;
            int east = x < 15 ? this.lowest[c + 1] : Integer.MIN_VALUE;
            int limit = Math.min(top, Math.max(Math.max(north, south), Math.max(west, east)));
            for (int y = this.lowest[c]; y < limit; y++) {
                int directions = (y < north ? 1 << NORTH : 0) | (y < south ? 1 << SOUTH : 0)
                        | (y < west ? 1 << WEST : 0) | (y < east ? 1 << EAST : 0);
                push(y << 8 | c, 15, directions);
            }
        }
    }

    private void spread() {
        while (this.head < this.tail) {
            long entry = this.queue[this.head++];
            int index = (int) (entry >>> 10), level = (int) (entry & 15), directions = (int) (entry >>> 4) & 63;
            if (this.light[index] != level) continue;   // raised since it was queued
            if (++this.steps > this.spreadBudget) {
                this.exhausted = true;
                return;
            }
            int from = -1;
            for (int direction = 0; direction < 6; direction++) {
                if ((directions & 1 << direction) == 0) continue;
                int to = neighbour(index, direction);
                if (to < 0) continue;
                int slot = to >>> 12;
                if (slot < this.cleared) {
                    Arrays.fill(this.light, slot << 12, this.cleared << 12, (byte) 0);
                    this.cleared = slot;
                }
                int old = this.light[to];
                if (level - 1 <= old) continue;
                int entered = descriptorAt(to);
                int next = level - Math.max(1, entered & 15);
                if (next <= old) continue;
                if (from < 0) from = descriptorAt(index);
                if (closed(from, direction, entered, index, to)) continue;
                this.light[to] = (byte) next;
                this.lit[slot] = true;
                if (next > 1) push(to, next, ALL_DIRECTIONS & ~(1 << (direction ^ 1)));
            }
        }
    }

    private int neighbour(int index, int direction) {
        switch (direction) {
            case DOWN: return index >= 256 ? index - 256 : -1;
            case UP: return index + 256 < this.sections << 12 ? index + 256 : -1;
            case NORTH: return (index & 0xF0) != 0 ? index - 16 : -1;
            case SOUTH: return (index & 0xF0) != 0xF0 ? index + 16 : -1;
            case WEST: return (index & 15) != 0 ? index - 1 : -1;
            default: return (index & 15) != 15 ? index + 1 : -1;
        }
    }

    private int descriptorAt(int index) {
        int section = this.sections - 1 - (index >>> 12);
        return this.air[section] ? AIR : this.column.descriptor(section, index & 4095);
    }

    private boolean closed(int from, int direction, int entered, int fromIndex, int toIndex) {
        if (((from | entered) & FACES) == 0) return false;
        int opposite = direction ^ 1;
        if ((from >>> FULL_SHIFT + direction & 1) != 0 || (entered >>> FULL_SHIFT + opposite & 1) != 0) return true;
        if ((from >>> PARTIAL_SHIFT + direction & 1) == 0 || (entered >>> PARTIAL_SHIFT + opposite & 1) == 0) return false;
        return this.column.partialFacesClose(this.sections - 1 - (fromIndex >>> 12), fromIndex & 4095,
                this.sections - 1 - (toIndex >>> 12), toIndex & 4095, direction);
    }

    private void push(int index, int level, int directions) {
        if (this.tail == this.queue.length) this.queue = Arrays.copyOf(this.queue, this.queue.length * 2);
        this.queue[this.tail++] = (long) index << 10 | (long) directions << 4 | level;
    }
}
