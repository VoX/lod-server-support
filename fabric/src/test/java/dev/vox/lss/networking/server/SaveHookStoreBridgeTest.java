package dev.vox.lss.networking.server;

import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.store.LodStoreDiagnostics;
import dev.vox.lss.common.store.LodStoreMode;
import dev.vox.lss.common.store.LodStoreService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save-hook -> store bridge ({@code LSSServerNetworking.applySaveObservationToStore}),
 * DELETE-only since the 4-agent round (R2-M2): a content-changing save deletes the
 * position's store row — the old write-through deposit provably never survived the
 * dirty->store fan-out its own mark triggers, so the bridge must NEVER deposit (a
 * reintroduced deposit is doomed work plus shed pressure on the bounded queue).
 * Unchanged saves and a null store are no-ops.
 */
class SaveHookStoreBridgeTest {

    private record Call(String kind, String dim, long packed) {}

    private static final class RecordingStore implements LodStoreService {
        final List<Call> calls = new ArrayList<>();
        @Override public LodStoreMode mode() { return LodStoreMode.FULL; }
        @Override public StoreHit get(String dimension, long packed) { return null; }
        @Override public boolean deposit(String dimension, long packed, byte[] sectionBytes,
                                         long columnTimestamp, long acquiredEpochSeconds) {
            this.calls.add(new Call("deposit", dimension, packed));
            return true;
        }
        @Override public void invalidate(String dimension, long[] positions) {
            this.calls.add(new Call("invalidate", dimension, positions[0]));
        }
        @Override public void delete(String dimension, long packed) {
            this.calls.add(new Call("delete", dimension, packed));
        }
        @Override public LodStoreDiagnostics diagnostics() { return new LodStoreDiagnostics(); }
        @Override public void shutdown() { }
    }

    private static final String OW = "minecraft:overworld";

    @Test
    void changedSaveDeletesTheRowAndNeverDeposits() {
        var store = new RecordingStore();
        LSSServerNetworking.applySaveObservationToStore(store, OW, 7, -3,
                new DirtyContentFilter.SaveObservation(true, true, new byte[]{1, 2, 3}));
        assertEquals(List.of(new Call("delete", OW, PositionUtil.packPosition(7, -3))),
                store.calls,
                "a changed save must promptly delete the pre-edit row — and must NOT"
                        + " deposit (R2-M2: a hook deposit cannot survive the fan-out"
                        + " its own mark triggers)");
    }

    @Test
    void changedButUndepositableAlsoJustDeletes() {
        var store = new RecordingStore();
        LSSServerNetworking.applySaveObservationToStore(store, OW, 2, 2,
                new DirtyContentFilter.SaveObservation(true, false, null));
        assertEquals(List.of(new Call("delete", OW, PositionUtil.packPosition(2, 2))),
                store.calls,
                "an edit that could not be re-serialized must kill the stale row");
    }

    @Test
    void unchangedSaveAndNullStoreAreNoOps() {
        var store = new RecordingStore();
        LSSServerNetworking.applySaveObservationToStore(store, OW, 3, 3,
                new DirtyContentFilter.SaveObservation(false, false, null));
        assertEquals(0, store.calls.size(), "a suppressed save must not touch the store");
        LSSServerNetworking.applySaveObservationToStore(null, OW, 3, 3,
                new DirtyContentFilter.SaveObservation(true, true, new byte[]{1}));
    }

    /**
     * The review-P3 skip gate's full truth table (three-lens review, test-adequacy
     * MAJOR): the skip fires ONLY under the triple conjunction. Each conjunct carries a
     * correctness failure if dropped — a registered client's session state, a store row
     * an online edit must kill, and (the correctness MAJOR) a persisted timestamp-cache
     * stamp from a PREVIOUS session that a pre-first-join edit must invalidate or a
     * warm rejoin draws up_to_date for pre-edit terrain.
     */
    @Test
    void skipDirtyHashRequiresAllThreeConjuncts() {
        assertTrue(LSSServerNetworking.skipDirtyHash(false, false, true),
                "never-registered + store-off + cache-booted-empty is the ONE skip cell");
        assertFalse(LSSServerNetworking.skipDirtyHash(true, false, true),
                "any registration this session keeps the hash forever (one-way latch)");
        assertFalse(LSSServerNetworking.skipDirtyHash(false, true, true),
                "a live store keeps the hash — a skipped edit leaves a stale store row");
        assertFalse(LSSServerNetworking.skipDirtyHash(false, false, false),
                "persisted stamps from a previous session keep the hash — the"
                        + " stale-up_to_date-on-warm-rejoin MAJOR");
        assertFalse(LSSServerNetworking.skipDirtyHash(true, true, false),
                "all three observers present certainly hashes");
    }

    /** The pending load-seed set (xaero-scatter-remediation-plan.md WI-1b, review M1):
     *  positions loaded while nobody can seed them are recorded once each, bounded, and
     *  cleared with the server's sidecar facts. */
    @Test
    void pendingLoadSeedsDedupeAndStayBounded() {
        ServerReceiverGlue.clearPendingLoadSeeds();
        var dim = net.minecraft.world.level.Level.OVERWORLD;
        ServerReceiverGlue.recordPendingLoadSeed(dim, 1, 2);
        ServerReceiverGlue.recordPendingLoadSeed(dim, 1, 2);
        assertEquals(1, ServerReceiverGlue.pendingLoadSeedCount(), "a position records once");
        ServerReceiverGlue.recordPendingLoadSeed(net.minecraft.world.level.Level.NETHER, 1, 2);
        assertEquals(2, ServerReceiverGlue.pendingLoadSeedCount(), "per dimension");
        for (int i = 0; i < ServerReceiverGlue.MAX_PENDING_LOAD_SEEDS + 10; i++) {
            ServerReceiverGlue.recordPendingLoadSeed(dim, i, 1_000_000);
        }
        assertEquals(ServerReceiverGlue.MAX_PENDING_LOAD_SEEDS, ServerReceiverGlue.pendingLoadSeedCount(),
                "bounded: the excess stays unseeded rather than growing forever");
        ServerReceiverGlue.clearClientInfo();
        assertEquals(0, ServerReceiverGlue.pendingLoadSeedCount(), "dies with the server's sidecar facts");
    }
}
