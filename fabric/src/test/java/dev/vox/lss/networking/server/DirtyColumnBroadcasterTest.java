package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.common.tracking.DirtyColumnTracker;
import dev.vox.lss.config.LSSServerConfig;
import dev.vox.lss.networking.payloads.DirtyColumnsS2CPayload;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests of {@link DirtyColumnBroadcaster} through its test seams (injected dimension
 * source + {@link DirtyColumnBroadcaster.PlayerView}): exact broadcast cadence, the raw
 * lodDistance range gate (no request-gate +32 buffer), per-dimension recipient isolation,
 * the MAX_POSITIONS pagination (a >cap flood splits into multiple packets, not truncated),
 * clear-before-send ordering, and the broadcast-scoped send-failure skip.
 */
class DirtyColumnBroadcasterTest {

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---- recorded seam events (one ordered log shared by processor + view) ----

    private record Invalidated(String dimension, long[] positions) {}
    private record Cleared(UUID player, long[] positions) {}
    private record Sent(UUID player, long[] positions) {}
    private record SendFailed(UUID player) {}

    /** Records the broadcaster's two processor interactions instead of posting to the mailbox. */
    private static final class RecordingProcessor extends FabricOffThreadProcessor {
        private final List<Object> events;

        RecordingProcessor(Map<UUID, PlayerRequestState> players, List<Object> events) {
            super(players, null, false, null, 1);
            this.events = events;
        }

        @Override
        public void invalidateTimestamps(String dimension, long[] positions) {
            this.events.add(new Invalidated(dimension, positions.clone()));
        }

        @Override
        public void clearDiskReadDone(UUID playerUuid, long[] positions) {
            this.events.add(new Cleared(playerUuid, positions.clone()));
        }
    }

    /** A request state with no live ServerPlayer: dimension and name come from the fake. */
    private static final class FakePlayerState extends PlayerRequestState {
        private final ResourceKey<Level> dimension;

        FakePlayerState(ResourceKey<Level> dimension) {
            super(UUID.randomUUID(), 4, 4);
            this.dimension = dimension;
        }

        @Override
        public ResourceKey<Level> getLastDimension() { return this.dimension; }

        @Override
        public String getPlayerName() { return "fake-" + getPlayerUUID(); }
    }

    private static final class FakeView implements DirtyColumnBroadcaster.PlayerView {
        final Map<UUID, int[]> chunkPositions = new HashMap<>();
        final Set<UUID> removed = new HashSet<>();
        final Set<UUID> failSends = new HashSet<>();
        private final List<Object> events;

        FakeView(List<Object> events) { this.events = events; }

        @Override
        public boolean isRemoved(PlayerRequestState state) {
            return this.removed.contains(state.getPlayerUUID());
        }

        @Override
        public int chunkX(PlayerRequestState state) {
            return this.chunkPositions.get(state.getPlayerUUID())[0];
        }

        @Override
        public int chunkZ(PlayerRequestState state) {
            return this.chunkPositions.get(state.getPlayerUUID())[1];
        }

        @Override
        public void send(PlayerRequestState state, DirtyColumnsS2CPayload payload) {
            if (this.failSends.contains(state.getPlayerUUID())) {
                this.events.add(new SendFailed(state.getPlayerUUID()));
                throw new IllegalStateException("injected send failure");
            }
            this.events.add(new Sent(state.getPlayerUUID(), payload.dirtyPositions().clone()));
        }
    }

    private static final class Rig {
        // LinkedHashMap: the broadcaster iterates players.values(), so insertion order
        // makes cross-player ordering assertions deterministic.
        final Map<UUID, PlayerRequestState> players = new LinkedHashMap<>();
        final DirtyColumnTracker tracker = new DirtyColumnTracker();
        final List<Object> events = new ArrayList<>();
        final FakeView view = new FakeView(this.events);
        final LSSServerConfig config = new LSSServerConfig();
        final DirtyColumnBroadcaster broadcaster;

        Rig(List<ResourceKey<Level>> dimensions) {
            this.config.dirtyBroadcastIntervalSeconds = 1; // 20 tick() calls per broadcast
            this.config.lodDistanceChunks = 8;
            var processor = new RecordingProcessor(this.players, this.events);
            this.broadcaster = new DirtyColumnBroadcaster(this.players, processor, this.tracker,
                    () -> dimensions, this.view);
        }

        FakePlayerState addPlayer(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
            var state = addPlayerWithoutHandshake(dimension, chunkX, chunkZ);
            state.markHandshakeComplete();
            return state;
        }

        FakePlayerState addPlayerWithoutHandshake(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
            var state = new FakePlayerState(dimension);
            this.players.put(state.getPlayerUUID(), state);
            this.view.chunkPositions.put(state.getPlayerUUID(), new int[]{chunkX, chunkZ});
            return state;
        }

        /** One full broadcast interval at the configured cadence. */
        void fire() {
            int intervalTicks = this.config.dirtyBroadcastIntervalSeconds * LSSConstants.TICKS_PER_SECOND;
            for (int i = 0; i < intervalTicks; i++) {
                this.broadcaster.tick(this.config);
            }
        }
    }

    // ---- event-log helpers ----

    private static List<long[]> sentTo(Rig rig, FakePlayerState player) {
        var out = new ArrayList<long[]>();
        for (var e : rig.events) {
            if (e instanceof Sent sent && sent.player().equals(player.getPlayerUUID())) {
                out.add(sent.positions());
            }
        }
        return out;
    }

    private static List<long[]> clearsFor(Rig rig, FakePlayerState player) {
        var out = new ArrayList<long[]>();
        for (var e : rig.events) {
            if (e instanceof Cleared cleared && cleared.player().equals(player.getPlayerUUID())) {
                out.add(cleared.positions());
            }
        }
        return out;
    }

    private static List<long[]> invalidationsFor(Rig rig, String dimension) {
        var out = new ArrayList<long[]>();
        for (var e : rig.events) {
            if (e instanceof Invalidated inv && inv.dimension().equals(dimension)) {
                out.add(inv.positions());
            }
        }
        return out;
    }

    private static long[] only(List<long[]> batches) {
        assertEquals(1, batches.size(), "expected exactly one batch");
        return batches.get(0);
    }

    private static Set<Long> asSet(long[] positions) {
        var set = new HashSet<Long>();
        for (long p : positions) set.add(p);
        return set;
    }

    private static int firstIndex(Rig rig, Predicate<Object> matcher) {
        for (int i = 0; i < rig.events.size(); i++) {
            if (matcher.test(rig.events.get(i))) return i;
        }
        return -1;
    }

    private static long count(Rig rig, Predicate<Object> matcher) {
        return rig.events.stream().filter(matcher).count();
    }

    // ---- FP-047: cadence ----

    @Test
    void firesOnExactlyTheIntervalTickAndCounterRestartsFromZero() {
        var rig = new Rig(List.of(Level.OVERWORLD));
        var player = rig.addPlayer(Level.OVERWORLD, 0, 0);
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 3, 4);

        // dirtyBroadcastIntervalSeconds=1 → exactly 20 tick() calls per broadcast
        for (int t = 1; t <= 19; t++) {
            rig.broadcaster.tick(rig.config);
            assertEquals(1, rig.tracker.pendingCount(), "tick " + t + " must not drain the tracker");
            assertTrue(rig.events.isEmpty(), "tick " + t + " must not invalidate, clear, or send");
        }
        rig.broadcaster.tick(rig.config); // the 20th
        assertEquals(0, rig.tracker.pendingCount(), "the 20th tick drains and broadcasts");
        assertArrayEquals(new long[]{PositionUtil.packPosition(3, 4)}, only(sentTo(rig, player)));

        // Counter restarted at zero and the interval is re-derived from config each tick:
        // at 2 s the next broadcast needs exactly 40 further ticks.
        rig.events.clear();
        rig.config.dirtyBroadcastIntervalSeconds = 2;
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 3, 4);
        for (int t = 1; t <= 39; t++) {
            rig.broadcaster.tick(rig.config);
            assertTrue(rig.events.isEmpty(), "tick " + t + " of the 2s interval must stay silent");
        }
        rig.broadcaster.tick(rig.config); // the 40th
        assertEquals(1, sentTo(rig, player).size(), "the 40th tick of the 2s interval broadcasts");
    }

    // ---- FP-048: range gate ----

    @Test
    void rangeGateUsesRawLodDistanceWithoutTheRequestGateBuffer() {
        var rig = new Rig(List.of(Level.OVERWORLD));
        rig.config.lodDistanceChunks = 8;
        var player = rig.addPlayer(Level.OVERWORLD, 100, 200);

        long inAtBoundary = PositionUtil.packPosition(108, 192);  // Chebyshev exactly 8
        long outJustBeyond = PositionUtil.packPosition(109, 200); // Chebyshev 9: well inside the
        // request gate (lodDistance + LOD_DISTANCE_BUFFER = 40) but outside the broadcast gate —
        // columns a client holds via the +32 request buffer never receive dirty pushes.
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 108, 192);
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 109, 200);
        rig.fire();

        assertArrayEquals(new long[]{inAtBoundary}, only(sentTo(rig, player)),
                "exactly lodDistance is in range; lodDistance+1 is not");
        assertArrayEquals(new long[]{inAtBoundary}, only(clearsFor(rig, player)),
                "the out-of-range position keeps its done-bit");
        assertEquals(Set.of(inAtBoundary, outJustBeyond),
                asSet(only(invalidationsFor(rig, LSSConstants.DIM_STR_OVERWORLD))),
                "timestamp invalidation is range-blind");
    }

    // ---- FP-049: dimension isolation ----

    @Test
    void onlySameDimensionPlayersAreNotifiedAndCleared() {
        var rig = new Rig(List.of(Level.OVERWORLD, Level.END));
        var overworldPlayer = rig.addPlayer(Level.OVERWORLD, 0, 0);
        var endPlayer = rig.addPlayer(Level.END, 0, 0);

        long overworldPos = PositionUtil.packPosition(1, 1);
        long endPos = PositionUtil.packPosition(2, 2);
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 1, 1);
        rig.tracker.markDirty(LSSConstants.DIM_STR_THE_END, 2, 2);
        rig.fire();

        assertArrayEquals(new long[]{overworldPos}, only(sentTo(rig, overworldPlayer)));
        assertArrayEquals(new long[]{endPos}, only(sentTo(rig, endPlayer)));
        assertEquals(2, count(rig, e -> e instanceof Sent), "one notification per player, no cross-dimension fan-out");

        assertArrayEquals(new long[]{overworldPos}, only(clearsFor(rig, overworldPlayer)),
                "End positions must not clear the overworld player's done-bits");
        assertArrayEquals(new long[]{endPos}, only(clearsFor(rig, endPlayer)));

        // Per-dimension timestamp invalidation still applies to both dimensions.
        assertArrayEquals(new long[]{overworldPos}, only(invalidationsFor(rig, LSSConstants.DIM_STR_OVERWORLD)));
        assertArrayEquals(new long[]{endPos}, only(invalidationsFor(rig, LSSConstants.DIM_STR_THE_END)));
    }

    // ---- FP-050 (#8 fix): >MAX_POSITIONS flood paginates instead of truncating ----

    @Test
    void floodBeyondMaxPositionsPaginatesAcrossPacketsCoveringEveryPosition() {
        var rig = new Rig(List.of(Level.OVERWORLD));
        rig.config.lodDistanceChunks = 128;
        var player = rig.addPlayer(Level.OVERWORLD, 0, 0);

        assertEquals(10240, DirtyColumnsS2CPayload.MAX_POSITIONS, "the per-packet wire cap");
        int flood = DirtyColumnsS2CPayload.MAX_POSITIONS + 1;
        var dirtySet = new HashSet<Long>();
        outer:
        for (int x = 0; x <= 101; x++) { // all within Chebyshev 101 < 128 of the player
            for (int z = 0; z <= 101; z++) {
                rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, x, z);
                dirtySet.add(PositionUtil.packPosition(x, z));
                if (dirtySet.size() == flood) break outer;
            }
        }
        assertEquals(flood, dirtySet.size());

        rig.fire();

        // The overflow is split into a second packet, not dropped: every in-range position is
        // notified, each packet still respects the wire cap.
        var batches = sentTo(rig, player);
        assertEquals(2, batches.size(), "a >cap flood splits into two packets");
        assertEquals(DirtyColumnsS2CPayload.MAX_POSITIONS, batches.get(0).length, "first packet is full");
        assertEquals(1, batches.get(1).length, "second packet carries the remainder");
        var allSent = new HashSet<Long>();
        for (long[] b : batches) {
            assertTrue(b.length <= DirtyColumnsS2CPayload.MAX_POSITIONS, "every packet respects the wire cap");
            for (long p : b) allSent.add(p);
        }
        assertEquals(dirtySet, allSent, "every in-range dirty position is notified across the packets");

        var allCleared = new HashSet<Long>();
        for (long[] c : clearsFor(rig, player)) for (long p : c) allCleared.add(p);
        assertEquals(dirtySet, allCleared, "done-bits clear for every notified position");
        assertEquals(0, rig.tracker.pendingCount(), "the whole set was drained");

        // The complete set went out in this one broadcast, so a later interval re-broadcasts nothing.
        rig.events.clear();
        rig.fire();
        assertTrue(rig.events.isEmpty(), "nothing left to re-broadcast after a complete paginated send");
    }

    // ---- FP-051: clear-before-send ordering ----

    @Test
    void doneBitClearIsQueuedBeforeTheSendAndSurvivesASendFailure() {
        var rig = new Rig(List.of(Level.OVERWORLD));
        rig.addPlayer(Level.OVERWORLD, 0, 0);
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 1, 2);
        rig.fire();

        int clearIdx = firstIndex(rig, e -> e instanceof Cleared);
        int sendIdx = firstIndex(rig, e -> e instanceof Sent);
        assertTrue(clearIdx >= 0 && sendIdx >= 0, "both the clear and the send must happen");
        assertTrue(clearIdx < sendIdx, "clearDiskReadDone must be queued before the notification: "
                + "a re-request racing the broadcast re-serves at worst, never gets a stale up_to_date");

        // A throwing send still leaves the clear applied (the re-serve path stays honest).
        var rig2 = new Rig(List.of(Level.OVERWORLD));
        var failing = rig2.addPlayer(Level.OVERWORLD, 0, 0);
        rig2.view.failSends.add(failing.getPlayerUUID());
        rig2.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 1, 2);
        assertDoesNotThrow(rig2::fire);

        assertArrayEquals(new long[]{PositionUtil.packPosition(1, 2)}, only(clearsFor(rig2, failing)),
                "the clear is applied even though the send threw");
        assertTrue(sentTo(rig2, failing).isEmpty());
        int clearIdx2 = firstIndex(rig2, e -> e instanceof Cleared);
        int failIdx = firstIndex(rig2, e -> e instanceof SendFailed);
        assertTrue(failIdx >= 0, "the send was attempted");
        assertTrue(clearIdx2 < failIdx, "the clear preceded the failed send and is not rolled back");
    }

    // ---- FP-052: send-failure skip is broadcast-scoped ----

    /**
     * Pins the observable contract of the failedPlayers set: the exception is contained, players
     * iterated after the failure still receive the same broadcast, and the next interval attempts
     * the failed player again (the set is local to one tick). The "skip across remaining
     * dimensions in the same broadcast" leg is structurally unobservable: the dimension gate
     * already excludes a player from every level other than their own.
     */
    @Test
    void sendFailureIsContainedSkipsOnlyThatPlayerAndOnlyThisBroadcast() {
        var rig = new Rig(List.of(Level.OVERWORLD));
        var failing = rig.addPlayer(Level.OVERWORLD, 0, 0); // inserted first → iterated first
        var healthy = rig.addPlayer(Level.OVERWORLD, 0, 0);
        rig.view.failSends.add(failing.getPlayerUUID());
        long pos = PositionUtil.packPosition(5, 5);
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 5, 5);

        assertDoesNotThrow(rig::fire, "a send failure must not escape the tick");
        assertEquals(1, count(rig, e -> e instanceof SendFailed));
        assertTrue(sentTo(rig, failing).isEmpty());
        assertArrayEquals(new long[]{pos}, only(sentTo(rig, healthy)),
                "the player iterated after the failure still receives this broadcast");

        // Broadcast-scoped: the next interval attempts the failed player again.
        rig.events.clear();
        rig.view.failSends.remove(failing.getPlayerUUID());
        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 5, 5);
        rig.fire();
        assertArrayEquals(new long[]{pos}, only(sentTo(rig, failing)),
                "failedPlayers is broadcast-local — no sticky exclusion");
        assertArrayEquals(new long[]{pos}, only(sentTo(rig, healthy)));
    }

    // ---- seam-guard: eligibility gates ----

    @Test
    void handshakeAndRemovedGatesExcludePlayersWithoutClearingDoneBits() {
        var rig = new Rig(List.of(Level.OVERWORLD));
        var eligible = rig.addPlayer(Level.OVERWORLD, 0, 0);
        var noHandshake = rig.addPlayerWithoutHandshake(Level.OVERWORLD, 0, 0);
        var removed = rig.addPlayer(Level.OVERWORLD, 0, 0);
        rig.view.removed.add(removed.getPlayerUUID());

        rig.tracker.markDirty(LSSConstants.DIM_STR_OVERWORLD, 1, 1);
        rig.fire();

        assertArrayEquals(new long[]{PositionUtil.packPosition(1, 1)}, only(sentTo(rig, eligible)));
        assertEquals(1, count(rig, e -> e instanceof Sent), "only the eligible player is notified");
        assertEquals(1, count(rig, e -> e instanceof Cleared), "gated players keep their done-bits");
        assertTrue(sentTo(rig, noHandshake).isEmpty());
        assertTrue(sentTo(rig, removed).isEmpty());
        assertTrue(clearsFor(rig, noHandshake).isEmpty());
        assertTrue(clearsFor(rig, removed).isEmpty());
    }
}
