package dev.vox.lss.compat;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.client.ClientNetGlue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import java.lang.ref.WeakReference;

/**
 * Vertigo (Fabric, MIT) sends the sections of a chunk far above or below the player as air,
 * and Voxy saves that air over its distant terrain: a trench behind every flight (issue #437,
 * docs/planning/2026-11-27-vertigo-hollowed-columns-plan.md). When a vanilla chunk unloads,
 * this decides from the chunk itself whether Voxy saved it hollow ({@link VertigoHollowDetector})
 * and, if so, has the session ask the server for that column once more. It uses nothing of
 * Vertigo but its presence. Runs only on the Fabric client, and only while Voxy is the consumer
 * (Voxy is what saves the air).
 */
public final class VertigoCompat {
    private static volatile boolean enabled;
    private static final VertigoHollowDetector detector = new VertigoHollowDetector();
    private static WeakReference<ClientLevel> detectorLevel = new WeakReference<>(null);
    /**
     * The vanilla chunks seen loading and not yet unloading, for the silent-eviction sweep: after
     * a teleport the server's forget packets find the old chunks out of the cache's range, so
     * vanilla's drop does nothing and no unload event fires (and Voxy, which never saw them
     * leave, keeps what it saved when they arrived). Main client thread only.
     */
    private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<WeakReference<LevelChunk>> tracked =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private static WeakReference<ClientLevel> trackedLevel = new WeakReference<>(null);
    static final int SWEEP_TICKS = 20;
    private static int sweepCountdown = SWEEP_TICKS;

    private VertigoCompat() {}

    /** Called once at client start when Vertigo and the Voxy bridge are both there. */
    public static void init() {
        enabled = true;
        LSSLogger.info("Vertigo detected: terrain it hides from Voxy is fetched again after you pass");
    }

    public static boolean available() { return enabled; }

    /**
     * A vanilla chunk is unloading (main client thread, while its sections are still there).
     *
     * @param serverViewDistance the server's view distance from the login packet, or 0
     */
    public static void chunkUnloading(ClientLevel level, LevelChunk chunk, int serverViewDistance) {
        if (!enabled || level == null || chunk == null) return;
        if (trackedLevel.get() == level) tracked.remove(VertigoHollowDetector.pack(chunk.getPos().x(), chunk.getPos().z()));
        try {
            if (!ClientNetGlue.wantsHollowReports() || !VoxyCompat.isInitialized()
                    || !VoxyCompat.vanillaIngestEnabled(level)) {
                detector.clear();
                return;
            }
            if (detectorLevel.get() != level) {
                detector.clear();
                detectorLevel = new WeakReference<>(level);
            }
            int cx = chunk.getPos().x(), cz = chunk.getPos().z();
            VertigoHollowDetector.Chunks chunks = (x, z) -> {
                LevelChunk loaded = x == cx && z == cz ? chunk
                        : level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                return loaded == null ? null : view(loaded);
            };
            if (detector.unloading(cx, cz, level.getMinSectionY(), level.getMaxSectionY(), chunks)) {
                ClientNetGlue.reportHollowed(level.dimension(), cx, cz, serverViewDistance);
            }
        } catch (RuntimeException | LinkageError failure) {
            enabled = false;
            detector.clear();
            LSSLogger.warn("Vertigo terrain repair turned off for this session", failure);
        }
    }

    /** A vanilla chunk loaded: drop a pending re-ask for it (the next unload decides again). */
    public static void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        if (!enabled || level == null || chunk == null) return;
        if (trackedLevel.get() != level) {
            tracked.clear();
            trackedLevel = new WeakReference<>(level);
        }
        tracked.put(VertigoHollowDetector.pack(chunk.getPos().x(), chunk.getPos().z()), new WeakReference<>(chunk));
        ClientNetGlue.vanillaChunkLoaded(level.dimension(), chunk.getPos().x(), chunk.getPos().z());
    }

    /**
     * Once a second (main client thread): a tracked chunk that is no longer what the client
     * chunk cache holds at its position left without an unload event. It is judged as a chunk
     * dropped with its neighbours: by what it holds now, which is what Voxy saved at its add,
     * or as a suspect if a neighbour's unload had marked it.
     */
    public static void clientTick(ClientLevel level, int serverViewDistance) {
        if (!enabled || tracked.isEmpty() || --sweepCountdown > 0) return;
        sweepCountdown = SWEEP_TICKS;
        if (level == null || trackedLevel.get() != level) {
            tracked.clear();
            return;
        }
        boolean report;
        try {
            report = ClientNetGlue.wantsHollowReports() && VoxyCompat.isInitialized()
                    && VoxyCompat.vanillaIngestEnabled(level);
            var iterator = tracked.long2ObjectEntrySet().fastIterator();
            var source = level.getChunkSource();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                LevelChunk held = entry.getValue().get();
                long key = entry.getLongKey();
                int x = (int) key, z = (int) (key >> 32);
                if (held != null && source.getChunk(x, z, ChunkStatus.FULL, false) == held) continue;
                iterator.remove();
                if (held == null || !report) continue;
                if (detector.forget(key)
                        || VertigoHollowDetector.hollow(view(held), level.getMinSectionY(), level.getMaxSectionY())) {
                    ClientNetGlue.reportHollowed(level.dimension(), x, z, serverViewDistance);
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            enabled = false;
            tracked.clear();
            detector.clear();
            LSSLogger.warn("Vertigo terrain repair turned off for this session", failure);
        }
    }

    /**
     * The rule's view of a client chunk: its {@code WORLD_SURFACE} heights (the server's own,
     * since Vertigo filters only sections and block entities) and which sections it holds as
     * all air. A chunk without that heightmap has nothing to judge by and is never hollow.
     */
    static VertigoHollowDetector.ChunkView view(LevelChunk chunk) {
        if (!chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) return null;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                low = Math.min(low, top);
                high = Math.max(high, top);
            }
        }
        int minTop = low, maxTop = high;
        return new VertigoHollowDetector.ChunkView() {
            public int minTopY() { return minTop; }
            public int maxTopY() { return maxTop; }
            public boolean sectionEmpty(int sectionY) {
                int index = chunk.getSectionIndexFromSectionY(sectionY);
                var sections = chunk.getSections();
                if (index < 0 || index >= sections.length) return false;
                var section = sections[index];
                return section == null || section.hasOnlyAir();
            }
        };
    }
}
