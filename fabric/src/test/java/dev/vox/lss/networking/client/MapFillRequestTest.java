package dev.vox.lss.networking.client;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.PositionUtil;
import dev.vox.lss.networking.client.LodRequestManager.MapFillRequest;
import dev.vox.lss.networking.payloads.SessionConfigS2CPayload;
import dev.vox.lss.networking.payloads.VoxelColumnS2CPayload;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #380 at the request manager: terrain no map was given is owed to the map, nothing is
 * asked for until the player asks, and the answers go to the map alone. Driven through the real
 * receive glue, the real delivery receipts and the real scan phase; the test plays the server
 * (it answers what is declared) and the map (it writes a column, lets it go, reports it, or
 * holds it for later, as the bridges do).
 */
class MapFillRequestTest {

    private static final byte[] CONTENT = {2, 0, 0, 0}; // lead varint 2: a non-clear body
    private static final long SECOND = 1_000_000_000L;
    private static final long SATISFIED = ColumnStateMap.SATISFIED;

    @BeforeAll
    static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** What a map that is being written does with a column it is handed. */
    private enum MapDoes { WRITES, LETS_GO, REPORTS, HOLDS, WRITES_THEN_REPORTS }

    private record Batch(long[] positions, long[] timestamps) {
        boolean has(long position) {
            return indexOf(position) >= 0;
        }

        int indexOf(long position) {
            for (int i = 0; i < positions.length; i++) if (positions[i] == position) return i;
            return -1;
        }
    }

    private record Offered(long packed, boolean resync, boolean mapOnly, ColumnDelivery delivery) {}

    /** A column the map has queued: its delivery and the lease the map took on it. */
    private record Held(ColumnDelivery delivery, Runnable release) {
        void write() {
            delivery.written();
            release.run();
        }

        void letGo() {
            release.run();
        }
    }

    private LodRequestManager manager;
    private ResourceKey<Level> dim;
    /** The probe: a map backend is registered and takes columns right now. */
    private boolean mapWriting;
    private MapDoes mapDoes;
    private final Map<Long, MapDoes> mapDoesAt = new HashMap<>();
    private final List<Held> heldByMap = new ArrayList<>();
    /** When non-null, delivery events wait here instead of running at once. */
    private List<Runnable> queuedEvents;
    private long nanos;
    private int px, pz, view;
    private final List<String> notices = new ArrayList<>();
    private final List<Batch> sent = new ArrayList<>();
    private final List<Offered> offered = new ArrayList<>();
    /** The stamp of every column the server holds (assigned when it is first asked for). */
    private final Long2LongOpenHashMap server = new Long2LongOpenHashMap();
    private long nextStamp;
    /** Positions the client held when they were asked for as "I hold nothing": a fill's asks. */
    private final LongArrayList mapAsks = new LongArrayList();

    private final ClientColumnProcessor processor = new ClientColumnProcessor((d, x, z) -> {}, () -> null) {
        @Override
        void offer(VoxelColumnS2CPayload payload, boolean resync, ColumnDelivery delivery) {
            hand(payload, resync, delivery, false);
        }

        @Override
        void offer(VoxelColumnS2CPayload payload, boolean resync, ColumnDelivery delivery, boolean mapOnly) {
            hand(payload, resync, delivery, mapOnly);
        }

        /** What the drain and a map bridge do with a column: the map says "written" before
         *  its lease is released, or says nothing; then the drain completes the delivery. */
        private void hand(VoxelColumnS2CPayload payload, boolean resync, ColumnDelivery delivery, boolean mapOnly) {
            long packed = PositionUtil.packPosition(payload.chunkX(), payload.chunkZ());
            offered.add(new Offered(packed, resync, mapOnly, delivery));
            if (delivery == null) return;
            if (mapWriting) {
                switch (mapDoesAt.getOrDefault(packed, mapDoes)) {
                    case WRITES -> delivery.written();
                    case REPORTS -> delivery.report();
                    case WRITES_THEN_REPORTS -> {
                        delivery.written(); // the surface tile committed
                        delivery.report();  // and a cave tile of the same column was dropped
                    }
                    case HOLDS -> heldByMap.add(new Held(delivery, delivery.deferAcceptance()));
                    case LETS_GO -> { }
                }
            }
            delivery.complete();
        }
    };

    @BeforeEach
    void setUp() {
        ClientCommandActions.setMenuButtonsForTest(true); // the Sodium 0.8 options page, with its buttons
        start(9, LSSConstants.PROTOCOL_VERSION, true);
    }

    @org.junit.jupiter.api.AfterEach
    void noMenu() {
        ClientCommandActions.setMenuButtonsForTest(false);
    }

    private void start(int lodDistance, int protocol, boolean cacheLoaded) {
        start(lodDistance, protocol, cacheLoaded, "map-fill-test");
    }

    private void start(int lodDistance, int protocol, boolean cacheLoaded, String address) {
        manager = new LodRequestManager();
        manager.joinSlowStartEnabled = () -> false;
        manager.mapWritingProbe = () -> mapWriting;
        manager.mapFillNotice = line -> notices.add(describe(line));
        queuedEvents = null;
        manager.deliveryExecutor = event -> {
            if (queuedEvents != null) queuedEvents.add(event);
            else event.run();
        };
        manager.mapFillForTest().clock = () -> nanos;
        manager.onSessionConfig(new SessionConfigS2CPayload(protocol, true, lodDistance, true), address);
        dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:map_fill"));
        manager.setLastDimensionForTest(dim);
        if (cacheLoaded) manager.markCacheLoadedForTest();
        manager.setBatchSenderForTest(p -> sent.add(new Batch(
                Arrays.copyOf(p.packedPositions(), p.count()),
                Arrays.copyOf(p.clientTimestamps(), p.count()))));
        sent.clear();
        notices.clear();
        offered.clear();
        heldByMap.clear();
        mapDoesAt.clear();
        mapAsks.clear();
        mapWriting = false;
        mapDoes = MapDoes.WRITES;
        px = 0;
        pz = 0;
        view = 0;
    }

    /** The line's translation key and arguments (the brand prefix is the literal in front). */
    private static String describe(Component line) {
        for (Component part : line.getSiblings()) {
            if (part.getContents() instanceof TranslatableContents text) {
                return text.getKey() + Arrays.toString(text.getArgs());
            }
        }
        return line.getString();
    }

    private static long p(int x, int z) {
        return PositionUtil.packPosition(x, z);
    }

    private ColumnStateMap columns() {
        return manager.columnsForTest();
    }

    /** One second passes and the scan fires; the batch it declared, or null when it sent none. */
    private Batch fire() {
        nanos += SECOND;
        int before = sent.size();
        for (int tick = 0; tick <= LSSConstants.TICKS_PER_SECOND; tick++) {
            if (manager.tickScanPhase(px, pz, view, 0, 0L, -1, () -> 0) >= 0) {
                return sent.size() > before ? sent.get(sent.size() - 1) : null;
            }
        }
        throw new AssertionError("the scan cadence never fired");
    }

    private Offered receive(long packed, long stamp) {
        int before = offered.size();
        LSSClientNetworking.handleVoxelColumn(manager, processor, new VoxelColumnS2CPayload(
                PositionUtil.unpackX(packed), PositionUtil.unpackZ(packed), dim, stamp, CONTENT));
        return offered.size() > before ? offered.get(offered.size() - 1) : null;
    }

    private long serverStamp(long position) {
        if (!server.containsKey(position)) server.put(position, ++nextStamp + 1000);
        return server.get(position);
    }

    /** The server answers one batch: "up to date" for a stamp it agrees with, else the column. */
    private void serve(Batch batch) {
        if (batch == null) return;
        for (int i = 0; i < batch.positions().length; i++) {
            long position = batch.positions()[i];
            long stamp = serverStamp(position);
            if (batch.timestamps()[i] == stamp) {
                manager.onColumnUpToDate(position);
                continue;
            }
            if (batch.timestamps()[i] <= 0 && columns().timestampFor(position) > 0) mapAsks.add(position);
            receive(position, stamp);
        }
    }

    /** Scans and serves until the scan declares nothing (no fill is running). */
    private void converge() {
        for (int round = 0; round < 400; round++) {
            Batch batch = fire();
            if (batch == null) return;
            serve(batch);
        }
        throw new AssertionError("the scan did not converge");
    }

    /** Scans and serves for this many seconds. */
    private void run(int seconds) {
        for (int i = 0; i < seconds; i++) serve(fire());
    }

    private boolean finished() {
        return notices.stream().anyMatch(n -> n.startsWith("lss.mapfill.finished")
                || n.startsWith("lss.mapfill.done_here"));
    }

    /** The settled-debt bit the next save takes to the record (not merely "not owed", which a
     *  column on its way to a map is too). Consumes the state: call it last. */
    private boolean settledForTheRecord(long position) {
        var settled = columns().detachForSave().mapSettled;
        return (settled.get(ColumnStateMap.leafKeyFor(position)) & (1L << ColumnStateMap.bitIndexFor(position))) != 0;
    }

    /** Scans and serves until the fill says it is done. */
    private void fillToTheEnd() {
        for (int round = 0; round < 300; round++) {
            serve(fire());
            if (finished()) return;
        }
        throw new AssertionError("the fill never finished; notices " + notices
                + ", owed " + manager.getMapOwedColumns());
    }

    private String lastNotice() {
        return notices.isEmpty() ? null : notices.get(notices.size() - 1);
    }

    /** A download with no map, a map that appears, a press, and the grace after it. */
    private void pressAfterADownloadWithoutAMap(int lodDistance) {
        start(lodDistance, LSSConstants.PROTOCOL_VERSION, true);
        converge();
        mapWriting = true;
        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        assertNull(fire(), "nothing is asked in the first seconds after the request");
        assertNull(fire());
    }

    // ---- the debt ----

    @Test
    void terrainThatArrivesWhileNoMapIsWrittenIsOwedAndWithAMapNothingIs() {
        converge();
        assertEquals(360, columns().size(), "premise: the whole lod-9 disc but the player's own column");
        assertEquals(360, manager.getMapOwedColumns());
        assertTrue(offered.stream().noneMatch(Offered::mapOnly));
        assertTrue(notices.isEmpty(), "no map: nobody is told anything");

        start(9, LSSConstants.PROTOCOL_VERSION, true);
        mapWriting = true;
        converge();
        assertEquals(360, columns().size());
        assertEquals(0, manager.getMapOwedColumns(), "a normal session with a map on owes nothing");
        assertTrue(notices.isEmpty());
        assertEquals(MapFillRequest.Outcome.NOTHING_OWED, manager.requestMapFill().outcome());
        assertFalse(manager.mapFillForTest().requested(), "and with nothing owed anywhere, nothing is asked for");
        for (int i = 0; i < 5; i++) assertNull(fire());
        assertTrue(notices.isEmpty(), "a press that found nothing says nothing more: " + notices);
    }

    /** "A map is being written" at the moment a column arrives is a guess. What the map then
     *  does decides: a column it lets go (switched off meanwhile, blocked, out of memory, an
     *  early return) is owed, though nothing failed and nothing is sent again by itself. */
    @Test
    void aColumnTheMapWasHandedAndDidNotWriteIsOwed() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        mapWriting = true;
        mapDoes = MapDoes.LETS_GO;
        converge();
        assertEquals(48, columns().size(), "premise: every column is held, none was reported");
        assertEquals(48, manager.getMapOwedColumns());
    }

    /** While the map still holds a column in its queue, the column counts as on its way (a
     *  running fill must not ask for it again); the debt is decided when the map is done. */
    @Test
    void aQueuedColumnIsDecidedWhenTheMapIsDoneWithIt() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        mapWriting = true;
        mapDoes = MapDoes.HOLDS;
        converge();
        assertEquals(48, heldByMap.size());
        assertEquals(0, manager.getMapOwedColumns(), "on their way to the map");

        for (int i = 0; i < 48; i++) {
            if (i < 40) heldByMap.get(i).write();
            else heldByMap.get(i).letGo();
        }
        assertEquals(8, manager.getMapOwedColumns(), "the eight the map let go");
        assertTrue(columns().isMapOwed(heldByMap.get(47).delivery().packed));
        assertFalse(columns().isMapOwed(heldByMap.get(0).delivery().packed));
    }

    /** A handful of owed columns (a setting flipped mid-session) is not worth a chat line;
     *  the button still fills them. */
    @Test
    void aPressIsNotFollowedByTheContinuingLine() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge(); // no map yet: nothing was said this stay
        mapWriting = true;
        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        fire();
        fire();
        assertTrue(notices.isEmpty(), "the press already said what happens: " + notices);
    }

    @Test
    void aSmallDebtIsNotAnnounced() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge();
        assertEquals(48, manager.getMapOwedColumns());
        assertTrue(48 < MapFill.HINT_MINIMUM);
        mapWriting = true;
        assertNull(fire());
        assertTrue(notices.isEmpty());
        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        assertNull(fire());
        assertNull(fire());
        assertEquals(48, fire().positions().length);
    }

    // ---- the #380 story ----

    @Test
    void aMapThatAppearsStartsNoDownloadThePlayerIsToldOnceAndAFillGoesToTheMapAlone() {
        converge();
        var stampsBefore = new Long2LongOpenHashMap();
        columns().forEachPresentForTest(stampsBefore::put);
        assertEquals(360, stampsBefore.size());

        mapWriting = true; // Map Ingestion switched on, and the player reconnected
        assertNull(fire(), "a map that appears starts no download");
        assertEquals(List.of("lss.mapfill.owed[360, " + dev.vox.lss.common.Brand.clientCommand() + "]"), notices);
        assertNull(fire());
        assertEquals(1, notices.size(), "told once");

        MapFillRequest request = manager.requestMapFill();
        assertEquals(MapFillRequest.Outcome.STARTED, request.outcome());
        assertEquals(360, request.columns());

        offered.clear();
        fillToTheEnd();
        assertEquals(360, mapAsks.size(), "every owed column was asked for");
        assertEquals(360, new LongOpenHashSet(mapAsks).size(), "and each of them once");
        assertEquals(360, offered.size());
        assertTrue(offered.stream().allMatch(Offered::mapOnly), "every answer went to the map alone");
        assertTrue(offered.stream().noneMatch(Offered::resync), "and none was air-filled for it");
        assertEquals(0, manager.getMapOwedColumns());
        assertEquals("lss.mapfill.finished[360, 0]", lastNotice());
        for (var entry : stampsBefore.long2LongEntrySet()) {
            assertEquals(entry.getLongValue(), columns().timestampFor(entry.getLongKey()),
                    "the held stamps are what they were");
            assertEquals(SATISFIED, columns().classify(entry.getLongKey()), "and the scan has nothing to ask");
        }

        int noticesAfter = notices.size();
        assertNull(fire(), "and the fill is over here: nothing more is asked");
        assertNull(fire());
        assertEquals(noticesAfter, notices.size());
        assertFalse(manager.mapFillForTest().working());
        assertFalse(manager.mapFillForTest().requested(), "this cache owes the map nothing more: the request ended");
        assertEquals(360, manager.getMapFillDelivered());
    }

    @Test
    void aFillNeverGoesBeforeTerrainNobodyHolds() {
        pressAfterADownloadWithoutAMap(3);

        long hole = p(2, -1);
        manager.onIngestFailure(dim, hole); // its consumer never got it: asked for again as terrain
        Batch batch = fire();
        assertEquals(1, batch.positions().length, "only the column nobody holds");
        assertEquals(hole, batch.positions()[0]);
        assertEquals(0, manager.mapFillForTest().askedForTest(), "the fill waits");

        receive(hole, 9000);
        assertFalse(manager.isMapOwed(hole), "it arrived for everyone while a map is written");
        batch = fire();
        assertEquals(47, batch.positions().length, "now the owed columns ride");
        assertFalse(batch.has(hole));
    }

    @Test
    void theBatchNeverGrowsPastTheScansBudget() {
        pressAfterADownloadWithoutAMap(24); // 2,400 columns: more than one batch
        assertEquals(49 * 49 - 1, columns().size());
        Batch batch = fire();
        int budget = manager.scannerForTest().getLastBudget();
        assertTrue(budget < 2400, "premise: the disc does not fit one batch");
        assertEquals(budget, batch.positions().length, "exactly what the scan's budget leaves");
        // nearest first: the first pick is next to the player
        int first = Math.max(Math.abs(PositionUtil.unpackX(batch.positions()[0])),
                Math.abs(PositionUtil.unpackZ(batch.positions()[0])));
        assertTrue(first <= 8, "the nearest leaf comes first");
        // unanswered picks are declared again, like the scan's own wants
        Batch again = fire();
        assertEquals(new java.util.HashSet<>(Arrays.stream(batch.positions()).boxed().toList()),
                new java.util.HashSet<>(Arrays.stream(again.positions()).boxed().toList()));
    }

    // ---- answers ----

    /** An answer that is not newer than the column held is that column: the map alone takes
     *  it and nothing about the held column changes. "Not newer", not "equal": a validation
     *  can move the held stamp past the column's own (the server's verification second),
     *  and the stamp is never moved backwards. */
    @Test
    void anAnswerThatIsNotNewerThanTheColumnHeldGoesToTheMapAlone() {
        pressAfterADownloadWithoutAMap(3);
        Batch batch = fire();
        long same = batch.positions()[0];
        long ratcheted = batch.positions()[1];
        long stamp = columns().timestampFor(same);

        Offered answer = receive(same, stamp);
        assertTrue(answer.mapOnly());
        assertFalse(answer.resync());
        assertEquals(stamp, columns().timestampFor(same));
        assertEquals(SATISFIED, columns().classify(same));
        assertFalse(manager.isMapOwed(same));
        assertEquals(1, manager.getMapFillDelivered());

        long own = columns().timestampFor(ratcheted);
        assertTrue(columns().ratchetStamp(ratcheted, own + 500), "premise: a validation moved the held stamp");
        Offered below = receive(ratcheted, own);
        assertTrue(below.mapOnly());
        assertEquals(own + 500, columns().timestampFor(ratcheted), "the held stamp is not moved backwards");
        assertFalse(manager.isMapOwed(ratcheted));
    }

    /** A newer stamp: the server changed the column or stamped it again (it serves a chunk it
     *  has loaded, and every read on a server without a store, with the time of the serve),
     *  and the client cannot tell which. Terrain for everyone, with its new stamp, so one
     *  send settles both: given to the map alone under the old stamp, the server would send
     *  it a second time at the next join. It still counts for the fill. */
    @Test
    void anAnswerNewerThanTheColumnHeldIsTerrainForEveryoneAndCountsForTheFill() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        Offered answer = receive(position, stamp + 7777);
        assertFalse(answer.mapOnly());
        assertTrue(answer.resync(), "the consumers that hold the older column get the air-fill");
        assertEquals(stamp + 7777, columns().timestampFor(position), "the next validation finds nothing to send");
        assertFalse(manager.isMapOwed(position), "the map wrote it with them");
        assertEquals(1, manager.getMapFillDelivered(), "and the fill counts it");

        server.put(position, stamp + 7777);
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[48, 0]", lastNotice());
    }

    /** The same answer when the map lets it go: owed again and struck like any answer of the
     *  fill; the next one carries the stamp now held and goes to the map alone. */
    @Test
    void aNewerAnswerTheMapLetsGoComesBackAsADebt() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);
        mapDoesAt.put(position, MapDoes.LETS_GO);

        assertFalse(receive(position, stamp + 7777).mapOnly());
        assertTrue(manager.isMapOwed(position));
        assertEquals(1, manager.mapFillForTest().unwritten());
        mapDoesAt.clear();
        assertTrue(fire().has(position), "asked again");
        assertTrue(receive(position, stamp + 7777).mapOnly(), "the stamp held now");
        assertFalse(manager.isMapOwed(position));
    }

    @Test
    void anAnswerForAColumnTheServerAnnouncedAsChangedIsNewTerrainForEveryone() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        manager.onDirtyColumns(new long[]{position}); // the column changed while the request was on its way
        Offered answer = receive(position, stamp + 7);
        assertFalse(answer.mapOnly());
        assertTrue(answer.resync(), "the consumers that hold the old column get the air-fill");
        assertEquals(stamp + 7, columns().timestampFor(position));
        assertFalse(manager.isMapOwed(position), "and the map wrote it with them");
        assertEquals(1, manager.getMapFillDelivered(), "through the fill's request, so the fill counts it");
    }

    /** The stamp alone does not say that a column is the one held: a change in the same
     *  second as the stamp held (or below a stamp a validation moved forward) arrives with a
     *  stamp that is not newer. The server's notice that the column changed is what tells. */
    @Test
    void anAnswerForAChangedColumnIsForEveryoneAlsoWhenItsStampIsNotNewer() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        manager.onDirtyColumns(new long[]{position});
        Offered answer = receive(position, stamp);
        assertFalse(answer.mapOnly(), "the other consumers hold the column from before the change");
        assertTrue(answer.resync());
        assertFalse(manager.isMapOwed(position));
    }

    @Test
    void anAnswerThatArrivesWhileNoMapTakesItGoesTheNormalWayAndStaysOwed() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];

        mapWriting = false; // the server asked for no map ingestion in the meantime
        Offered answer = receive(position, columns().timestampFor(position));
        assertFalse(answer.mapOnly());
        assertTrue(manager.isMapOwed(position));
        assertEquals(0, manager.mapFillForTest().unwritten(),
                "no map was there to refuse it: not a strike, and no step towards 'the map takes nothing'");
        assertEquals(0, manager.mapFillForTest().pendingForTest());

        mapWriting = true;
        assertTrue(fire().has(position), "asked again when a map is written again");
        assertTrue(receive(position, columns().timestampFor(position)).mapOnly());
        assertFalse(manager.isMapOwed(position));
    }

    /** The server answers every declaration, and an answer does not say which one it answers.
     *  One "up to date" may belong to a question the scan asked earlier, so it is not yet
     *  read as "there is nothing to send"; the column that follows is still the fill's. */
    @Test
    void oneUpToDateIsNotBelievedAndTheColumnThatFollowsStillGoesToTheMap() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        manager.onColumnUpToDate(position);
        assertTrue(manager.isMapOwed(position), "the debt stands");
        assertEquals(SATISFIED, columns().classify(position), "and the held column is not put in doubt");
        Batch again = fire();
        assertEquals(-1L, again.timestamps()[again.indexOf(position)], "asked once more for the map");

        Offered answer = receive(position, stamp);
        assertTrue(answer.mapOnly());
        assertFalse(manager.isMapOwed(position));

        // And when the column arrives before the next scan has asked again.
        long other = again.positions()[again.indexOf(position) == 0 ? 1 : 0];
        manager.onColumnUpToDate(other);
        assertTrue(receive(other, columns().timestampFor(other)).mapOnly(), "still the fill's answer");
    }

    @Test
    void twoUpToDateAnswersDropTheDebtAndHaveTheHeldStampCheckedInsteadOfConfirmed() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        manager.onColumnUpToDate(position);
        fire();
        manager.onColumnUpToDate(position); // "nothing to send", again: the column is air now
        assertFalse(manager.isMapOwed(position));
        assertEquals(stamp, columns().timestampFor(position));
        assertEquals(stamp, columns().classify(position),
                "the answer was to 'I hold nothing': the held stamp is asked about next");
        Batch next = fire();
        assertEquals(stamp, next.timestamps()[next.indexOf(position)], "this time with the stamp the client holds");
    }

    /** While the scan has a question of its own open for a column (here: the server announced
     *  a change), "up to date" answers that question, also when the fill asked for the same
     *  column a moment ago. Two such answers are not "the server has nothing to send". */
    @Test
    void anUpToDateWhileTheScanHasAnOpenQuestionIsTheScans() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        manager.onDirtyColumns(new long[]{position});
        assertEquals(stamp, columns().classify(position), "premise: the scan has a question open");
        manager.onColumnUpToDate(position); // it crossed the notice: the question stays open
        assertTrue(manager.isMapOwed(position));
        Batch next = fire();
        assertEquals(stamp, next.timestamps()[next.indexOf(position)], "the scan asks about its stamp");
        manager.onColumnUpToDate(position);
        assertEquals(SATISFIED, columns().classify(position), "the scan's question is answered");
        assertTrue(manager.isMapOwed(position), "and the map is still owed the column");
        assertEquals(0, manager.mapFillForTest().withoutData());
    }

    /** The fill stopped declaring a column (here: no map takes columns for a while). A few
     *  seconds later nobody is waiting for an answer to that request any more: a column
     *  that arrives then is terrain for everyone. */
    @Test
    void anAnswerLongAfterTheFillStoppedAskingIsTerrainForEveryone() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        mapWriting = false;
        for (int i = 0; i < 2; i++) assertNull(fire());
        assertTrue(manager.mapFillForTest().isFillAsked(position),
                "two seconds on, an answer the server had already queued is still the fill's");
        for (int i = 0; i < 3; i++) assertNull(fire());
        mapWriting = true;
        assertFalse(manager.mapFillForTest().isFillAsked(position));
        Offered late = receive(position, stamp + 9);
        assertFalse(late.mapOnly());
        assertEquals(stamp + 9, columns().timestampFor(position));
        assertFalse(manager.isMapOwed(position), "the map wrote it with everyone");
    }

    @Test
    void aNotGeneratedAnswerDropsTheDebtAndKeepsTheColumn() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        manager.onColumnNotGenerated(position);
        assertFalse(manager.isMapOwed(position));
        assertEquals(stamp, columns().timestampFor(position));
        assertFalse(fire().has(position), "not asked again");
    }

    /** A column the scan itself just asked about is left alone for a while: the answers to
     *  the scan's declarations must be in before the fill asks its own question. */
    @Test
    void aColumnTheScanJustAskedAboutIsNotPickedUntilTheGraceHasPassed() {
        pressAfterADownloadWithoutAMap(3);
        long position = p(2, 2);
        long stamp = columns().timestampFor(position);
        manager.onDirtyColumns(new long[]{position}); // announced as changed: the scan asks about it

        Batch batch = fire();
        assertEquals(stamp, batch.timestamps()[batch.indexOf(position)], "premise: the scan's own question");
        long declaredAt = nanos;
        serve(batch); // "up to date" for it, and the other 47 go to the map
        assertEquals(SATISFIED, columns().classify(position), "premise: the scan is done with it again");
        assertTrue(manager.isMapOwed(position));

        long pickedAt = -1;
        for (int round = 0; round < 10 && pickedAt < 0; round++) {
            Batch next = fire();
            if (next != null && next.has(position)) pickedAt = nanos;
            serve(next);
        }
        assertTrue(pickedAt > 0, "it is asked for the map in the end");
        assertTrue(pickedAt - declaredAt >= MapFill.WALK_GRACE_NANOS,
                "but not within the grace: " + (pickedAt - declaredAt) / SECOND + " s");
        assertFalse(manager.isMapOwed(position));
        fillToTheEnd();
        assertEquals(List.of("lss.mapfill.finished[48, 0]"),
                notices.stream().filter(n -> n.startsWith("lss.mapfill.finished")).toList(),
                "the fill is not called finished while a column still waits for its turn");
    }

    // ---- the map and its deliveries ----

    /** A map that keeps dropping a column and asking for it again: the stamp stands (Voxy has
     *  the column), the debt returns, and after three tries the fill leaves it. */
    @Test
    void aColumnTheMapReportsKeepsItsStampAndIsTriedThreeTimes() {
        aColumnTheMapDoesNotTakeIsTriedThreeTimes(MapDoes.REPORTS);
        assertEquals(3, manager.mapFillForTest().failed());
    }

    /** The same for a map that lets the column go without a word. */
    @Test
    void aColumnTheMapLetsGoKeepsItsStampAndIsTriedThreeTimes() {
        aColumnTheMapDoesNotTakeIsTriedThreeTimes(MapDoes.LETS_GO);
        assertEquals(3, manager.mapFillForTest().unwritten());
    }

    private void aColumnTheMapDoesNotTakeIsTriedThreeTimes(MapDoes refusal) {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);
        mapDoesAt.put(position, refusal);

        for (int attempt = 1; attempt <= MapFill.MAX_FAILURES; attempt++) {
            if (attempt > 1) assertTrue(fire().has(position), "attempt " + attempt + " asks again");
            Offered answer = receive(position, stamp);
            assertTrue(answer.mapOnly());
            assertTrue(manager.isMapOwed(position), "the debt returns");
            assertEquals(stamp, columns().timestampFor(position),
                    "the column is not unstamped: every other consumer holds it");
            assertEquals(SATISFIED, columns().classify(position));
        }
        assertFalse(fire().has(position), "left for another session");

        fillToTheEnd();
        assertEquals("lss.mapfill.finished.partial[47, 1]", lastNotice(),
                "the 47 others are on the map; one could not be filled");
        assertTrue(manager.isMapOwed(position), "still owed: the next press tries again");
        assertFalse(manager.mapFillForTest().requested());

        // A new press tries it again, and this time the map takes it.
        mapDoesAt.clear();
        notices.clear();
        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[1, 0]", lastNotice(), "the count is this request's");
        assertFalse(manager.isMapOwed(position));
    }

    /** The fill is done when the map is done, not when the last answer arrived. */
    @Test
    void theFillIsNotFinishedWhileTheMapStillHoldsItsColumns() {
        pressAfterADownloadWithoutAMap(3);
        mapDoes = MapDoes.HOLDS;
        serve(fire());
        assertEquals(48, heldByMap.size(), "premise: every answer is in the map's queue");
        assertEquals(48, manager.mapFillForTest().pendingForTest());
        assertEquals(0, manager.getMapOwedColumns(), "taken as delivered meanwhile: not asked twice");
        for (int i = 0; i < 5; i++) assertNull(fire(), "nothing more to ask");
        assertFalse(finished(), "but not finished: the map has written none of them yet");
        assertTrue(manager.mapFillForTest().requested());

        // The map writes forty and lets eight go (its queue was cleared): those come back.
        mapDoes = MapDoes.WRITES;
        for (int i = 0; i < 48; i++) {
            if (i < 40) heldByMap.get(i).write();
            else heldByMap.get(i).letGo();
        }
        assertEquals(0, manager.mapFillForTest().pendingForTest());
        assertEquals(8, manager.getMapOwedColumns());
        mapAsks.clear();
        fillToTheEnd();
        assertEquals(8, mapAsks.size(), "the eight are asked again");
        assertEquals("lss.mapfill.finished[48, 0]", lastNotice());
        assertEquals(0, manager.getMapOwedColumns());
    }

    /** Receive is switched off while a fill's answer waits in the map's queue. */
    @Test
    void aCancelledMapOnlyDeliveryGivesTheDebtBackAndLeavesTheColumnAlone() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);

        mapDoes = MapDoes.HOLDS;
        Offered answer = receive(position, stamp);
        assertTrue(answer.mapOnly());
        assertFalse(manager.isMapOwed(position));
        manager.cancelDelivery(answer.delivery());
        assertTrue(manager.isMapOwed(position));
        assertEquals(stamp, columns().timestampFor(position));
        assertEquals(SATISFIED, columns().classify(position), "the held column was never in doubt");
        heldByMap.get(0).write(); // the map's late word about a cancelled delivery changes nothing
        assertTrue(manager.isMapOwed(position));
    }

    /** A second delivery of a column while the first is still on its way to the consumers:
     *  what is put back if both are cancelled is what was known before the first. */
    @Test
    void aSecondDeliveryWhileTheFirstIsPendingRemembersWhatWasKnownBeforeBoth() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge(); // no map: everything is owed
        long position = p(1, 1);
        long stamp = columns().timestampFor(position);
        mapWriting = true;
        mapDoes = MapDoes.HOLDS;

        Offered first = receive(position, stamp + 1); // new content, for everyone
        assertFalse(first.mapOnly(), "nobody asked for it for the map: it is terrain for everyone");
        assertEquals(ColumnStateMap.MAP_OWED, first.delivery().mapStateBefore);
        assertFalse(manager.isMapOwed(position), "premise: on its way to the map");
        Offered second = receive(position, stamp + 2);
        assertEquals(ColumnStateMap.MAP_OWED, second.delivery().mapStateBefore,
                "not the first delivery's guess");

        manager.retireAcquisition(); // Receive switched off: both are cancelled
        assertEquals(stamp, columns().timestampFor(position), "premise: the stamp before both");
        assertTrue(columns().isMapOwed(position));
    }

    /** Only the latest delivery of a column says what the map has: an older one that the map
     *  finishes late describes a column the client no longer holds. */
    @Test
    void anOlderDeliverysWordDoesNotSettleTheNewerOne() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge();
        long position = p(1, 1);
        long stamp = columns().timestampFor(position);
        mapWriting = true;
        mapDoes = MapDoes.HOLDS;
        receive(position, stamp + 1);
        receive(position, stamp + 2);
        assertEquals(2, heldByMap.size());

        heldByMap.get(1).letGo(); // the newer one is let go
        assertTrue(manager.isMapOwed(position));
        heldByMap.get(0).write(); // the older one is written after all
        assertTrue(manager.isMapOwed(position), "the map has the older column, not the one held");

        // And the other way round: the newer one is written, the older one let go later.
        long other = p(2, 2);
        heldByMap.clear();
        receive(other, columns().timestampFor(other) + 1);
        receive(other, columns().timestampFor(other) + 1);
        heldByMap.get(1).write();
        assertFalse(manager.isMapOwed(other));
        heldByMap.get(0).letGo();
        assertFalse(manager.isMapOwed(other), "the map has the column held");
    }

    /** A fill's answer never overtakes an earlier delivery of the same column that has not
     *  reached the consumers yet: it then goes the way of every column. */
    @Test
    void aFillAnswerNeverOvertakesAnEarlierDeliveryOfTheSameColumn() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        long stamp = columns().timestampFor(position);
        var earlier = manager.trackDelivery(dim, position, stamp); // still in the decode queue
        assertTrue(manager.mapFillForTest().isFillAnswer(position, stamp), "premise: otherwise the fill's answer");

        Offered answer = receive(position, stamp);
        assertFalse(answer.mapOnly());
        earlier.complete();
    }

    /** A straggler from another dimension at a position the fill asked for is not its answer. */
    @Test
    void aColumnOfAnotherDimensionIsNeverAFillAnswer() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        var elsewhere = ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:elsewhere"));
        int before = offered.size();
        // With the stamp held here, so that nothing but the dimension tells it apart.
        LSSClientNetworking.handleVoxelColumn(manager, processor, new VoxelColumnS2CPayload(
                PositionUtil.unpackX(position), PositionUtil.unpackZ(position), elsewhere,
                columns().timestampFor(position), CONTENT));
        assertTrue(offered.size() > before, "premise: handed on for the drain's own dimension filter");
        assertFalse(offered.get(offered.size() - 1).mapOnly());
        assertTrue(manager.isMapOwed(position), "the debt here is untouched");
        assertTrue(manager.mapFillForTest().isFillAsked(position), "and the request for it still stands");
    }

    /** JourneyMap replays its last renders into another map directory and reports the ones it
     *  no longer holds with no delivery named. For a column that went to the map alone that
     *  must not unstamp what every other consumer holds. Only a map backend's report is
     *  read that way: another consumer's report without a handle is an ingest failure, or
     *  its hole would keep a false stamp. */
    @Test
    void aMapsReportThatNamesNoDeliveryKeepsTheStampOfAColumnThatWentToTheMapAlone() {
        pressAfterADownloadWithoutAMap(3);
        Batch batch = fire();
        long filled = batch.positions()[0];
        long other = batch.positions()[1];
        long stamp = columns().timestampFor(filled);
        assertTrue(receive(filled, stamp).mapOnly());
        assertTrue(receive(other, columns().timestampFor(other)).mapOnly());
        assertFalse(manager.isMapOwed(filled));

        manager.onMapDrop(dim, filled);
        assertEquals(stamp, columns().timestampFor(filled), "the stamp stands");
        assertTrue(manager.isMapOwed(filled), "the debt returns");

        // Voxy, or another mod's reader, reporting the same way: its own copy is what failed.
        manager.onIngestFailure(dim, other);
        assertEquals(-1L, columns().timestampFor(other), "an ingest failure, as always");

        // And a map's report for a column that arrived for everyone is an ingest failure too.
        long normal = p(3, 3) == filled || p(3, 3) == other ? p(-3, -3) : p(3, 3);
        manager.onDirtyColumns(new long[]{normal}); // announced as changed: its answer is new terrain
        assertFalse(receive(normal, columns().timestampFor(normal) + 1).mapOnly(), "premise");
        manager.onMapDrop(dim, normal);
        assertEquals(-1L, columns().timestampFor(normal));
    }

    // ---- what is on its way is never saved as "on the map" ----

    private void rejoinAt(String address, String bucket, int lod) {
        start(lod, LSSConstants.PROTOCOL_VERSION, false, address);
        manager.setPendingStateLoadForTest(CompletableFuture.completedFuture(
                ColumnCacheStore.loadStateAsync(bucket, dim).join()));
        assertTrue(manager.tickCacheGatePhase());
    }

    /** The player asks for a fill, the answers sit in the map's queue, and the player flies
     *  on: the columns leave memory before the map is done with them. What the map then
     *  lets go must still be owed in the record; what it writes must be settled there.
     *  Through the real files. */
    @Test
    void aColumnThatLeftMemoryWhileInTheMapsQueueIsOwedUnlessTheMapWroteIt() {
        final String address = "map-fill-pruned";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge(); // no map: 48 owed
            manager.saveCache();

            rejoinAt(address, bucket, 3);
            mapWriting = true;
            converge(); // the scan validates the stamps
            assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
            assertNull(fire());
            assertNull(fire());
            mapDoes = MapDoes.HOLDS;
            serve(fire());
            assertEquals(48, heldByMap.size(), "premise: every answer is in the map's queue");

            int far = 4 * manager.scannerForTest().getPruneDistance();
            manager.tickMovementPhase(far, 0); // the player teleports away
            assertEquals(0, columns().size(), "premise: the columns left memory");
            long written = heldByMap.get(0).delivery().packed;
            long letGo = heldByMap.get(1).delivery().packed;
            heldByMap.get(0).write();
            for (int i = 1; i < 48; i++) heldByMap.get(i).letGo();
            px = far;
            // The player plays on where they are now: the scan runs, and the fill decides
            // whether it is done. It is done here, and it stands for what the map let go.
            mapDoes = MapDoes.WRITES;
            converge();
            for (int i = 0; i < 3; i++) serve(fire());
            assertEquals("lss.mapfill.done_here[1]", lastNotice());
            assertTrue(manager.mapFillForTest().requested(), "the debt left memory in the map's queue");
            manager.saveCache();

            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            var map = new ColumnStateMap();
            map.adoptLoaded(loaded);
            assertFalse(map.isMapOwed(written), "the one the map wrote is settled");
            assertTrue(map.isMapOwed(letGo), "the ones it let go are still owed");
            assertEquals(47, map.mapOwedCount());
            assertTrue(loaded.mapFillRequested, "and the request stands for them");
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** Whether a fill is done here is not decided while any column is still in a map's queue,
     *  the fill's or not: an ordinary column the map then lets go is a debt the fill pays. */
    @Test
    void theFillIsNotDoneWhileTheMapStillHoldsAnOrdinaryColumn() {
        pressAfterADownloadWithoutAMap(3);
        long changed = p(2, 2);
        manager.onDirtyColumns(new long[]{changed}); // the server says this column changed
        mapDoesAt.put(changed, MapDoes.HOLDS);
        assertFalse(receive(changed, columns().timestampFor(changed) + 1).mapOnly(), "premise: terrain for everyone");
        assertEquals(1, heldByMap.size());

        for (int i = 0; i < 12; i++) serve(fire());
        assertEquals(0, manager.getMapOwedColumns(), "premise: the fill has paid everything else");
        assertFalse(finished(), "the map still holds a column: nothing is said yet");
        assertTrue(manager.mapFillForTest().requested());

        mapDoesAt.clear();
        heldByMap.get(0).letGo(); // the map gives it up
        assertTrue(manager.isMapOwed(changed));
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[48, 0]", lastNotice(), "and the fill paid that one too");
    }

    /** The button has no confirm screen, so it gets pressed twice. While the map holds the
     *  fill's answers nothing is owed in range, and a fresh start would find nothing to ask
     *  and end the request under them. A second press changes nothing. */
    @Test
    void aSecondPressWhileTheMapHoldsTheAnswersChangesNothing() {
        pressAfterADownloadWithoutAMap(3);
        mapDoes = MapDoes.HOLDS;
        serve(fire());
        assertEquals(48, heldByMap.size(), "premise: every answer is in the map's queue");
        assertEquals(0, manager.getMapOwedColumns());

        MapFillRequest again = manager.requestMapFill();
        assertEquals(MapFillRequest.Outcome.RUNNING, again.outcome());
        assertEquals(48, again.columns(), "what is still to go");
        assertTrue(manager.mapFillForTest().requested());

        mapDoes = MapDoes.WRITES;
        heldByMap.get(0).letGo(); // one comes back as a debt
        for (int i = 1; i < 48; i++) heldByMap.get(i).write();
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[48, 0]", lastNotice(), "the first press's fill runs to its end");

        // And a press while it is still asking from here does not restart the count either.
        pressAfterADownloadWithoutAMap(9);
        Batch batch = fire();
        int half = batch.positions().length / 2;
        serve(new Batch(Arrays.copyOf(batch.positions(), half), Arrays.copyOf(batch.timestamps(), half)));
        assertTrue(manager.getMapFillDelivered() > 0 && manager.getMapOwedColumns() > 0, "premise: part-way");
        assertEquals(MapFillRequest.Outcome.RUNNING, manager.requestMapFill().outcome());
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[360, 0]", lastNotice());
    }

    /** The session ends while columns sit in the map's queue: the record keeps what was known
     *  about them before (here: owed), never "on the map". Through the real files. */
    @Test
    void aColumnStillInTheMapsQueueWhenTheSessionEndsIsSavedAsItWas() {
        final String address = "map-fill-queued-at-end";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge(); // no map: 48 owed
            manager.saveCache();

            rejoinAt(address, bucket, 3);
            mapWriting = true;
            converge();
            assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
            assertNull(fire());
            assertNull(fire());
            mapDoes = MapDoes.HOLDS;
            serve(fire());
            assertEquals(48, heldByMap.size(), "premise: every answer is in the map's queue");
            assertEquals(0, manager.getMapOwedColumns(), "premise: on their way, so not asked for twice");

            manager.retireAcquisition(); // the session ends
            manager.saveCache();

            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            var map = new ColumnStateMap();
            map.adoptLoaded(loaded);
            assertEquals(48, map.mapOwedCount(), "nothing was written: everything is still owed");
            assertEquals(0, map.mapUnknownCount());
            assertTrue(loaded.mapFillRequested, "and the fill goes on next time");
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** Without any fill: a column the record says is owed is sent again as terrain (it changed
     *  on the server), the player moves on while it is in the map's queue, and the map writes
     *  it. The session holds nothing at its end, and the record still learns that this debt
     *  is paid. Through the real files. */
    @Test
    void aDebtPaidAfterThePlayerMovedOnReachesTheRecordWithoutAnyFill() {
        final String address = "map-fill-paid-after-prune";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge(); // no map: 48 owed
            manager.saveCache();

            rejoinAt(address, bucket, 3);
            mapWriting = true;
            converge();
            long position = p(2, 2);
            mapDoes = MapDoes.HOLDS;
            manager.onDirtyColumns(new long[]{position});
            assertFalse(receive(position, columns().timestampFor(position) + 5).mapOnly(), "premise: terrain");
            assertEquals(1, heldByMap.size());

            int far = 4 * manager.scannerForTest().getPruneDistance();
            manager.tickMovementPhase(far, 0);
            assertEquals(0, columns().size(), "premise: the player moved on and nothing is held");
            heldByMap.get(0).write();
            px = far;
            manager.saveCache();

            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            var map = new ColumnStateMap();
            map.adoptLoaded(loaded);
            assertFalse(map.isMapOwed(position), "the map wrote it");
            assertEquals(47, map.mapOwedCount(), "and the rest is as it was");
            assertFalse(loaded.mapFillRequested, "nobody asked for a fill");
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** A delivery ends on the decode thread and its end is queued for the client thread. If
     *  the session ends first, the end is applied then: what the map did is a fact. */
    @Test
    void aDeliveryWhoseEndIsStillQueuedWhenTheSessionEndsIsSettledThen() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge(); // no map: everything is owed
        long letGo = p(1, 1);
        long written = p(2, 2);
        mapWriting = true;
        mapDoesAt.put(letGo, MapDoes.LETS_GO);
        manager.onDirtyColumns(new long[]{letGo, written});

        queuedEvents = new ArrayList<>();
        receive(letGo, columns().timestampFor(letGo) + 1);
        receive(written, columns().timestampFor(written) + 1);
        assertFalse(manager.isMapOwed(letGo), "premise: on its way, the end not applied yet");
        assertEquals(2, queuedEvents.size(), "premise: both ends are queued");

        manager.retireAcquisition(); // the session ends before the queue runs
        assertTrue(columns().isMapOwed(letGo), "the map did not write it");
        assertFalse(columns().isMapOwed(written));
        assertEquals(ColumnStateMap.MAP_SETTLED, columns().mapState(written));
        queuedEvents.forEach(Runnable::run); // and running the queue afterwards changes nothing
        assertTrue(columns().isMapOwed(letGo));
        assertTrue(settledForTheRecord(written), "the written one is a fact for the record");
    }

    /** With cave layers a column is several tiles: the surface can be on the map while a cave
     *  tile of it is dropped and reported. The surface is what the debt is about. */
    @Test
    void aFillAnswerWhoseSurfaceIsOnTheMapIsNotFailedByALaterReport() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        mapDoesAt.put(position, MapDoes.WRITES_THEN_REPORTS);
        assertTrue(receive(position, columns().timestampFor(position)).mapOnly());
        assertFalse(manager.isMapOwed(position));
        assertEquals(0, manager.mapFillForTest().failed());
        assertEquals(1, manager.getMapFillDelivered());
        assertFalse(fire().has(position), "not asked again");
    }

    /** The same column still in the map's queue for its cave tile when Receive is switched
     *  off: the surface is on the map, so the debt does not come back. */
    @Test
    void aCancelledFillAnswerTheMapAlreadyWroteStaysSettled() {
        pressAfterADownloadWithoutAMap(3);
        long position = fire().positions()[0];
        mapDoes = MapDoes.HOLDS;
        Offered answer = receive(position, columns().timestampFor(position));
        answer.delivery().written(); // the surface; the lease is for the cave tile
        manager.cancelDelivery(answer.delivery());
        assertFalse(manager.isMapOwed(position));
        assertTrue(settledForTheRecord(position), "and the record is told: not merely 'on its way'");
    }

    // ---- the view ----

    /** Columns inside the view are never asked for (the map mod draws real chunks itself),
     *  but nothing here proves that it did: their debt stays, and they are filled when the
     *  player has moved on. */
    @Test
    void columnsInsideTheViewKeepTheirDebtAndAreFilledWhenThePlayerMovesOn() {
        converge(); // lod 9, no view yet: all 360 are owed
        mapWriting = true;
        view = 2;
        fire(); // the announcement, with the view known
        var inView = new LongOpenHashSet();
        columns().forEachPresentForTest((position, stamp) -> {
            if (SpiralScanner.isVanillaRendered(PositionUtil.unpackX(position), PositionUtil.unpackZ(position),
                    0, 0, 2)) {
                inView.add(position);
            }
        });
        assertTrue(inView.size() >= 8, "premise: some held columns are inside the view: " + inView.size());

        MapFillRequest request = manager.requestMapFill();
        assertEquals(360 - inView.size(), request.columns(), "what can be asked for from here");
        fillToTheEnd();
        assertEquals("lss.mapfill.done_here[" + (360 - inView.size()) + "]", lastNotice(),
                "the line says that the fill goes on");
        assertTrue(mapAsks.longStream().noneMatch(inView::contains), "none of them was asked for");
        assertEquals(inView.size(), manager.getMapOwedColumns(), "their debt stands");
        assertTrue(manager.mapFillForTest().requested(), "and so does the request");

        int noticesBefore = notices.size();
        px = 6; // the player walks on: those columns leave the view
        mapAsks.clear();
        for (int round = 0; round < 40 && manager.getMapOwedColumns() > 0; round++) serve(fire());
        assertEquals(0, manager.getMapOwedColumns(), "they are paid without another press");
        assertTrue(new LongOpenHashSet(mapAsks).containsAll(inView), "they were asked for after all");
        long stillOwedOfThem = inView.longStream().filter(columns()::isMapOwed).count();
        assertEquals(0, stillOwedOfThem);
        for (int i = 0; i < 3; i++) serve(fire());
        assertFalse(manager.mapFillForTest().requested(), "and then the request has ended");
        assertEquals(noticesBefore, notices.size(), "and the player is not told a second time");
    }

    /** Columns the map refused do not end a request while columns inside the view still wait:
     *  those are filled when the player moves on, and then the request ends. */
    @Test
    void refusedColumnsDoNotEndARequestThatStillWaitsForColumnsInView() {
        converge(); // lod 9, no view yet: all 360 are owed
        mapWriting = true;
        view = 2;
        fire();
        long inView = columns().size() - manager.mapFillForTest().owedInRange(0, 0, 2, 9);
        assertTrue(inView >= 8, "premise: " + inView);
        for (long refused : new long[]{p(5, 5), p(6, 5), p(7, 5)}) mapDoesAt.put(refused, MapDoes.LETS_GO);

        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        fillToTheEnd();
        assertEquals("lss.mapfill.finished.partial[" + (360 - inView - 3) + ", 3]", lastNotice());
        assertTrue(manager.mapFillForTest().requested(), "the columns inside the view still wait");

        px = 6; // the player walks on
        for (int round = 0; round < 40 && manager.getMapOwedColumns() > 3; round++) serve(fire());
        assertEquals(3, manager.getMapOwedColumns(), "they are paid; the three the map refused are left");
        for (int i = 0; i < 3; i++) serve(fire());
        assertFalse(manager.mapFillForTest().requested(), "what is left here the map refused: the request ends");
    }

    // ---- across sessions ----

    /** Session one downloads without a map and the player asks for a fill just before
     *  leaving; session two finds the request in the record, validates what it holds, and
     *  fills; session three finds nothing owed and nothing asked for. Through the real files. */
    @Test
    void aFillThatWasAskedForGoesOnInTheNextSessionAndEndsThere() {
        final String address = "map-fill-roundtrip";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge();
            mapWriting = true;
            assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
            manager.saveCache();

            // Session two.
            start(3, LSSConstants.PROTOCOL_VERSION, false, address);
            manager.setPendingStateLoadForTest(CompletableFuture.completedFuture(
                    ColumnCacheStore.loadStateAsync(bucket, dim).join()));
            assertTrue(manager.tickCacheGatePhase());
            assertEquals(48, manager.getMapOwedColumns(), "the debt came back from the record");
            assertTrue(manager.mapFillForTest().requested(), "and so did the request");
            mapWriting = true;

            Batch first = fire();
            assertEquals(List.of("lss.mapfill.resumed[48]"), notices);
            assertEquals(48, first.positions().length);
            assertTrue(Arrays.stream(first.timestamps()).allMatch(ts -> ts > 0),
                    "first the scan asks about the stamps it holds: nothing is picked before that");
            serve(first);
            mapAsks.clear();
            fillToTheEnd();
            assertEquals(48, mapAsks.size());
            assertEquals("lss.mapfill.finished[48, 0]", lastNotice());
            manager.saveCache();

            // Session three.
            start(3, LSSConstants.PROTOCOL_VERSION, false, address);
            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            assertFalse(loaded.mapFillRequested);
            manager.setPendingStateLoadForTest(CompletableFuture.completedFuture(loaded));
            assertTrue(manager.tickCacheGatePhase());
            assertEquals(48, columns().size(), "the cache still holds the columns");
            assertEquals(0, manager.getMapOwedColumns());
            assertEquals(0, columns().mapUnknownCount());
            mapWriting = true;
            run(8);
            assertTrue(notices.isEmpty());
            assertTrue(mapAsks.isEmpty());
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    private void rejoin(String address, String bucket) {
        start(9, LSSConstants.PROTOCOL_VERSION, false, address);
        manager.setPendingStateLoadForTest(CompletableFuture.completedFuture(
                ColumnCacheStore.loadStateAsync(bucket, dim).join()));
        assertTrue(manager.tickCacheGatePhase());
    }

    private long hints() {
        return notices.stream().filter(n -> n.startsWith("lss.mapfill.owed[")).count();
    }

    /** The line about owed columns is said once per debt, not at every login: a player who
     *  chose not to fill is not reminded for ever. New terrain that arrives while no map is
     *  written is new debt, and is worth the line again. Through the real files. */
    @Test
    void thePlayerIsToldOncePerDebtNotOncePerSession() {
        final String address = "map-fill-hint";
        start(9, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge(); // no map: 360 owed
            manager.saveCache();

            rejoin(address, bucket); // a map is written now: told
            mapWriting = true;
            converge();
            assertEquals(1, hints());
            manager.saveCache();

            rejoin(address, bucket); // still not filled: not told again
            mapWriting = true;
            converge();
            assertEquals(0, hints());
            assertEquals(360, manager.getMapOwedColumns(), "premise: the debt is still there");
            mapWriting = false; // the map is switched off, and the player walks on
            px = 12;
            converge();
            assertTrue(manager.getMapOwedColumns() > 360, "premise: new terrain, new debt");
            manager.saveCache();

            rejoin(address, bucket); // the map is back: told about it once more
            px = 12;
            mapWriting = true;
            converge();
            assertEquals(1, hints());
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** A map that reads as "being written" and takes nothing (Xaero with "Load New Chunks"
     *  off refuses every tile of an area it has not mapped): the request stops after a
     *  quarter of a region, not after three tries for every column of the disc. */
    @Test
    void aFillStopsWhenTheMapTakesNoneOfItsAnswers() {
        pressAfterADownloadWithoutAMap(24); // 2,400 owed
        ClientCommandActions.setMenuButtonsForTest(false); // an options screen without buttons
        mapDoes = MapDoes.LETS_GO;
        int budget = 0;
        for (int i = 0; i < 30 && manager.mapFillForTest().requested(); i++) {
            Batch batch = fire();
            budget = Math.max(budget, manager.scannerForTest().getLastBudget());
            serve(batch);
        }
        assertFalse(manager.mapFillForTest().requested(), "the request stopped");
        assertNull(fire(), "and asks for nothing more");
        assertTrue(mapAsks.size() >= MapFill.STALL_LIMIT && mapAsks.size() <= MapFill.STALL_LIMIT + 2 * budget,
                "asked " + mapAsks.size() + " with a budget of " + budget + ": not three times the disc");
        assertTrue(lastNotice().startsWith("lss.mapfill.stalled.command["), String.valueOf(notices));
        assertTrue(lastNotice().endsWith(", " + dev.vox.lss.common.Brand.clientCommand() + "]"), "it names the command");
        assertFalse(finished(), "it is not called finished");
        assertEquals(2400, manager.getMapOwedColumns(), "the debt stands");
        assertTrue(offered.stream().filter(o -> o.delivery() != null && o.mapOnly()).count() == mapAsks.size(),
                "premise: every answer went to the map alone, also the ones that came after the stop");

        // The map can take columns again, and the player presses again.
        mapDoes = MapDoes.WRITES;
        notices.clear();
        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[2400, 0]", lastNotice());
    }

    /** A map that takes every other column: the columns it refused are asked again only when
     *  no untried column is left, so every column it takes reaches it first. Then what is
     *  left is being refused, and the request stops after a quarter of a region of that
     *  instead of trying each refused column three times. */
    @Test
    void aMapThatRefusesHalfGetsTheOtherHalfBeforeTheRequestStops() {
        pressAfterADownloadWithoutAMap(24); // 2,400 owed
        columns().forEachPresentForTest((position, stamp) -> {
            if ((PositionUtil.unpackX(position) & 1) == 0) mapDoesAt.put(position, MapDoes.LETS_GO);
        });
        int refused = mapDoesAt.size();
        int takers = 2400 - refused;
        int budget = 0;
        for (int i = 0; i < 60 && manager.mapFillForTest().requested(); i++) {
            Batch batch = fire();
            budget = Math.max(budget, manager.scannerForTest().getLastBudget());
            serve(batch);
        }
        assertFalse(manager.mapFillForTest().requested(), "the request stopped");
        fire();
        assertEquals("lss.mapfill.stalled[" + MapFill.STALL_LIMIT + ", " + takers + ", "
                + dev.vox.lss.common.Brand.clientCommand() + "]", lastNotice());
        assertEquals(takers, manager.getMapFillDelivered(), "every column the map takes is on it");
        assertTrue(budget < 900, "premise: a batch is small beside the refused half: " + budget);
        assertTrue(mapAsks.size() <= 2400 + MapFill.STALL_LIMIT + budget,
                "asked " + mapAsks.size() + ": the refused half was not tried three times");
        assertTrue(mapAsks.size() < 2400 + refused, "not even once more each: " + mapAsks.size());
        assertFalse(finished());
    }

    /** A few columns the map keeps refusing do not stop a request: each is tried three
     *  times, after everything else, and the line says how many could not be added. */
    @Test
    void aFewRefusedColumnsAreTriedThreeTimesAndTheRequestRunsToItsEnd() {
        pressAfterADownloadWithoutAMap(24);
        int[] count = {0};
        columns().forEachPresentForTest((position, stamp) -> {
            if (count[0] < 40 && (PositionUtil.unpackX(position) % 7) == 0) {
                mapDoesAt.put(position, MapDoes.LETS_GO);
                count[0]++;
            }
        });
        assertEquals(40, mapDoesAt.size(), "premise");
        fillToTheEnd();
        assertEquals("lss.mapfill.finished.partial[2360, 40]", lastNotice());
        assertEquals(40L * MapFill.MAX_FAILURES, manager.mapFillForTest().unwritten());
        assertEquals(2400 + 40 * (MapFill.MAX_FAILURES - 1), mapAsks.size());
        assertFalse(manager.mapFillForTest().requested(), "what is left here the map refused: the request ends");
    }

    /** One press is "fill my map": the request stands while this cache owes the map terrain
     *  the fill cannot ask for from here, that terrain is filled with no other press when
     *  the player gets there, and the request ends when nothing is owed any more. */
    @Test
    void aRequestStandsUntilTheCacheOwesTheMapNothing() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge(); // no map: the 48 columns around the origin are owed
        px = 4;     // the player walks on, still without a map
        converge();
        int owed = manager.getMapOwedColumns();
        mapWriting = true;
        fire();
        notices.clear();

        MapFillRequest request = manager.requestMapFill();
        assertEquals(MapFillRequest.Outcome.STARTED, request.outcome());
        int behind = owed - request.columns();
        assertTrue(behind >= 20, "premise: owed columns are held that are out of reach from here: " + behind);
        fillToTheEnd();
        assertEquals("lss.mapfill.done_here[" + request.columns() + "]", lastNotice());
        assertEquals(behind, manager.getMapOwedColumns());
        assertFalse(manager.mapFillForTest().working(), "nothing more to do here");
        assertTrue(manager.mapFillForTest().requested(), "but the request stands for the rest");

        int noticesBefore = notices.size();
        px = 0; // the player walks back
        mapAsks.clear();
        for (int round = 0; round < 40 && manager.getMapOwedColumns() > 0; round++) serve(fire());
        assertEquals(0, manager.getMapOwedColumns(), "the rest is filled without another press");
        assertEquals(behind, mapAsks.size(), "each of them asked for once");
        for (int i = 0; i < 3; i++) serve(fire());
        assertFalse(manager.mapFillForTest().requested(), "and with nothing left to owe, the request has ended");
        assertEquals(noticesBefore, notices.size());
    }

    /** A press that finds nothing to ask from here is answered once and says nothing more,
     *  also while the request stands for terrain elsewhere: no "finished: 0 columns". */
    @Test
    void aPressThatFindsNothingHereSaysNothingMoreWhileTheRequestStands() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        converge(); // no map: 48 owed around the origin
        mapWriting = true;
        int far = 4 * manager.scannerForTest().getPruneDistance();
        manager.tickMovementPhase(far, 0); // the player is far from all of it
        px = far;
        assertEquals(MapFillRequest.Outcome.NOTHING_HERE, manager.requestMapFill().outcome());
        converge(); // the terrain here is downloaded, and written to the map
        for (int i = 0; i < 4; i++) serve(fire());
        assertTrue(notices.isEmpty(), "nothing more is said: " + notices);
        assertTrue(manager.mapFillForTest().requested(), "and the request stands for the debt that left memory");
        assertFalse(manager.mapFillForTest().working());
    }

    /** A press is a fact for the record also when the session holds no column when it ends
     *  (the player pressed far from everything this cache knows): the next session that
     *  plays where the debt is goes on with the fill. Through the real files. */
    @Test
    void aPressWhereNothingIsHeldStillReachesTheRecord() {
        final String address = "map-fill-press-far";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge(); // no map: 48 owed
            manager.saveCache();

            rejoinAt(address, bucket, 3);
            mapWriting = true;
            int far = 4 * manager.scannerForTest().getPruneDistance();
            manager.tickMovementPhase(far, 0); // far from all of it
            px = far;
            assertEquals(0, columns().size(), "premise: nothing is held here");
            assertEquals(MapFillRequest.Outcome.NOTHING_HERE, manager.requestMapFill().outcome());
            assertTrue(manager.mapFillForTest().requested(), "the debt that left memory is still this cache's");
            manager.saveCache(); // the session ends holding no column

            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            assertTrue(loaded.mapFillRequested, "the press reached the record");
            var map = new ColumnStateMap();
            map.adoptLoaded(loaded);
            assertEquals(48, map.mapOwedCount());
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** A request stands across sessions until the cache owes the map nothing, which on a
     *  large cache is a long download. The player can take it back without clearing the
     *  cache: nothing more is asked for the map, what the map is missing stays recorded,
     *  and a later press fills it. */
    @Test
    void aStoppedRequestAsksNothingMoreAndALaterPressFillsTheRest() {
        pressAfterADownloadWithoutAMap(9);
        Batch batch = fire();
        int half = batch.positions().length / 2;
        serve(new Batch(Arrays.copyOf(batch.positions(), half), Arrays.copyOf(batch.timestamps(), half)));
        long paid = manager.getMapFillDelivered();
        int left = manager.getMapOwedColumns();
        assertTrue(paid > 0 && left > 0, "premise: part-way");
        mapDoes = MapDoes.WRITES;

        var said = new java.util.ArrayList<String>();
        ClientCommandActions.stopFillMap(line -> said.add(key(line)), manager);
        assertEquals(List.of("lss.mapfill.stopped"), said);
        assertFalse(manager.mapFillForTest().requested());
        int asked = mapAsks.size();
        int noticesBefore = notices.size();

        // The answers that were already on their way are still the map's.
        long stampOfOne = columns().timestampFor(batch.positions()[half]);
        Offered late = receive(batch.positions()[half], stampOfOne);
        assertTrue(late.mapOnly(), "asked before the stop: it goes to the map alone");
        assertEquals(stampOfOne, columns().timestampFor(batch.positions()[half]), "and the held column is untouched");
        left--;
        assertEquals(left, manager.getMapOwedColumns(), "and the map having written it settles that debt");

        for (int i = 0; i < 6; i++) serve(fire());
        assertEquals(asked, mapAsks.size(), "nothing more is asked for the map");
        assertEquals(noticesBefore, notices.size(), "and nothing is said: no 'finished', no 'your map is missing terrain'");
        assertEquals(left, manager.getMapOwedColumns(), "what the map is missing stays recorded");
        assertEquals(new ColumnStateMap.MapDebt(false, false, true, true), manager.mapFillForTest().forSave(),
                "the record is told that no request stands, and not to make the rest of the file owed");

        ClientCommandActions.stopFillMap(line -> said.add(key(line)), manager);
        assertEquals("lss.mapfill.not_running", said.get(1), "a second stop has nothing to stop");
        ClientCommandActions.stopFillMap(line -> said.add(key(line)), null);
        assertEquals("lss.mapfill.not_running", said.get(2), "nor has a stop outside a session");

        assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
        assertFalse(manager.mapFillForTest().stopped(), "a press after a stop: the stop is over");
        fillToTheEnd();
        assertEquals(0, manager.getMapOwedColumns());
        assertEquals(360, manager.getMapFillDelivered(), "the later press paid the rest");
    }

    /** A stop with nothing to stop changes nothing. A fill that ended by itself (the map took
     *  none of its answers) says "Map fill stopped: …"; a player who then types the stop
     *  command must not undo what the press had decided for the save. */
    @Test
    void aStopWithNothingToStopChangesNothing() {
        pressAfterADownloadWithoutAMap(24);
        mapDoes = MapDoes.LETS_GO;
        for (int i = 0; i < 40 && manager.mapFillForTest().requested(); i++) serve(fire());
        assertFalse(manager.mapFillForTest().requested(), "premise: the fill ended by itself");
        var before = manager.mapFillForTest().forSave();
        assertTrue(before.fileUnknownBecomeOwed(), "premise: the press stands for the save");

        assertFalse(manager.stopMapFill());
        assertEquals(before, manager.mapFillForTest().forSave());
        assertFalse(manager.mapFillForTest().stopped());
    }

    /** Right after a join the cache is still loading, and whether its record holds a request
     *  is not known yet. A stop typed then is not lost: the request the record turns out to
     *  hold does not start, and the record hears of it. A stop is this dimension's. */
    @Test
    void aStopWhileTheCacheIsStillLoadingHoldsForTheRequestInTheRecord() {
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        mapWriting = true;
        assertTrue(manager.stopMapFill(), "whatever the record holds is taken back");
        manager.mapFillForTest().onCacheLoaded(true, true); // the record held a request
        assertFalse(manager.mapFillForTest().requested(), "it does not go on");
        assertEquals(new ColumnStateMap.MapDebt(false, false, true, true), manager.mapFillForTest().forSave(),
                "and the record is told");

        manager.mapFillForTest().reset(); // the player changes dimension
        assertFalse(manager.mapFillForTest().stopped(), "the next dimension knows nothing of that stop");

        // With no request in the record there is nothing for the record to hear.
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        mapWriting = true;
        assertTrue(manager.stopMapFill());
        manager.mapFillForTest().onCacheLoaded(false, false);
        assertFalse(manager.mapFillForTest().stopped());
        assertFalse(manager.mapFillForTest().requested());

        // And when the record cannot be read, the save says that no request stands.
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        mapWriting = true;
        assertTrue(manager.stopMapFill());
        manager.mapFillForTest().onCacheUnavailable();
        assertEquals(Boolean.FALSE, manager.mapFillForTest().forSave().fillRequested());
        assertTrue(manager.mapFillForTest().stopped());
    }

    /** A stop reaches the record also from a session that holds no column: a request left
     *  standing in the file would go on at the next join. Through the real files. */
    @Test
    void aStopWhereNothingIsHeldStillReachesTheRecord() {
        final String address = "map-fill-stop-far";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            converge(); // no map: 48 owed
            mapWriting = true;
            assertEquals(MapFillRequest.Outcome.STARTED, manager.requestMapFill().outcome());
            manager.saveCache();
            assertTrue(ColumnCacheStore.loadStateAsync(bucket, dim).join().mapFillRequested, "premise: the request is in the record");

            rejoinAt(address, bucket, 3);
            mapWriting = true;
            int far = 4 * manager.scannerForTest().getPruneDistance();
            manager.tickMovementPhase(far, 0); // far from all of it
            px = far;
            assertEquals(0, columns().size(), "premise: nothing is held here");
            assertTrue(manager.stopMapFill());
            manager.saveCache(); // the session ends holding no column

            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            assertFalse(loaded.mapFillRequested, "the stop reached the record");
            var map = new ColumnStateMap();
            map.adoptLoaded(loaded);
            assertEquals(48, map.mapOwedCount(), "and what the map is missing is still there for a later press");
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** Chat lines name the Fill Map button only where the options screen has one. On
     *  Sodium 0.6 (the usual one for Minecraft 1.21.1) or without Sodium, they name the
     *  command alone. */
    @Test
    void withoutAButtonTheLinesNameTheCommandAlone() {
        ClientCommandActions.setMenuButtonsForTest(false);
        converge();
        mapWriting = true;
        assertNull(fire());
        assertEquals(List.of("lss.mapfill.owed.command[360, " + dev.vox.lss.common.Brand.clientCommand() + "]"), notices);
    }

    /** The same far from a cache of before the record: its columns have an unknown history,
     *  and the press makes them owed at the save, wherever the player stood. Without a
     *  press they stay unknown, and a request found in the record does not stand for them. */
    @Test
    void aPressFarFromACacheOfUnknownHistoryMakesItOwed() {
        final String address = "map-fill-press-far-unknown";
        start(3, LSSConstants.PROTOCOL_VERSION, true, address);
        String bucket = manager.cacheBucketForTest();
        try {
            var stamps = new Long2LongOpenHashMap();
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) stamps.put(p(x, z), serverStamp(p(x, z)));
            }
            ColumnCacheStore.save(bucket, dim, stamps); // as 0.23 wrote it: no record

            rejoinAt(address, bucket, 3);
            mapWriting = true;
            assertEquals(49, columns().mapUnknownCount(), "premise");
            int far = 4 * manager.scannerForTest().getPruneDistance();
            manager.tickMovementPhase(far, 0);
            px = far;
            manager.mapFillForTest().onCacheLoaded(true, false); // as if the record had asked
            for (int i = 0; i < 4; i++) serve(fire());
            assertFalse(manager.mapFillForTest().requested(), "unknown is not owed: nothing to stand for");

            assertEquals(MapFillRequest.Outcome.NOTHING_HERE, manager.requestMapFill().outcome());
            assertTrue(manager.mapFillForTest().requested(), "after a press it is");
            manager.saveCache();

            var loaded = ColumnCacheStore.loadStateAsync(bucket, dim).join();
            assertTrue(loaded.mapFillRequested);
            var map = new ColumnStateMap();
            map.adoptLoaded(loaded);
            assertEquals(49, map.mapOwedCount(), "the cache's columns are owed now");
            assertEquals(0, map.mapUnknownCount());
        } finally {
            ColumnCacheStore.clearForServer(bucket);
        }
    }

    /** A request found in the record with nothing left to do here ends without a word. */
    @Test
    void aRequestFromAnEarlierSessionWithNothingInRangeSaysNothing() {
        start(3, LSSConstants.PROTOCOL_VERSION, true);
        mapWriting = true;
        converge(); // a map was written all along: nothing is owed
        manager.mapFillForTest().onCacheLoaded(true, false); // as the record reports it
        assertTrue(manager.mapFillForTest().requested());
        for (int i = 0; i < 6; i++) assertNull(fire());
        assertTrue(notices.isEmpty(), "no 'continuing', no 'finished: 0 columns'");
        assertFalse(manager.mapFillForTest().requested(), "this cache owes the map nothing: it has ended");
    }

    /** A cache from before the record: nobody is told (nothing is known to be owed), and a
     *  press takes every column of unknown history. */
    @Test
    void withAnUnknownHistoryNobodyIsToldAndAPressTakesEverythingHeld() {
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        var stamps = new Long2LongOpenHashMap();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x != 0 || z != 0) stamps.put(p(x, z), serverStamp(p(x, z)));
            }
        }
        var loaded = ColumnStateMap.buildLoaded(stamps);
        ColumnStateMap.applyMapRecord(loaded, null, null); // as the cache store loads a file without a record
        manager.setPendingStateLoadForTest(CompletableFuture.completedFuture(loaded));
        assertTrue(manager.tickCacheGatePhase());
        mapWriting = true;

        converge(); // the scan validates what it holds
        assertTrue(notices.isEmpty(), "unknown is not owed");
        assertEquals(0, manager.getMapOwedColumns());
        assertEquals(48, columns().mapUnknownCount());
        assertTrue(mapAsks.isEmpty());

        MapFillRequest request = manager.requestMapFill();
        assertEquals(MapFillRequest.Outcome.STARTED, request.outcome());
        assertEquals(48, request.columns());
        assertEquals(48, manager.getMapOwedColumns());
        assertEquals(0, columns().mapUnknownCount());
        assertEquals(new ColumnStateMap.MapDebt(true, true, true), manager.mapFillForTest().forSave(),
                "and the unknown rest of the file becomes owed at the next save");
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[48, 0]", lastNotice());
    }

    /** The cache could not be read: the stay goes on without it, a press still works, and a
     *  session that never learned what the record says about a fill leaves that alone. */
    @Test
    void aCacheThatCouldNotBeLoadedStillTakesAPress() {
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        manager.setPendingStateLoadForTest(CompletableFuture.failedFuture(new IllegalStateException("disk")));
        mapWriting = true;
        assertTrue(manager.tickCacheGatePhase());
        assertNull(manager.mapFillForTest().forSave().fillRequested(), "the record's own word stands");
        converge();
        assertEquals(0, manager.getMapOwedColumns(), "what arrives is written to the map");
        assertEquals(MapFillRequest.Outcome.NOTHING_HERE, manager.requestMapFill().outcome(),
                "the press is answered, not parked for a load that never comes");
        for (int i = 0; i < 3; i++) serve(fire());
        assertEquals(Boolean.TRUE, manager.mapFillForTest().forSave().fillRequested(),
                "what this cache owes is not known here: the request stands for the record to decide");

        // And a press made before the load failed starts when it has.
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        var load = new CompletableFuture<ColumnStateMap.LoadedState>();
        manager.setPendingStateLoadForTest(load);
        mapWriting = true;
        assertEquals(MapFillRequest.Outcome.WAITING_FOR_CACHE, manager.requestMapFill().outcome());
        load.completeExceptionally(new IllegalStateException("disk"));
        assertTrue(manager.tickCacheGatePhase());
        assertTrue(manager.mapFillForTest().requested());
        assertEquals(Boolean.TRUE, manager.mapFillForTest().forSave().fillRequested());
    }

    // ---- what a request can answer ----

    @Test
    void aRequestSaysWhyItCannotStart() {
        converge();
        assertEquals(MapFillRequest.Outcome.NO_MAP, manager.requestMapFill().outcome(), "no map is written");
        assertFalse(manager.mapFillForTest().requested());

        start(3, LSSConstants.V16_COMPAT_PROTOCOL_VERSION, true);
        mapWriting = true;
        assertEquals(MapFillRequest.Outcome.UNAVAILABLE, manager.requestMapFill().outcome(),
                "an older server dialect is not asked");

        start(3, LSSConstants.PROTOCOL_VERSION, false);
        mapWriting = true;
        assertEquals(MapFillRequest.Outcome.WAITING_FOR_CACHE, manager.requestMapFill().outcome());
        manager.mapFillForTest().onCacheLoaded(false, false);
        assertTrue(manager.mapFillForTest().requested(), "the request starts once the cache has loaded");

        // A press that is still waiting for the cache can be taken back too.
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        mapWriting = true;
        assertEquals(MapFillRequest.Outcome.WAITING_FOR_CACHE, manager.requestMapFill().outcome());
        assertTrue(manager.stopMapFill(), "there was a press to take back");
        manager.mapFillForTest().onCacheLoaded(false, false);
        assertFalse(manager.mapFillForTest().requested(), "and it does not start when the cache has loaded");

        start(3, LSSConstants.PROTOCOL_VERSION, true);
        mapWriting = true;
        manager.retireAcquisition(); // Receive switched off
        assertEquals(MapFillRequest.Outcome.UNAVAILABLE, manager.requestMapFill().outcome());
    }

    /** A fill that is running stops asking while no map takes columns, and goes on after. */
    @Test
    void aRunningFillWaitsWhileNoMapIsWritten() {
        pressAfterADownloadWithoutAMap(3);
        mapWriting = false; // the server asked for no map ingestion
        for (int i = 0; i < 5; i++) assertNull(fire());
        assertFalse(finished());
        assertTrue(manager.mapFillForTest().requested());
        mapWriting = true;
        fillToTheEnd();
        assertEquals("lss.mapfill.finished[48, 0]", lastNotice());
    }

    @Test
    void theLinesAPlayerReadsNameTheOutcome() {
        assertEquals("lss.mapfill.unavailable", key(ClientCommandActions.fillMapLine(null)));
        assertEquals("lss.mapfill.started",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.STARTED, 12))));
        assertEquals("lss.mapfill.waiting",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.WAITING_FOR_CACHE, 0))));
        assertEquals("lss.mapfill.nothing",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.NOTHING_OWED, 0))));
        assertEquals("lss.mapfill.nothing_here",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.NOTHING_HERE, 0))));
        assertEquals("lss.mapfill.running",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.RUNNING, 7))));
        assertEquals("lss.mapfill.no_map",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.NO_MAP, 0))));
        assertEquals("lss.mapfill.unavailable",
                key(ClientCommandActions.fillMapLine(new MapFillRequest(MapFillRequest.Outcome.UNAVAILABLE, 0))));
    }

    private static String key(Component line) {
        return ((TranslatableContents) line.getContents()).getKey();
    }

    /** A cleared cache (Re-download Terrain) holds nothing: no fill is asked for, and what
     *  arrives from then on is recorded. */
    @Test
    void afterAClearNoFillIsAskedFor() {
        start(3, LSSConstants.PROTOCOL_VERSION, false);
        assertNull(manager.mapFillForTest().forSave().fillRequested(), "premise: the record has not been read");
        manager.flushCache();
        assertEquals(new ColumnStateMap.MapDebt(false, false, false), manager.mapFillForTest().forSave());
    }
}
