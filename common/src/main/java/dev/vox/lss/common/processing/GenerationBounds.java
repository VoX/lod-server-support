package dev.vox.lss.common.processing;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Where LOD-triggered generation is allowed: per dimension, the rectangle of chunks the world
 * border covers. Immutable; the server thread builds a new one when a border changed and
 * the processing thread reads the latest through a volatile field.
 *
 * <p>The rule is overlap: a chunk any part of which lies inside the border is inside. (Vanilla's
 * own {@code WorldBorder.isWithinBounds(ChunkPos)} asks for the whole chunk, which would
 * leave the edge chunks players can stand in out.) A dimension that was never published has
 * no bound.
 */
public final class GenerationBounds {
    /** No bound anywhere: the rule is off, or nothing was published yet. */
    public static final GenerationBounds NONE = new GenerationBounds(Map.of());

    /** minCx, maxCx, minCz, maxCz, all inclusive. */
    private final Map<String, int[]> byDimension;

    private GenerationBounds(Map<String, int[]> byDimension) {
        this.byDimension = byDimension;
    }

    /** A copy of {@code rectangles} (dimension to {@link #chunkRectangle}). */
    public static GenerationBounds of(Map<String, int[]> rectangles) {
        if (rectangles.isEmpty()) {
            return NONE;
        }
        var copy = new HashMap<String, int[]>(rectangles.size() * 2);
        for (var entry : rectangles.entrySet()) {
            int[] r = entry.getValue();
            if (r == null || r.length != 4) {
                throw new IllegalArgumentException("a rectangle is four chunk coordinates");
            }
            copy.put(entry.getKey(), r.clone());
        }
        return new GenerationBounds(copy);
    }

    public boolean allows(String dimension, int cx, int cz) {
        int[] r = this.byDimension.get(dimension);
        return r == null || (cx >= r[0] && cx <= r[1] && cz >= r[2] && cz <= r[3]);
    }

    public boolean isUnbounded() {
        return this.byDimension.isEmpty();
    }

    /**
     * The chunks that overlap a border given by its centre and its side length in blocks.
     * {@code size} is the larger of the border's current and target size while it moves, so
     * a moving border is published once and never refuses a chunk it is about to cover (or
     * still covers).
     */
    public static int[] chunkRectangle(double centerX, double centerZ, double size) {
        double half = Math.max(0.0, size) / 2.0;
        return new int[]{
                firstChunk(centerX - half), lastChunk(centerX + half),
                firstChunk(centerZ - half), lastChunk(centerZ + half)};
    }

    /** The chunk that holds the border's lower edge: chunk c covers blocks [16c, 16c + 16). */
    private static int firstChunk(double min) {
        return clamp(Math.floor(min / 16.0));
    }

    /** The last chunk with a block below the border's upper edge (the edge is exclusive). */
    private static int lastChunk(double max) {
        return clamp(Math.ceil(max / 16.0) - 1.0);
    }

    private static int clamp(double chunk) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, chunk));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof GenerationBounds that) || that.byDimension.size() != this.byDimension.size()) {
            return false;
        }
        for (var entry : this.byDimension.entrySet()) {
            if (!Arrays.equals(entry.getValue(), that.byDimension.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (var entry : this.byDimension.entrySet()) {
            h += entry.getKey().hashCode() ^ Arrays.hashCode(entry.getValue());
        }
        return h;
    }

    @Override
    public String toString() {
        var sb = new StringBuilder("GenerationBounds{");
        this.byDimension.forEach((dimension, r) -> sb.append(dimension).append('=').append(Arrays.toString(r)).append(' '));
        return sb.append('}').toString();
    }
}
