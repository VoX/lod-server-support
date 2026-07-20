package dev.vox.lss.paper;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * First direct tests of {@link PaperDirtyColumnBroadcaster} (previously pinned only by the
 * live paper-dirty-falling-block soak chain). The sender seam replaces the static plugin-
 * message send; a recording {@link PaperOffThreadProcessor} subclass observes timestamp
 * invalidation and done-bit clears. Pins the per-player gates (handshake, dimension,
 * removed), the raw-lodDistance range filter (NO +32 request-gate buffer), the 10240-cap pagination,
 * clear-BEFORE-send ordering (a send failure must leave the clear applied), timestamp
 * invalidation with zero recipients (dirty-while-offline correctness), the exact
 * interval-ticks cadence with mid-run config pickup, and failed-send player isolation.
 */
class PaperDirtyColumnBroadcasterTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- recording collaborators ----

    static class RecordingProcessor extends PaperOffThreadProcessor {
        record Invalidation(String dimension, long[] positions) {}
        record Clear(UUID playerUuid, long[] positions) {}
        final List<Invalidation> invalidations = new ArrayList<>();
        final List<Clear> clears = new ArrayList<>();
        final List<String> sequence; // shared clear/send ordering log

        RecordingProcessor(Map<UUID, PaperPlayerRequestState> players, List<String> sequence) {
            super(players, null, false, null, 32, 0); // never start()ed; memo off
            this.sequence = sequence;
        }

        @Override
        public void invalidateTimestamps(String dimension, long[] positions) {
            invalidations.add(new Invalidation(dimension, positions));
        }

        @Override
        public void clearDiskReadDone(UUID playerUuid, long[] positions) {
            clears.add(new Clear(playerUuid, positions));
            sequence.add("clear:" + playerUuid);
        }
    }

    record Sent(UUID playerUuid, long[] positions) {}

    private final Map<UUID, PaperPlayerRequestState> players = new ConcurrentHashMap<>();
    private final List<String> sequence = new ArrayList<>();
    private final List<Sent> sent = new ArrayList<>();
    private DirtyColumnTracker tracker;
    private RecordingProcessor processor;
    private MinecraftServer server;
    private ServerLevel overworld;
    private PaperConfig config;
    private PaperDirtyColumnBroadcaster broadcaster;

    @BeforeEach
    void buildRig() {
        tracker = new DirtyColumnTracker();
        processor = new RecordingProcessor(players, sequence);
        overworld = level(Level.OVERWORLD);
        server = mock(MinecraftServer.class);
        when(server.getAllLevels()).thenReturn(List.of(overworld));
        config = new PaperConfig();
        config.validate();
        config.dirtyBroadcastIntervalSeconds = 1; // 20 ticks per broadcast
        broadcaster = new PaperDirtyColumnBroadcaster(server, players, tracker, processor);
        broadcaster.setDirtySender((player, positions) -> {
            sent.add(new Sent(player.getUUID(), positions.clone()));
            sequence.add("send:" + player.getUUID());
        });
    }

    private static ServerLevel level(ResourceKey<Level> key) {
        var l = mock(ServerLevel.class);
        when(l.dimension()).thenReturn(key);
        return l;
    }

    /** Mocked player at chunk (0,0); the state's lastDimension is captured from {@code level}. */
    private PaperPlayerRequestState addPlayer(UUID uuid, ServerLevel level, boolean handshook, boolean removed) {
        var p = mock(ServerPlayer.class);
        when(p.getUUID()).thenReturn(uuid);
        when(p.level()).thenReturn(level);
        when(p.isRemoved()).thenReturn(removed);
        when(p.getName()).thenReturn(Component.literal("p-" + uuid.toString().substring(0, 8)));
        var state = new PaperPlayerRequestState(p, 4, 4);
        if (handshook) state.markHandshakeComplete();
        players.put(uuid, state);
        return state;
    }

    /** Advance exactly one broadcast interval (fires on the last call). */
    private void fireBroadcast() {
        for (int i = 0; i < config.dirtyBroadcastIntervalSeconds * LSSConstants.TICKS_PER_SECOND; i++) {
            broadcaster.tick(config);
        }
    }

    private long[] sortedPositions(long[] a) {
        long[] copy = a.clone();
        Arrays.sort(copy);
        return copy;
    }

    // ---- PP-022: eligibility gates ----

    @Test
    void onlyHandshookSameDimensionNonRemovedPlayersReceive() {
        var eligible = UUID.randomUUID();
        addPlayer(eligible, overworld, true, false);
        addPlayer(UUID.randomUUID(), overworld, false, false);          // handshake gate
        addPlayer(UUID.randomUUID(), level(Level.END), true, false);    // dimension gate
        addPlayer(UUID.randomUUID(), overworld, true, true);            // removed gate

        tracker.markDirty(OVERWORLD, 1, 2);
        fireBroadcast();

        assertEquals(1, sent.size(), "exactly one player passes all three gates");
        assertEquals(eligible, sent.get(0).playerUuid());
        assertArrayEquals(new long[]{PositionUtil.packPosition(1, 2)}, sent.get(0).positions());
        assertEquals(1, processor.clears.size(), "done-bits cleared only for the recipient");
        assertEquals(eligible, processor.clears.get(0).playerUuid());
    }

    // ---- PP-023: invalidation does not depend on recipients ----

    @Test
    void zeroEligiblePlayersStillInvalidatesTimestamps() {
        tracker.markDirty(OVERWORLD, 3, 4);
        fireBroadcast();

        assertEquals(1, processor.invalidations.size(),
                "dirty-while-offline: the timestamp cache must learn about the edit even when "
                        + "nobody is connected, or a later rejoin is answered up-to-date with stale data");
        assertEquals(OVERWORLD, processor.invalidations.get(0).dimension());
        assertArrayEquals(new long[]{PositionUtil.packPosition(3, 4)},
                processor.invalidations.get(0).positions());
        assertTrue(sent.isEmpty());
        assertEquals(0, tracker.pendingCount(), "the dirty set was drained, not deferred");
    }

    // ---- PP-019: range filter, cap, clear-before-send ----

    @Test
    void rangeFilterUsesRawLodDistanceWithoutTheRequestGateBuffer() {
        config.lodDistanceChunks = 8;
        var uuid = UUID.randomUUID();
        addPlayer(uuid, overworld, true, false); // at chunk (0,0)

        tracker.markDirty(OVERWORLD, 8, 0);   // Chebyshev 8: in
        tracker.markDirty(OVERWORLD, 9, 0);   // Chebyshev 9: out (inside the request gate's +32, NOT broadcast)
        tracker.markDirty(OVERWORLD, 0, -9);  // negative side: out
        fireBroadcast();

        assertEquals(1, sent.size());
        assertArrayEquals(new long[]{PositionUtil.packPosition(8, 0)}, sent.get(0).positions(),
                "broadcast range is the raw lodDistance — the +32 buffer applies to requests only");
        assertArrayEquals(sent.get(0).positions(), processor.clears.get(0).positions(),
                "done-bits cleared for exactly the notified set");
        assertArrayEquals(
                new long[]{PositionUtil.packPosition(0, -9), PositionUtil.packPosition(8, 0),
                        PositionUtil.packPosition(9, 0)},
                sortedPositions(processor.invalidations.get(0).positions()),
                "out-of-range positions are still invalidated (other players may be near them)");
    }

    @Test
    void broadcastPaginatesBeyondMaxDirtyColumnPositionsCoveringEveryPosition() {
        var uuid = UUID.randomUUID();
        addPlayer(uuid, overworld, true, false);
        int total = LSSConstants.MAX_DIRTY_COLUMN_POSITIONS + 1; // 10241
        for (int i = 0; i < total; i++) {
            tracker.markDirty(OVERWORLD, i % 128, i / 128); // all within default lodDistance 256
        }

        fireBroadcast();

        // #8 fix: the overflow paginates into a second frame, not dropped — each frame within
        // the wire cap, together covering every in-range position.
        assertEquals(2, sent.size(), "a >cap flood splits into two frames");
        assertEquals(LSSConstants.MAX_DIRTY_COLUMN_POSITIONS, sent.get(0).positions().length, "first frame full");
        assertEquals(1, sent.get(1).positions().length, "second frame carries the remainder");
        var allSent = new HashSet<Long>();
        for (var s : sent) {
            assertTrue(s.positions().length <= LSSConstants.MAX_DIRTY_COLUMN_POSITIONS,
                    "every frame respects the wire cap");
            for (long p : s.positions()) allSent.add(p);
        }
        assertEquals(total, allSent.size(), "every in-range position is notified across the frames");

        var allCleared = new HashSet<Long>();
        for (var c : processor.clears) for (long p : c.positions()) allCleared.add(p);
        assertEquals(allSent, allCleared, "done-bits clear for every notified position");
        assertEquals(total, processor.invalidations.get(0).positions().length,
                "every drained position is invalidated");
        assertEquals(0, tracker.pendingCount());
    }

    @Test
    void clearDiskReadDoneIsQueuedBeforeTheSendAndSurvivesASendFailure() {
        var uuid = UUID.randomUUID();
        addPlayer(uuid, overworld, true, false);

        tracker.markDirty(OVERWORLD, 1, 1);
        fireBroadcast();
        assertEquals(List.of("clear:" + uuid, "send:" + uuid), sequence,
                "the done-bit clear is queued BEFORE the send");

        sequence.clear();
        broadcaster.setDirtySender((player, positions) -> { throw new IllegalStateException("wire down"); });
        tracker.markDirty(OVERWORLD, 2, 2);
        assertDoesNotThrow(this::fireBroadcast);
        assertEquals(List.of("clear:" + uuid), sequence,
                "a throwing send still leaves the clear applied (the client re-requests and re-resolves)");
    }

    // ---- PP-020: cadence ----

    @Test
    void firesExactlyEveryIntervalTicksAndResetsTheCounter() {
        var uuid = UUID.randomUUID();
        addPlayer(uuid, overworld, true, false);
        tracker.markDirty(OVERWORLD, 1, 0);

        for (int i = 0; i < 19; i++) broadcaster.tick(config);
        assertTrue(sent.isEmpty(), "nothing fires before tick interval*20");
        broadcaster.tick(config);
        assertEquals(1, sent.size(), "fires exactly on the 20th tick at interval 1s");

        tracker.markDirty(OVERWORLD, 2, 0);
        for (int i = 0; i < 19; i++) broadcaster.tick(config);
        assertEquals(1, sent.size(), "counter was reset — the next window is again 20 full ticks");
        broadcaster.tick(config);
        assertEquals(2, sent.size());
    }

    @Test
    void midRunConfigIntervalChangeTakesEffectImmediately() {
        var uuid = UUID.randomUUID();
        addPlayer(uuid, overworld, true, false);
        tracker.markDirty(OVERWORLD, 1, 0);

        for (int i = 0; i < 10; i++) broadcaster.tick(config); // counter 10 of 20
        config.dirtyBroadcastIntervalSeconds = 2;              // window stretches to 40 ticks
        for (int i = 0; i < 29; i++) broadcaster.tick(config); // counter 39 of 40
        assertTrue(sent.isEmpty(), "the longer interval is honored mid-window (no caching)");
        broadcaster.tick(config);
        assertEquals(1, sent.size(), "fires at the NEW interval boundary");
    }

    // ---- PP-021: failed-send isolation ----

    @Test
    void throwingSendSkipsOnlyThatPlayerAndRetriesOnTheNextBroadcast() {
        var failing = UUID.randomUUID();
        var healthy = UUID.randomUUID();
        addPlayer(failing, overworld, true, false);
        addPlayer(healthy, overworld, true, false);
        broadcaster.setDirtySender((player, positions) -> {
            if (player.getUUID().equals(failing)) throw new IllegalStateException("kaput");
            sent.add(new Sent(player.getUUID(), positions.clone()));
        });

        tracker.markDirty(OVERWORLD, 1, 0);
        assertDoesNotThrow(this::fireBroadcast);
        // Iteration order over the player map is unspecified; assert per-player outcomes.
        // (The cross-LEVEL short-circuit set is unreachable today: each level has a unique
        // dimension key and its dirty set is drained once, so a player matches at most one
        // send per broadcast — the per-broadcast skip is pinned via isolation + retry.)
        assertEquals(1, sent.size(), "the healthy player still receives in the same broadcast");
        assertEquals(healthy, sent.get(0).playerUuid());
        assertEquals(2, processor.clears.size(),
                "the failed player's clear was already queued (clear-before-send)");

        // The failure is broadcast-scoped: the next interval retries the failed player.
        broadcaster.setDirtySender((player, positions) -> sent.add(new Sent(player.getUUID(), positions.clone())));
        sent.clear();
        tracker.markDirty(OVERWORLD, 2, 0);
        fireBroadcast();
        assertEquals(2, sent.size(), "both players receive once the connection heals");
        assertTrue(sent.stream().anyMatch(s -> s.playerUuid().equals(failing)),
                "no permanent blacklist from a single failed send");
    }
}
