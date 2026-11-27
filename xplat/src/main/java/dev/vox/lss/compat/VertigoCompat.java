package dev.vox.lss.compat;

import dev.vox.lss.networking.client.ClientNetGlue;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import java.lang.ref.WeakReference;

/**
 * Vertigo (Fabric, MIT) sends the sections of a chunk far above or below the player as air,
 * and Voxy saves that air over its distant terrain: a trench behind every flight (issue #437,
 * docs/planning/2026-11-27-vertigo-hollowed-columns-plan.md §10). For every vanilla chunk that
 * leaves the client, this decides from the chunks themselves whether Voxy saved it hollow
 * ({@link VertigoHollowDetector}) and, if so, has the session ask the server for that column
 * once more. It uses nothing of Vertigo but its presence. Fabric client only (the hook), and only
 * while Voxy is the consumer (Voxy is what saves the air).
 *
 * <p>One path: the chunks seen loading are held, and once a second the ones the client chunk
 * cache no longer holds at their position have left. That covers the normal unload and the
 * silent eviction after a teleport, where the server's forget packets find the old chunks out of
 * the cache's range, vanilla's drop does nothing, and no unload event fires. A second of latency
 * costs nothing: a re-ask waits at least five seconds anyway.
 */
public final class VertigoCompat {
    static final int SWEEP_TICKS = 20;

    private static volatile boolean enabled;
    private static final VertigoHollowDetector detector = new VertigoHollowDetector();
    /** Position -> the chunk seen loading there, for the level in {@link #trackedLevel}. Main
     *  client thread only. Strong references: a chunk that left is held until the next sweep. */
    private static final Long2ObjectOpenHashMap<LevelChunk> tracked = new Long2ObjectOpenHashMap<>();
    private static WeakReference<ClientLevel> trackedLevel = new WeakReference<>(null);
    private static int sweepCountdown = SWEEP_TICKS;

    private VertigoCompat() {}

    /** Called once at client start, by the Fabric hook, when Vertigo and the Voxy bridge are there. */
    public static void enable() { enabled = true; }

    /** A vanilla chunk loaded (main client thread). */
    public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        if (!enabled || level == null || chunk == null) return;
        switchLevel(level);
        long key = VertigoHollowDetector.pack(chunk.getPos().x(), chunk.getPos().z());
        LevelChunk previous = tracked.put(key, chunk);
        // Left and came back within one sweep: the old chunk's suspect mark is not this one's.
        if (previous != null && previous != chunk) detector.forget(key);
        ClientNetGlue.vanillaChunkLoaded(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
    }

    private static void switchLevel(ClientLevel level) {
        if (trackedLevel.get() == level) return;
        tracked.clear();
        detector.clear();
        trackedLevel = new WeakReference<>(level);
    }

    /**
     * Once a second (main client thread): every held chunk that the client chunk cache no longer
     * holds at its position has left. Throws nothing: a failure turns this off until the game
     * restarts.
     *
     * @param serverViewDistance the server's view distance from the login packet, or 0
     */
    public static void clientTick(ClientLevel level, int serverViewDistance) {
        if (!enabled || --sweepCountdown > 0) return;
        sweepCountdown = SWEEP_TICKS;
        if (level == null) {
            tracked.clear();
            detector.clear();
            return;
        }
        switchLevel(level);
        if (tracked.isEmpty()) return;
        try {
            boolean report = ClientNetGlue.wantsHollowReports() && VoxyCompat.isInitialized()
                    && VoxyCompat.vanillaIngestEnabled(level);
            var source = level.getChunkSource();
            var gone = new LongArrayList();
            for (var entry : tracked.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                if (source.getChunk((int) key, (int) (key >> 32), ChunkStatus.FULL, false) != entry.getValue()) {
                    gone.add(key);
                }
            }
            if (gone.isEmpty()) return;
            if (!report) detector.clear();
            VertigoHollowDetector.Chunks chunks = (x, z) -> {
                LevelChunk loaded = source.getChunk(x, z, ChunkStatus.FULL, false);
                return loaded == null ? null : view(loaded);
            };
            for (int i = 0; i < gone.size(); i++) {
                long key = gone.getLong(i);
                LevelChunk chunk = tracked.remove(key);
                if (!report || chunk == null) continue;
                int x = (int) key, z = (int) (key >> 32);
                if (detector.left(x, z, view(chunk), chunks)) {
                    ClientNetGlue.reportHollowed(level.dimension(), x, z, serverViewDistance);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            enabled = false;
            tracked.clear();
            detector.clear();
            dev.vox.lss.common.LSSLogger.warn("Vertigo terrain repair turned off until the game restarts", failure);
        }
    }

    /**
     * The rule's view of a client chunk: whether a section that holds some column's top block
     * (its {@code WORLD_SURFACE} height, the server's own, since Vertigo filters only sections
     * and block entities) is all air here. Columns with no block at all are skipped. A chunk
     * without that heightmap has nothing to judge by.
     */
    static VertigoHollowDetector.ChunkView view(LevelChunk chunk) {
        return () -> topSectionEmpty(chunk);
    }

    static boolean topSectionEmpty(LevelChunk chunk) {
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) return false;
        var sections = chunk.getSections();
        long checked = 0L; // sections already looked at (index < 64); taller worlds re-check
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int index = chunk.getSectionIndex(top);
                // A column with no block reports the world's floor - 1: section index -1.
                if (index < 0 || index >= sections.length) continue;
                if (index < 64) {
                    if ((checked & (1L << index)) != 0) continue;
                    checked |= 1L << index;
                }
                var section = sections[index];
                if (section == null || section.hasOnlyAir()) return true;
            }
        }
        return false;
    }
}
