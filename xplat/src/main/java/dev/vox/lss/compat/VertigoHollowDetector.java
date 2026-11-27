package dev.vox.lss.compat;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Decides which unloading vanilla chunks Voxy saved with Vertigo's air in them (issue #437,
 * docs/planning/2026-11-27-vertigo-hollowed-columns-plan.md §3.1). Pure: no Minecraft types,
 * so the rules are unit-tested directly. Main client thread only.
 *
 * <p><b>Hollow</b> is read from the chunk itself. Vertigo sends the sections far above or below
 * the player as air but leaves the heightmaps alone, so the client holds the server's real
 * surface heights. A section between the chunk's lowest and highest surface block that the
 * client holds as all air is a section Vertigo hid: real terrain is there (each column's own
 * top block sits in its section, and a fully empty section below every column's top is a
 * cave of a whole chunk's width, rare enough to cost only a needless re-serve). Vertigo's own
 * client state is not used: on 26.3 its client can fall back to whole-chunk tracking while the
 * server still filters sections, and then it reports every section as loaded.
 *
 * <p><b>When</b>: Voxy saves a client chunk when Sodium takes it out of "ready", which happens
 * at the first unload among its 8 neighbours; on the chunk's own drop the slot is already empty
 * and Voxy writes nothing. So at every unload, each still-loaded neighbour that is hollow now is
 * marked suspect, and the unloading chunk is reported if it was marked, or is hollow now itself
 * (a chunk dropped together with all its neighbours, judged by what Voxy saved when it was
 * added).
 */
final class VertigoHollowDetector {
    /** Bounded: suspects normally leave at their own unload; a level switch clears them. */
    static final int MAX_SUSPECTS = 16_384;

    /** A loaded client chunk, as far as the rule needs it. */
    interface ChunkView {
        /** Lowest and highest surface block over the chunk's 256 columns (Y). */
        int minTopY();
        int maxTopY();
        /** The client holds this section as all air (or not at all). */
        boolean sectionEmpty(int sectionY);
    }

    /** The client chunk cache: a loaded chunk, or null. */
    interface Chunks {
        ChunkView chunk(int chunkX, int chunkZ);
    }

    private final LongOpenHashSet suspect = new LongOpenHashSet();

    static boolean hollow(ChunkView view, int minSectionY, int maxSectionY) {
        if (view == null) return false;
        int high = view.maxTopY();
        if (high < minSectionY * 16) return false; // no block at all
        int from = Math.max(minSectionY, Math.floorDiv(view.minTopY(), 16));
        int to = Math.min(maxSectionY, Math.floorDiv(high, 16));
        for (int sy = from; sy <= to; sy++) {
            if (view.sectionEmpty(sy)) return true;
        }
        return false;
    }

    /** The chunk at (x, z) is unloading: true if its re-ask is due. */
    boolean unloading(int chunkX, int chunkZ, int minSectionY, int maxSectionY, Chunks chunks) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                int nx = chunkX + dx, nz = chunkZ + dz;
                long key = pack(nx, nz);
                if (this.suspect.contains(key)) continue;
                if (hollow(chunks.chunk(nx, nz), minSectionY, maxSectionY)) {
                    if (this.suspect.size() >= MAX_SUSPECTS) this.suspect.clear();
                    this.suspect.add(key);
                }
            }
        }
        boolean wasSuspect = this.suspect.remove(pack(chunkX, chunkZ));
        return wasSuspect || hollow(chunks.chunk(chunkX, chunkZ), minSectionY, maxSectionY);
    }

    void clear() { this.suspect.clear(); }

    /** A chunk left without an unload event: true if it was a suspect (it is none now). */
    boolean forget(long key) { return this.suspect.remove(key); }

    int suspectCount() { return this.suspect.size(); }

    static long pack(int x, int z) { return ((long) z << 32) | (x & 0xFFFFFFFFL); }
}
