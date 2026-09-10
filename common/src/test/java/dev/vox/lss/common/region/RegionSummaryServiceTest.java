package dev.vox.lss.common.region;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the region-summary server core (region-summary-sync-plan.md §5): latest-wins
 * request coalescing, dimension-matched admission with the raced-registration TTL
 * retention, the center clamp (+ its range_filtered count), sweeper assembly →
 * ready-frame drain on the pump, per-job failure containment, and disconnect sweep.
 */
class RegionSummaryServiceTest {

    private static final String DIM = "minecraft:overworld";
    private static final long NOW = System.currentTimeMillis() / 1000L;
    private static final long POLL_DEADLINE_NANOS = 30_000_000_000L;

    private record Sent(UUID player, byte[] frame) {}

    private static final class Rig {
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        final ConcurrentLinkedQueue<Sent> sent = new ConcurrentLinkedQueue<>();
        final RegionSummaryService service;
        volatile RegionSummaryService.SendOutcome senderOutcome =
                RegionSummaryService.SendOutcome.SENT;

        Rig(RegionSummaryService.TileStampSource source) {
            // Max distance: the server window equals the protocol max, so wire-cap
            // pins stay meaningful. Clamp tests use the explicit-distance ctor.
            this(source, 2048);
        }

        Rig(RegionSummaryService.TileStampSource source, int lodDistanceChunks) {
            this.service = new RegionSummaryService(source, () -> lodDistanceChunks,
                    this.clock::get);
        }

        Function<UUID, RegionSummaryService.PlayerAnchor> anchorsAt(UUID player, String dim,
                                                                     int cx, int cz) {
            return u -> u.equals(player) ? new RegionSummaryService.PlayerAnchor(dim, cx, cz) : null;
        }

        void pump(Function<UUID, RegionSummaryService.PlayerAnchor> anchors) {
            this.service.pump(anchors, (p, f) -> {
                var out = this.senderOutcome;
                if (out == RegionSummaryService.SendOutcome.SENT) this.sent.add(new Sent(p, f));
                return out;
            });
        }

        Sent pumpUntilFrame(Function<UUID, RegionSummaryService.PlayerAnchor> anchors)
                throws InterruptedException {
            long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
            while (true) {
                pump(anchors);
                var s = this.sent.poll();
                if (s != null) return s;
                if (System.nanoTime() > deadline) fail("timed out waiting for a summary frame");
                Thread.sleep(10);
            }
        }
    }

    @Test
    void admittedRequestProducesADecodableWindow() throws Exception {
        // Stamp formula keyed on tile coords so the row-major order is pinned too.
        var rig = new Rig((dim, tx, tz) -> NOW - 1000 + tx * 10L + tz);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 2, 3, 1));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 2 << 5, 3 << 5));
            assertEquals(player, sent.player());
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(DIM, summary.dimension());
            assertEquals(2, summary.centerTileX());
            assertEquals(3, summary.centerTileZ());
            assertEquals(1, summary.tileRadius());
            // Row-major, x fastest within z rows: first entry is (tx=1, tz=2). Real
            // stamps carry the shared serve-latency margin (the header rung doctrine).
            long margin = RegionStampTable.FRESH_CLAIM_MARGIN_SECONDS;
            assertEquals(NOW - 1000 + 10 + 2 + margin, summary.stampSeconds()[0]);
            assertEquals(NOW - 1000 + 30 + 4 + margin, summary.stampSeconds()[8]);
            assertEquals(9, rig.service.diagnostics().getTilesKnown());
            assertEquals(1, rig.service.diagnostics().getFrames());
            assertEquals(sent.frame().length, rig.service.diagnostics().getBytes());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void latestRequestWinsBeforeAdmission() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 2));
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 5, 5, 1));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 5 << 5, 5 << 5));
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(5, summary.centerTileX());
            assertEquals(1, summary.tileRadius());
            assertNull(rig.sent.poll(), "exactly one frame — the first request was coalesced");
            assertEquals(2, rig.service.diagnostics().getRequests());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void hostileCenterClampsToThePlayerWindowAndCounts() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player,
                    new RegionSummaryWire.Request(DIM, 1_000_000, -1_000_000, 0));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 0, 0));
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS, summary.centerTileX());
            assertEquals(-RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS, summary.centerTileZ());
            assertEquals(1, rig.service.diagnostics().getRangeFiltered());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void dimensionMismatchRetainsUntilTtlThenDrops() {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            // The player is still registered under the OLD dimension: retained.
            rig.pump(rig.anchorsAt(player, "minecraft:the_end", 0, 0));
            assertTrue(rig.service.hasPendingForTest(player), "raced request must be retained");
            // Past the TTL the stale request drops instead of lingering forever.
            rig.clock.addAndGet(RegionSummaryService.PENDING_TTL_NANOS + 1);
            rig.pump(rig.anchorsAt(player, "minecraft:the_end", 0, 0));
            assertFalse(rig.service.hasPendingForTest(player), "expired request must drop");
            assertNull(rig.sent.poll());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void unknownPlayerRetainsThenExpires() {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.pump(u -> null); // not registered yet
            assertTrue(rig.service.hasPendingForTest(player));
            rig.clock.addAndGet(RegionSummaryService.PENDING_TTL_NANOS + 1);
            rig.pump(u -> null);
            assertFalse(rig.service.hasPendingForTest(player));
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void removePlayerSweepsPendingWork() {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.service.removePlayer(player);
            assertFalse(rig.service.hasPendingForTest(player));
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void radiusAndCenterClampToTheServerWindowNotTheProtocolMax() throws Exception {
        // The I-M1 amplification fix: lodDistance 64 -> ceil(64/32)+1 = 3 tiles. A
        // radius-65 protocol-max request must shrink to the server's own window, and
        // the hostile center clamps so the WINDOW EDGES stay inside playerTile +-
        // maxTiles (final panel: an independent center clamp let a crafted
        // off-center max-radius request reach ~2x as far as an honest one) — at the
        // full window radius the center pins to the player's own tile.
        var rig = new Rig((dim, tx, tz) -> NOW, 64);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(
                    DIM, 1_000_000, -1_000_000, RegionSummaryWire.MAX_SUMMARY_TILE_RADIUS));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 0, 0));
            var summary = RegionSummaryWire.decodeSummary(sent.frame());
            assertEquals(3, summary.tileRadius(), "radius clamped to the server window");
            assertEquals(0, summary.centerTileX(),
                    "window-edge clamp: full radius pins the center to the player tile");
            assertEquals(0, summary.centerTileZ());
            assertEquals(49, summary.stampSeconds().length, "(2*3+1)^2 tiles, not 131^2");
            assertEquals(1, rig.service.diagnostics().getRangeFiltered());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void resweepCooldownRetainsThenAdmits() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            var anchors = rig.anchorsAt(player, DIM, 0, 0);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pumpUntilFrame(anchors);
            // A second request inside the cooldown is RETAINED (not dropped, not swept):
            // the stamp table's memos cannot have changed inside its stat horizon.
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(anchors);
            assertTrue(rig.service.hasPendingForTest(player),
                    "cooldown-held request must be retained");
            assertNull(rig.sent.poll(), "no second frame inside the cooldown");
            // Past the cooldown the retained request admits without a re-send.
            rig.clock.addAndGet(RegionSummaryService.RESWEEP_COOLDOWN_NANOS + 1);
            var sent = rig.pumpUntilFrame(anchors);
            assertEquals(player, sent.player());
            assertEquals(2, rig.service.diagnostics().getFrames());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void removePlayerClearsTheCooldownMark() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            var anchors = rig.anchorsAt(player, DIM, 0, 0);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pumpUntilFrame(anchors);
            // Disconnect sweeps the cooldown: a same-UUID rejoin's at-entry request
            // must not inherit the dead session's rate mark.
            rig.service.removePlayer(player);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            var sent = rig.pumpUntilFrame(anchors);
            assertEquals(player, sent.player(), "post-disconnect request admits immediately");
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void aDeclinedSendIsNotCountedAsAFrame() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.DROP;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0));
            assertEquals(0, rig.service.readyCountForTest(), "the frame was drained");
            assertEquals(0, rig.service.diagnostics().getFrames(),
                    "frames/bytes mean PUT ON THE WIRE — a vanished-player drop is uncounted");
            assertEquals(0, rig.service.diagnostics().getBytes());
        } finally {
            rig.service.shutdown();
        }
    }

    /** The retention half of the F2 writability guard (live-diagnosed 2026-08-20:
     *  rig reqs=7/frames=5 — frames drained at the join flood's unwritable instant
     *  were being discarded, and the fire-and-forget client never re-asks): a RETRY
     *  outcome must RETAIN the frame, uncounted, and the next writable drain must
     *  deliver it. */
    @Test
    void aRetriedFrameIsRetainedAndSentByTheNextWritableDrain() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.RETRY;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // unwritable drain
            assertEquals(1, rig.service.readyCountForTest(),
                    "a RETRY frame must be retained, not discarded");
            assertEquals(0, rig.service.diagnostics().getFrames(), "retention is uncounted");
            rig.senderOutcome = RegionSummaryService.SendOutcome.SENT;
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // writable again
            var s = rig.sent.poll();
            assertNotNull(s, "the retained frame must go out on the next writable drain");
            assertEquals(player, s.player());
            assertEquals(0, rig.service.readyCountForTest());
            assertEquals(1, rig.service.diagnostics().getFrames());
        } finally {
            rig.service.shutdown();
        }
    }

    /** The ordering half of retention (final-panel MAJOR, 2026-08-20): the client's
     *  summary apply has NO recency guard — its correctness argument is that a stale
     *  frame is always corrected by a later fresh one, never applied after it. So a
     *  RETRY-retained frame must send BEFORE any fresher frame assembled for the
     *  same player meanwhile (the old shared-queue tail re-add sent fresh-then-stale,
     *  letting the stale frame's lower tile stamps re-validate positions the fresh
     *  frame's revocation had just cleared — a false-clean seal beyond dirty range). */
    @Test
    void retainedFramesSendInAssemblyOrderPerPlayer() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            var anchors = rig.anchorsAt(player, DIM, 0, 0);
            rig.senderOutcome = RegionSummaryService.SendOutcome.RETRY;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.pump(anchors); // admit F1
            awaitAssembly(rig);
            rig.pump(anchors); // unwritable drain — F1 retained at its queue head
            assertEquals(1, rig.service.readyCountForTest());
            rig.clock.addAndGet(RegionSummaryService.RESWEEP_COOLDOWN_NANOS + 1);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 2));
            rig.pump(anchors); // admit F2 (channel still unwritable)
            // awaitAssembly waits for nonzero, which F1's retention already satisfies —
            // wait for the SECOND frame explicitly.
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (rig.service.readyCountForTest() < 2) {
                if (System.nanoTime() > deadline) fail("timed out waiting for F2 assembly");
                Thread.sleep(10);
            }
            assertEquals(2, rig.service.readyCountForTest(), "both frames retained");
            rig.senderOutcome = RegionSummaryService.SendOutcome.SENT;
            rig.pump(anchors); // writable: both drain, IN ASSEMBLY ORDER
            var first = rig.sent.poll();
            var second = rig.sent.poll();
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(1, RegionSummaryWire.decodeSummary(first.frame()).tileRadius(),
                    "the RETAINED (older) frame must send first");
            assertEquals(2, RegionSummaryWire.decodeSummary(second.frame()).tileRadius(),
                    "the fresher frame sends after the retained one");
            assertEquals(0, rig.service.readyCountForTest());
        } finally {
            rig.service.shutdown();
        }
    }

    /** removePlayer sweeps retained frames (final panel): a rejoin inside the RETRY
     *  TTL must not receive a dead session's frame. */
    @Test
    void removePlayerSweepsRetainedFrames() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.RETRY;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // unwritable — retained
            assertEquals(1, rig.service.readyCountForTest());
            rig.service.removePlayer(player);
            assertEquals(0, rig.service.readyCountForTest(),
                    "retained frames die with the connection");
        } finally {
            rig.service.shutdown();
        }
    }

    /** The retention belt: a frame that stays RETRY past FRAME_RETRY_TTL_NANOS is
     *  dropped as stale (the sender is not even consulted for it) — a permanently
     *  unwritable channel must not accumulate frames forever. */
    @Test
    void aRetriedFrameExpiresAtTheTtl() throws Exception {
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.senderOutcome = RegionSummaryService.SendOutcome.RETRY;
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admit
            awaitAssembly(rig);
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // unwritable — retained
            assertEquals(1, rig.service.readyCountForTest());
            rig.clock.addAndGet(RegionSummaryService.FRAME_RETRY_TTL_NANOS + 1);
            rig.senderOutcome = RegionSummaryService.SendOutcome.SENT; // channel healed too late
            rig.pump(rig.anchorsAt(player, DIM, 0, 0));
            assertEquals(0, rig.service.readyCountForTest(), "the expired frame is gone");
            assertEquals(0, rig.service.diagnostics().getFrames(),
                    "an expired frame is never sent");
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void eligibilityMarkArmsOnRequestAndSweepsAtDisconnect() {
        // Stamped up_to_date (stamped-up-to-date-plan.md §9.4): the summary request IS
        // the stamps-eligibility declaration; the mark survives admission (unlike the
        // pending mailbox) and dies ONLY at the network-disconnect sweep.
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            assertFalse(rig.service.hasRequestedThisSession(player));
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            assertTrue(rig.service.hasRequestedThisSession(player), "armed by the request");
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admission consumes the mailbox...
            assertTrue(rig.service.hasRequestedThisSession(player), "...but never the mark");
            rig.service.removePlayer(player);
            assertFalse(rig.service.hasRequestedThisSession(player), "swept at disconnect");
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void aQuitDuringAssemblyCannotResurrectTheReadyQueue() throws Exception {
        // Panel fold 2026-08-22: removePlayer landing between the pump's jobs
        // hand-off and assemble's ready-queue add used to recreate the departed
        // UUID's queue — the vanished-send belt dropped the frame but the empty
        // queue leaked for the server's life (the anchor-less sweep iterates
        // requestedThisSession, which no longer held the UUID), and a same-UUID
        // rejoin inside the RETRY TTL could receive the dead session's frame.
        // The sweeper's post-add liveness re-check closes both.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var rig = new Rig((dim, tx, tz) -> {
            entered.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return NOW;
        });
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.pump(rig.anchorsAt(player, DIM, 0, 0)); // admits; the sweeper starts
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS),
                    "premise: assembly in flight on the sweeper");
            rig.service.removePlayer(player); // the quit lands mid-assembly
            release.countDown();
            long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
            while (rig.service.readyCountForTest() != 0) {
                if (System.nanoTime() > deadline) {
                    fail("timed out waiting for the post-add re-check to sweep the"
                            + " resurrected queue, ready=" + rig.service.readyCountForTest());
                }
                Thread.sleep(10);
            }
            assertFalse(rig.service.hasReadyEntryForTest(player),
                    "the resurrected MAP ENTRY is swept too — a drained-but-retained"
                            + " empty queue for a dead UUID is the server-lifetime leak");
            rig.pump(u -> null); // a later drain must find nothing to send
            assertNull(rig.sent.poll(), "no dead-session frame may reach the sender");
        } finally {
            release.countDown();
            rig.service.shutdown();
        }
    }

    @Test
    void anchorlessSweepClearsAQuitRaceResurrectedMark() {
        // The Folia quit race (the pump's anchor-less eligibility sweep): a
        // region-thread offerRequest lands AFTER the tick thread's removePlayer,
        // resurrecting the eligibility mark with no disconnect sweep left to run.
        // The resurrected request's own pending entry shields the mark (a genuine
        // mid-registration request must not lose stamps eligibility — the client
        // requests only at dimension entry) until the pending TTL lapses; then the
        // sweep clears the mark in the same pump that drops the stale pending.
        var rig = new Rig((dim, tx, tz) -> NOW);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            rig.service.removePlayer(player);
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            assertTrue(rig.service.hasRequestedThisSession(player),
                    "premise: the racing offer resurrected the mark");
            rig.pump(u -> null); // anchor-less, pending inside its TTL: shielded
            assertTrue(rig.service.hasRequestedThisSession(player),
                    "the pending entry shields a mid-registration mark");
            rig.clock.addAndGet(RegionSummaryService.PENDING_TTL_NANOS + 1);
            rig.pump(u -> null); // drops the expired pending, then sweeps the mark
            assertFalse(rig.service.hasRequestedThisSession(player),
                    "the anchor-less sweep clears the quit-race mark");
        } finally {
            rig.service.shutdown();
        }
    }

    private static void awaitAssembly(Rig rig) throws InterruptedException {
        long deadline = System.nanoTime() + POLL_DEADLINE_NANOS;
        while (rig.service.readyCountForTest() == 0) {
            if (System.nanoTime() > deadline) fail("timed out waiting for assembly");
            Thread.sleep(10);
        }
    }

    @Test
    void noRegionTilesCountApartFromKnown() throws Exception {
        // H-m3: "everything is clean" and "no region files at all" must be
        // distinguishable — a whole-window no_region count is the resolver signal.
        var rig = new Rig((dim, tx, tz) -> tx == 0 && tz == 0
                ? NOW : RegionSummaryWire.STAMP_NO_REGION);
        try {
            var player = UUID.randomUUID();
            rig.service.offerRequest(player, new RegionSummaryWire.Request(DIM, 0, 0, 1));
            rig.pumpUntilFrame(rig.anchorsAt(player, DIM, 0, 0));
            assertEquals(1, rig.service.diagnostics().getTilesKnown());
            assertEquals(8, rig.service.diagnostics().getTilesNoRegion());
            assertEquals(0, rig.service.diagnostics().getTilesNeverClean());
        } finally {
            rig.service.shutdown();
        }
    }

    @Test
    void assemblyFailureIsContainedAndLaterJobsStillServe() throws Exception {
        var rig = new Rig((dim, tx, tz) -> {
            if ("minecraft:the_end".equals(dim)) throw new IllegalStateException("boom");
            return NOW;
        });
        try {
            var bad = UUID.randomUUID();
            rig.service.offerRequest(bad, new RegionSummaryWire.Request("minecraft:the_end", 0, 0, 1));
            rig.pump(rig.anchorsAt(bad, "minecraft:the_end", 0, 0));
            // Give the sweeper a moment to consume (and contain) the failing job.
            Thread.sleep(50);
            var good = UUID.randomUUID();
            rig.service.offerRequest(good, new RegionSummaryWire.Request(DIM, 0, 0, 0));
            var sent = rig.pumpUntilFrame(rig.anchorsAt(good, DIM, 0, 0));
            assertEquals(good, sent.player(), "the sweeper survived the failing job");
        } finally {
            rig.service.shutdown();
        }
    }
}
