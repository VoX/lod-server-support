package dev.vox.lss.compat;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Decides which vanilla chunks that left the client Voxy saved with Vertigo's air in them
 * (issue #437, docs/planning/2026-11-27-vertigo-hollowed-columns-plan.md §10). Pure: no
 * Minecraft types, so the rules are unit-tested directly. Main client thread only.
 *
 * <p><b>Hollow</b> is read from the chunk itself. Vertigo sends the sections far above or below
 * the player as air but leaves the heightmaps alone, so the client holds the server's real
 * surface heights. A column's top block is a real block, so the section that holds it is never
 * all air on the server. If the client holds such a section as all air, Vertigo hid it. Nothing
 * else produces that contradiction: air under a floating build, void columns in the End or a
 * cave never contain a column's top block. Vertigo's own client state is not used: on 26.3 its
 * client can fall back to whole-chunk tracking while the server still filters sections, and then
 * it reports every section as loaded.
 *
 * <p><b>When</b>: Voxy saves a client chunk when Sodium takes it out of "ready", which happens
 * when the first of its 8 neighbours leaves; on the chunk's own drop the slot is already empty
 * and Voxy writes nothing. So for every chunk that leaves, each still-loaded neighbour that is
 * hollow now is marked suspect, and the chunk that left is reported if it was marked, or is
 * hollow itself (a chunk that left with all its neighbours keeps what Voxy saved when it was
 * added).
 */
final class VertigoHollowDetector {
    /** Bounded: suspects normally leave with their chunk; a level switch clears them. */
    static final int MAX_SUSPECTS = 16_384;

    /** A loaded client chunk, as far as the rule needs it. */
    interface ChunkView {
        /** True if a section that holds some column's top block is all air on the client. */
        boolean topSectionEmpty();
    }

    /** The client chunk cache: a loaded chunk, or null. */
    interface Chunks {
        ChunkView chunk(int chunkX, int chunkZ);
    }

    private final LongOpenHashSet suspect = new LongOpenHashSet();

    static boolean hollow(ChunkView view) {
        return view != null && view.topSectionEmpty();
    }

    /**
     * The chunk at (x, z) has left the client: true if its re-ask is due.
     *
     * @param left the chunk that left (no longer in {@code chunks})
     */
    boolean left(int chunkX, int chunkZ, ChunkView left, Chunks chunks) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = chunkX + dx, nz = chunkZ + dz;
                long key = pack(nx, nz);
                if (this.suspect.contains(key)) continue;
                if (hollow(chunks.chunk(nx, nz)) && this.suspect.size() < MAX_SUSPECTS) {
                    this.suspect.add(key);
                }
            }
        }
        boolean wasSuspect = this.suspect.remove(pack(chunkX, chunkZ));
        return wasSuspect || hollow(left);
    }

    void clear() { this.suspect.clear(); }

    int suspectCount() { return this.suspect.size(); }

    static long pack(int x, int z) { return ((long) z << 32) | (x & 0xFFFFFFFFL); }
}
