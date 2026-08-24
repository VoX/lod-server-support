package dev.vox.lss.networking.client;

import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The section-leaf rewrite's differential gate (quadtree-client-state-plan.md §8 / R2):
 * drives identical op sequences into the new {@link ColumnStateMap} and the retained
 * {@link ReferenceColumnStateMap} (the shipped v0.11.1 hash implementation, verbatim) and
 * compares EVERY observable — classify, timestamps, all derived counts, the actionable-
 * retry ladder, the save/removal surfaces, and prune outcomes (position-granular
 * equivalence is the C-MAJOR-3 contract: which stamps survive movement must be
 * bit-identical, or the ts&gt;0/ts≤0 declaration mix drifts server-visibly).
 *
 * <p>Also pins the fast path's SOUNDNESS invariant: {@code ringNeedsFree} true ⇒ every
 * position on that ring classifies SATISFIED (the direction emission correctness rests
 * on; the converse — completeness — is a perf property the differential walk test
 * measures instead), and the detach/adopt cache lifecycle against the legacy merge-save.
 */
class SectionStateFuzzTest {

    // Position pool: a dense near square spanning many leaves incl. negative-coordinate
    // ones (the >>3 / &7 index math has sign traps), plus a far band that prune passes
    // genuinely cut.
    private static long[] positionPool() {
        var pool = new LongArrayList();
        for (int cx = -20; cx <= 20; cx++) {
            for (int cz = -20; cz <= 20; cz++) {
                pool.add(PositionUtil.packPosition(cx, cz));
            }
        }
        for (int i = -6; i <= 6; i++) {
            pool.add(PositionUtil.packPosition(60 + i, -3 * i));
            pool.add(PositionUtil.packPosition(-70, 55 + i));
        }
        return pool.toLongArray();
    }

    @Test
    void randomOpSequencesMatchTheReferenceImplementation() {
        for (long seed : new long[]{1L, 42L, 20260819L, -7L}) {
            fuzzOneSeed(seed);
        }
    }

    private void fuzzOneSeed(long seed) {
        var rng = new Random(seed);
        var impl = new ColumnStateMap();
        var ref = new ReferenceColumnStateMap();
        long[] pool = positionPool();

        boolean probeFired = false;
        boolean ratchetFired = false;
        for (int op = 0; op < 4_000; op++) {
            long p = pool[rng.nextInt(pool.length)];
            int kind = rng.nextInt(101); // 100 = the ratchet op (3-Opus fold: added by WIDENING, not by halving prune)
            if (kind == 100) {
                // Stamped-up_to_date ratchet (stamped-up-to-date-plan.md §4): a pure
                // monotonic ts advance on an existing positive mark-free unsatisfied
                // stamp. NOTE (3-Opus fold): this op CANNOT affect the needs mask by
                // construction — the differential's value here is the guard chain
                // (positive/dirty/retry/sessionSatisfied precedence) and the ts value
                // flowing into later classify/tile-validation ops identically.
                long second = 1L + rng.nextInt(12_000);
                boolean refMoved = ref.ratchetStamp(p, second);
                boolean implMoved = impl.ratchetStamp(p, second);
                assertEquals(refMoved, implMoved,
                        "ratchetStamp divergence at op " + op + " seed " + seed);
                ratchetFired |= implMoved;
                continue;
            }
            if (kind < 28) {
                // Mostly real stamps; occasionally the wire-legal ts=0 (a hostile/buggy
                // server — onColumnReceived applies it unchecked; review round 2).
                long ts = rng.nextInt(20) == 0 ? 0L : 1 + rng.nextInt(5_000);
                impl.onReceived(p, ts);
                ref.onReceived(p, ts);
            } else if (kind < 43) {
                impl.onUpToDate(p);
                ref.onUpToDate(p);
            } else if (kind < 50) {
                impl.onNotGenerated(p);
                ref.onNotGenerated(p);
            } else if (kind < 60) {
                impl.onIngestFailed(p);
                ref.onIngestFailed(p);
            } else if (kind < 70) {
                assertEquals(ref.markDirtyIfKnown(p), impl.markDirtyIfKnown(p),
                        "markDirtyIfKnown divergence at op " + op + " seed " + seed);
            } else if (kind < 74) {
                impl.markSessionSatisfied(p);
                ref.markSessionSatisfied(p);
            } else if (kind < 78) {
                impl.markRetry(p);
                ref.markRetry(p);
            } else if (kind < 81) {
                // Note WITHOUT an immediate resolve (review round 2: paired note/resolve
                // meant a staleInFlight bit never survived into a prune/clear).
                boolean inFlight = rng.nextBoolean();
                impl.noteStaleIfInFlight(p, inFlight);
                ref.noteStaleIfInFlight(p, inFlight);
            } else if (kind < 84) {
                assertEquals(ref.resolveStale(p), impl.resolveStale(p),
                        "resolveStale divergence at op " + op + " seed " + seed);
            } else if (kind < 87) {
                long pre = rng.nextInt(3) - 1 + rng.nextInt(2000); // sometimes <=0 (no-op branch)
                impl.markAuthoritativeClear(p, pre);
                ref.markAuthoritativeClear(p, pre);
            } else if (kind < 91) {
                // File rows: real stamps, the legacy 0 artifact, EXPLICIT -1 (a v0.11.1
                // client could persist the old backing's inert clamped entries — the
                // file-wins overwrite must still delete a live claim; review round 2),
                // and sub- -1 garbage (clamps, same overwrite).
                var loaded = new Long2LongOpenHashMap();
                loaded.defaultReturnValue(-1L);
                for (int i = 0, n = 1 + rng.nextInt(6); i < n; i++) {
                    long lp = pool[rng.nextInt(pool.length)];
                    long lts = switch (rng.nextInt(5)) {
                        case 0 -> 0L;                       // legacy artifact
                        case 1 -> -1L;                      // explicit claim-free row
                        case 2 -> -2L - rng.nextInt(100);   // corrupt garbage (clamps)
                        default -> 1L + rng.nextInt(5_000);
                    };
                    loaded.put(lp, lts);
                }
                impl.loadFrom(loaded);
                ref.loadFrom(loaded);
            } else if (kind < 94) {
                // Bulk-converge p's whole leaf so the ringNeedsFree probe below has real
                // needs-free territory to fire on (review round 2: without this the
                // TRUE branch was probabilistically unreachable).
                int baseX = (dev.vox.lss.common.PositionUtil.unpackX(p) >> 3) << 3;
                int baseZ = (dev.vox.lss.common.PositionUtil.unpackZ(p) >> 3) << 3;
                for (int dx = 0; dx < 8; dx++) {
                    for (int dz = 0; dz < 8; dz++) {
                        long lp = PositionUtil.packPosition(baseX + dx, baseZ + dz);
                        impl.onReceived(lp, 9_000L);
                        ref.onReceived(lp, 9_000L);
                    }
                }
            } else if (kind < 96) {
                int px = rng.nextInt(41) - 20, pz = rng.nextInt(41) - 20;
                int dist = 8 + rng.nextInt(80);
                impl.pruneOutOfRange(px, pz, dist);
                ref.pruneOutOfRange(px, pz, dist);
            } else if (kind < 97) {
                // Region-summary tile validation (final review MAJOR-1/2): the
                // provenance-scoped two-directional writer — validate strictly-newer
                // stamps as SUMMARY provenance, revoke ONLY summary-set bits, report
                // revocations. The one transition no other op produces is
                // revocation-in-isolation (validated cleared with no stamp/mark
                // change), so the needs invariant's coverage depends on this op.
                int tileX = PositionUtil.unpackX(p) >> 5;
                int tileZ = PositionUtil.unpackZ(p) >> 5;
                long stampM = switch (rng.nextInt(3)) {
                    case 0 -> 0L;                        // margined floor — validates all
                    case 1 -> 1L + rng.nextInt(5_000);   // mid-domain — mixed outcomes
                    default -> 9_999L;                   // above every stamp — revokes
                };
                var implRevoked = new LongArrayList();
                var refRevoked = new LongArrayList();
                var implOut = impl.applyTileValidation(tileX, tileZ, stampM, implRevoked::add);
                long[] refOut = ref.applyTileValidation(tileX, tileZ, stampM, refRevoked::add);
                assertEquals(refOut[0], implOut.newlyValidated(),
                        "applyTileValidation newlyValidated divergence at op " + op
                                + " seed " + seed);
                assertEquals(refOut[1] == 1, implOut.fullyValidated(),
                        "applyTileValidation fullyValidated divergence at op " + op
                                + " seed " + seed);
                implRevoked.sort(null);
                refRevoked.sort(null);
                assertEquals(refRevoked, implRevoked,
                        "applyTileValidation revocation-set divergence at op " + op
                                + " seed " + seed);
            } else if (kind < 98) {
                impl.clear();
                ref.clear();
            } else {
                // ringNeedsFree soundness probe on the CURRENT state: needs-free ⇒ every
                // ring position classifies SATISFIED. Conservative false is always legal.
                int px = rng.nextInt(31) - 15, pz = rng.nextInt(31) - 15;
                int r;
                if (rng.nextBoolean()) {
                    // Self-arranged flavor: converge the 3×3 leaf block around the probe
                    // center first (mirrored into both maps), then probe a ring contained
                    // in it — the TRUE branch fires deterministically (an opportunistic
                    // probe alone almost never finds fully-clean crossed leaves under
                    // this op churn; review round 2). Also pins COMPLETENESS: a fully
                    // converged neighborhood MUST report needs-free.
                    int baseX = ((px >> 3) - 1) << 3, baseZ = ((pz >> 3) - 1) << 3;
                    for (int dx = 0; dx < 24; dx++) {
                        for (int dz = 0; dz < 24; dz++) {
                            long lp = PositionUtil.packPosition(baseX + dx, baseZ + dz);
                            impl.onReceived(lp, 8_500L);
                            ref.onReceived(lp, 8_500L);
                        }
                    }
                    r = 1 + rng.nextInt(4); // extent ±4 stays inside the converged block
                    assertTrue(impl.ringNeedsFree(px, pz, r),
                            "a fully converged neighborhood must be needs-free (seed "
                                    + seed + ", op " + op + ")");
                } else {
                    r = 1 + rng.nextInt(12); // opportunistic: whatever state stands
                }
                if (impl.ringNeedsFree(px, pz, r)) {
                    probeFired = true;
                    int[] c = new int[2];
                    for (int i = 0; i < 8 * r; i++) {
                        SpiralScanner.ringIndexToCoord(r, i, px, pz, c);
                        long rp = PositionUtil.packPosition(c[0], c[1]);
                        assertEquals(ColumnStateMap.SATISFIED, impl.classify(rp),
                                "ringNeedsFree claimed ring " + r + " around (" + px + "," + pz
                                        + ") satisfied but (" + c[0] + "," + c[1]
                                        + ") classifies otherwise — the fast path would skip"
                                        + " an emission (seed " + seed + ", op " + op + ")");
                    }
                }
            }

            // Cheap parity every op on the touched position; the full sweep periodically.
            assertEquals(ref.classify(p), impl.classify(p), "classify(" + p + ") at op " + op + " seed " + seed);
            assertEquals(ref.timestampFor(p), impl.timestampFor(p), "timestampFor at op " + op + " seed " + seed);
            if (op % 23 == 0) assertParity(ref, impl, pool, rng, seed, op);
        }
        assertParity(ref, impl, pool, rng, seed, -1);
        assertTrue(probeFired, "the ringNeedsFree soundness probe's TRUE branch must fire"
                + " at least once per seed (the converge-leaf op guarantees territory;"
                + " a never-firing probe pins nothing — review round 2)");
        assertTrue(ratchetFired, "the ratchet op must actually MOVE a stamp at least"
                + " once per seed — a shared guard bug returning false in both"
                + " implementations would otherwise pass the differential while the"
                + " feature is dead (final panel; the probeFired discipline mirrored)");
    }

    @Test
    void ringNeedsFreeEngagesOnConvergedLeavesAndDisengagesPerNeedsFlavor() {
        // Deterministic engagement + disengagement (review round 2: the fuzz probe alone
        // was probabilistically weak). A fully received 3×3-leaf block (chunks [0..23]²)
        // is needs-free for every contained ring; each needs-producing state flips it.
        var store = new ColumnStateMap();
        for (int cx = 0; cx < 24; cx++) {
            for (int cz = 0; cz < 24; cz++) {
                store.onReceived(PositionUtil.packPosition(cx, cz), 1_000L);
            }
        }
        assertTrue(store.ringNeedsFree(12, 12, 3), "single-leaf ring on a converged leaf");
        assertTrue(store.ringNeedsFree(12, 12, 8), "multi-leaf ring across all nine leaves");
        assertFalse(store.ringNeedsFree(12, 12, 12),
                "a ring crossing ABSENT leaves (outside the block) is never needs-free");

        long probe = PositionUtil.packPosition(10, 10); // on ring 3's crossed leaf
        // dirty
        assertTrue(store.markDirtyIfKnown(probe));
        assertFalse(store.ringNeedsFree(12, 12, 3), "a dirty mark disengages the ring");
        store.onUpToDate(probe); // heals: clears dirty, >0 stamp revalidates
        assertTrue(store.ringNeedsFree(12, 12, 3));
        // retry
        store.markRetry(probe);
        assertFalse(store.ringNeedsFree(12, 12, 3), "a retry mark disengages the ring");
        store.onReceived(probe, 1_100L); // consumes the retry
        assertTrue(store.ringNeedsFree(12, 12, 3));
        // unstamp (ingest failure)
        store.onIngestFailed(probe);
        assertFalse(store.ringNeedsFree(12, 12, 3), "an ingest unstamp disengages the ring");
        store.onReceived(probe, 1_200L);
        assertTrue(store.ringNeedsFree(12, 12, 3));
        // unvalidated stamp (a fresh session's cached-not-yet-revalidated shape)
        var loadedShape = new ColumnStateMap();
        var file = new Long2LongOpenHashMap();
        file.defaultReturnValue(-1L);
        for (int cx = 8; cx < 16; cx++) {
            for (int cz = 8; cz < 16; cz++) {
                file.put(PositionUtil.packPosition(cx, cz), 500L);
            }
        }
        loadedShape.adoptLoaded(ColumnStateMap.buildLoaded(file));
        assertFalse(loadedShape.ringNeedsFree(12, 12, 3),
                "adopted-but-unvalidated stamps are revalidation NEEDS — never fast-skipped");
    }

    private void assertParity(ReferenceColumnStateMap ref, ColumnStateMap impl,
                              long[] pool, Random rng, long seed, int op) {
        String at = " (seed " + seed + ", op " + op + ")";
        for (long p : pool) {
            assertEquals(ref.classify(p), impl.classify(p), "classify sweep" + at);
            assertEquals(ref.timestampFor(p), impl.timestampFor(p), "timestamp sweep" + at);
            assertEquals(ref.isSessionSatisfied(p), impl.isSessionSatisfied(p), "sessionSatisfied sweep" + at);
            // The needs invariant DIRECTLY, bit-for-bit (final panel): needs bit ⇔
            // classify != SATISFIED. The aggregated ringNeedsFree probes cannot see a
            // stuck-OFF bit (the dangerous direction — the fast path would skip a
            // needing position) while any sibling bit legitimately holds the leaf's
            // aggregate up.
            assertEquals(impl.classify(p) != ColumnStateMap.SATISFIED, impl.needsBitForTest(p),
                    "needs bit diverges from classify at " + p + at);
        }
        assertEquals(ref.receivedCount(), impl.receivedCount(), "receivedCount" + at);
        assertEquals(ref.emptyCount(), impl.emptyCount(), "emptyCount" + at);
        assertEquals(ref.dirtyCount(), impl.dirtyCount(), "dirtyCount" + at);
        assertEquals(ref.sessionSatisfiedCount(), impl.sessionSatisfiedCount(), "sessionSatisfiedCount" + at);
        assertEquals(ref.hasRetries(), impl.hasRetries(), "hasRetries" + at);
        assertEquals(ref.hasPersistentRemovals(), impl.hasPersistentRemovals(), "hasPersistentRemovals" + at);
        assertEquals(ref.persistentRemovalsForSave(), impl.persistentRemovalsForSave(), "persistentRemovals" + at);

        // Save-map content: filter the reference's inert -1 entries (the sanctioned
        // clamp-to-absent divergence — observationally identical everywhere else).
        var refMap = new Long2LongOpenHashMap();
        refMap.defaultReturnValue(-1L);
        for (var e : ref.mapForSave().long2LongEntrySet()) {
            if (e.getLongValue() != -1L) refMap.put(e.getLongKey(), e.getLongValue());
        }
        assertEquals(refMap, impl.mapForSave(), "save-map content" + at);
        assertEquals(refMap.isEmpty(), impl.isEmptyMap(), "isEmptyMap (present-entry form)" + at);

        // regionNeedsFree (region-scan-plan.md §2.2): the region walk's skip probe vs a
        // brute-force classify sweep of the REFERENCE over each pool region. Sampled —
        // the sweep is 1024 classifies per region.
        if (op % 50 == 0) {
            var regions = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (long p : pool) {
                regions.add(PositionUtil.packPosition(
                        PositionUtil.unpackX(p) >> 5, PositionUtil.unpackZ(p) >> 5));
            }
            int lod = 4096; // whole pool in-lod around (0,0) — the lod-clamp arm is pinned in ColumnStateMapTest
            for (long r : regions) {
                int rx = PositionUtil.unpackX(r), rz = PositionUtil.unpackZ(r);
                boolean brute = true;
                for (int lz = 0; lz < 32 && brute; lz++) {
                    for (int lx = 0; lx < 32; lx++) {
                        int cx = (rx << 5) + lx, cz = (rz << 5) + lz;
                        if (Math.abs(cx) > lod || Math.abs(cz) > lod) continue;
                        if (ref.classify(PositionUtil.packPosition(cx, cz)) != ColumnStateMap.SATISFIED) {
                            brute = false;
                            break;
                        }
                    }
                }
                assertEquals(brute, impl.regionNeedsFree(rx, rz, 0, 0, lod),
                        "regionNeedsFree diverges from the reference sweep at region "
                                + rx + "," + rz + at);
            }
        }

        // The actionable-retry ladder with a random view: boolean + visited-ring multiset.
        int px = rng.nextInt(21) - 10, pz = rng.nextInt(21) - 10, excl = 2 + rng.nextInt(10);
        assertEquals(ref.hasActionableRetries(px, pz, excl), impl.hasActionableRetries(px, pz, excl),
                "hasActionableRetries" + at);
        var refRings = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        var implRings = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        ref.collectActionableRetryRings(px, pz, excl, r -> refRings.addTo(r, 1));
        impl.collectActionableRetryRings(px, pz, excl, r -> implRings.addTo(r, 1));
        assertEquals(refRings, implRings, "collectActionableRetryRings multiset" + at);
    }

    // ---- cache lifecycle: detach/adopt vs the legacy copy paths ----

    private static ResourceKey<Level> testDimension(String name) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse("lss_test:" + name));
    }

    @Test
    void detachForSaveEmptiesTheStoreAndMergeSaveDetachedMatchesLegacyMergeSave() {
        final String serverLegacy = "test-quad-detach-legacy";
        final String serverDetached = "test-quad-detach-new";
        var dim = testDimension("quad_detach");
        try {
            // Same starting file on both servers (history the merge must preserve).
            var history = new Long2LongOpenHashMap();
            history.defaultReturnValue(-1L);
            history.put(PositionUtil.packPosition(100, 100), 111L);
            history.put(PositionUtil.packPosition(3, 4), 222L);
            ColumnCacheStore.save(serverLegacy, dim, history);
            ColumnCacheStore.save(serverDetached, dim, history);

            // Build identical live state twice (detach consumes one).
            var forLegacy = new ColumnStateMap();
            var forDetach = new ColumnStateMap();
            for (var m : new ColumnStateMap[]{forLegacy, forDetach}) {
                m.onReceived(PositionUtil.packPosition(3, 4), 999L);   // overlays history
                m.onReceived(PositionUtil.packPosition(-9, 17), 555L); // new entry
                m.onReceived(PositionUtil.packPosition(40, -40), 777L);
                m.onIngestFailed(PositionUtil.packPosition(40, -40));  // honest unstamp -> removal
            }

            ColumnCacheStore.mergeSave(serverLegacy, dim, forLegacy.mapForSave(),
                    forLegacy.persistentRemovalsForSave(), 0, 0);

            var detached = forDetach.detachForSave();
            assertTrue(forDetach.isEmptyMap(), "detach must reset the live store");
            assertEquals(0, forDetach.receivedCount(), "counters reset with the detach");
            assertEquals(-1L, forDetach.timestampFor(PositionUtil.packPosition(3, 4)),
                    "detached stamps are gone from the live store");
            assertFalse(forDetach.hasPersistentRemovals(), "removals transferred out");
            ColumnCacheStore.mergeSaveDetached(serverDetached, dim, detached, 0, 0);

            assertEquals(ColumnCacheStore.load(serverLegacy, dim),
                    ColumnCacheStore.load(serverDetached, dim),
                    "the detached merge-save must write byte-equivalent content to the legacy path");
        } finally {
            ColumnCacheStore.clearForServer(serverLegacy);
            ColumnCacheStore.clearForServer(serverDetached);
        }
    }

    @Test
    void buildLoadedPlusAdoptEqualsLoadFromOverLiveState() {
        // The off-thread A1 path (buildLoaded on IO + adoptLoaded on main) must equal the
        // legacy inline loadFrom, including the merge-with-live-marks corner: the cache
        // gate can apply over early dirty marks, and file stamps must overwrite live
        // stamps (the loadFrom contract) while marks survive untouched.
        var viaAdopt = new ColumnStateMap();
        var viaLoadFrom = new ReferenceColumnStateMap();
        long early = PositionUtil.packPosition(5, 5);
        long stamped = PositionUtil.packPosition(6, 5);
        for (Object m : new Object[]{viaAdopt, viaLoadFrom}) {
            if (m instanceof ColumnStateMap c) {
                c.onReceived(stamped, 50L);
                c.onReceived(early, 60L);
                c.markDirtyIfKnown(early);
            } else if (m instanceof ReferenceColumnStateMap c) {
                c.onReceived(stamped, 50L);
                c.onReceived(early, 60L);
                c.markDirtyIfKnown(early);
            }
        }
        var loaded = new Long2LongOpenHashMap();
        loaded.defaultReturnValue(-1L);
        loaded.put(early, 4_000L);                        // overwrites the live stamp
        loaded.put(PositionUtil.packPosition(7, 7), 4_100L);
        loaded.put(PositionUtil.packPosition(8, 8), -55L); // corrupt -> clamped/dropped

        viaAdopt.adoptLoaded(ColumnStateMap.buildLoaded(loaded));
        viaLoadFrom.loadFrom(loaded);

        for (long p : new long[]{early, stamped, PositionUtil.packPosition(7, 7),
                PositionUtil.packPosition(8, 8)}) {
            assertEquals(viaLoadFrom.classify(p), viaAdopt.classify(p), "classify parity for " + p);
            assertEquals(viaLoadFrom.timestampFor(p), viaAdopt.timestampFor(p), "timestamp parity for " + p);
        }
        assertEquals(viaLoadFrom.receivedCount(), viaAdopt.receivedCount());
        // The dirty mark survives the load on both paths (loadFrom never touched marks).
        assertEquals(4_000L, viaAdopt.classify(early),
                "dirty + file stamp: classify returns the (new) stored stamp for the re-ask");
    }

    @Test
    void detachedStateVisitsExactlyThePresentEntries() {
        var store = new ColumnStateMap();
        store.onReceived(PositionUtil.packPosition(-1, -1), 10L); // negative-leaf corner
        store.onReceived(PositionUtil.packPosition(0, 0), 20L);
        store.markSessionSatisfied(PositionUtil.packPosition(2, 2)); // mark-only: NOT visited
        var loaded = new Long2LongOpenHashMap();
        loaded.defaultReturnValue(-1L);
        loaded.put(PositionUtil.packPosition(9, 9), 0L); // legacy zero IS a present entry
        store.loadFrom(loaded);

        var detached = store.detachForSave();
        var seen = new Long2LongOpenHashMap();
        seen.defaultReturnValue(-1L);
        detached.forEachPresent(seen::put);
        assertEquals(3, seen.size(), "two stamps + one legacy zero");
        assertEquals(10L, seen.get(PositionUtil.packPosition(-1, -1)));
        assertEquals(20L, seen.get(PositionUtil.packPosition(0, 0)));
        assertEquals(0L, seen.get(PositionUtil.packPosition(9, 9)));
    }
}
