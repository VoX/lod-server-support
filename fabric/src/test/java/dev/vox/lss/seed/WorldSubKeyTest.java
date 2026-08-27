package dev.vox.lss.seed;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world axis's pure predicate (plan §2.3): a sub-key exists only when EVERY term
 * holds, an unreadable seed is the ONE carry-forward shape, and the suffix namespace
 * (mint format, sweep anchor, reserved-tail escape) behaves as the contract the store
 * and the reset sweep rely on.
 */
class WorldSubKeyTest {

    private static WorldSubKey.Context ctx(boolean enabled, boolean remote, boolean realm,
                                           boolean singleplayer, boolean seedAvailable, long seed) {
        return new WorldSubKey.Context(enabled, remote, realm, singleplayer, seedAvailable, seed);
    }

    /** The all-terms-hold baseline every negative case perturbs. */
    private static WorldSubKey.Context qualifying(long seed) {
        return ctx(true, true, false, false, true, seed);
    }

    // ---- the predicate, term by term ----

    @Test
    void everyTermHoldingYieldsTheFormattedSubKey() {
        assertEquals(Optional.of("world-000000000000002a"),
                WorldSubKey.subKey(qualifying(42L)));
    }

    @Test
    void eachFailingTermYieldsNoSubKey() {
        assertTrue(WorldSubKey.subKey(ctx(false, true, false, false, true, 42L)).isEmpty(),
                "switch off");
        assertTrue(WorldSubKey.subKey(ctx(true, false, false, false, true, 42L)).isEmpty(),
                "not a remote server");
        assertTrue(WorldSubKey.subKey(ctx(true, true, true, false, true, 42L)).isEmpty(),
                "realm");
        assertTrue(WorldSubKey.subKey(ctx(true, true, false, true, true, 42L)).isEmpty(),
                "integrated server running");
        assertTrue(WorldSubKey.subKey(ctx(true, true, false, false, false, 42L)).isEmpty(),
                "seed unreadable");
        assertTrue(WorldSubKey.subKey(qualifying(0L)).isEmpty(),
                "seed 0 — the no-real-world sentinel (NanoLimbo-class waiting rooms)");
    }

    // ---- carry-forward: exactly the unreadable shape, nothing else ----

    @Test
    void onlyTheUnreadableSeedCarriesForward() {
        assertTrue(WorldSubKey.carryForward(ctx(true, true, false, false, false, 0L)),
                "readability is the one term whose failure carries the previous sub-key");
        assertFalse(WorldSubKey.carryForward(qualifying(42L)), "a readable seed never carries");
        assertFalse(WorldSubKey.carryForward(qualifying(0L)),
                "seed 0 is a DEFINITIVE no-sub-key answer, not an unreadable one");
        assertFalse(WorldSubKey.carryForward(ctx(false, true, false, false, false, 0L)),
                "switch off is definitive even when the seed is also unreadable");
        assertFalse(WorldSubKey.carryForward(ctx(true, false, false, false, false, 0L)),
                "not-remote is definitive");
        assertFalse(WorldSubKey.carryForward(ctx(true, true, true, false, false, 0L)),
                "realm is definitive");
        assertFalse(WorldSubKey.carryForward(ctx(true, true, false, true, false, 0L)),
                "singleplayer is definitive");
    }

    // ---- the mint format ----

    @Test
    void formatIsFixedWidthLowercaseHex() {
        assertEquals("world-0000000000000001", WorldSubKey.format(1L));
        assertEquals("world-ffffffffffffffff", WorldSubKey.format(-1L));
        assertEquals("world-8000000000000000", WorldSubKey.format(Long.MIN_VALUE));
        assertEquals("world-7fffffffffffffff", WorldSubKey.format(Long.MAX_VALUE));
        assertEquals("world-00000000000000ff", WorldSubKey.format(0xffL));
    }

    @Test
    void mintedNamesMatchTheSweepAnchorAsAComposedTail() {
        for (long seed : new long[] {1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            String composed = WorldSubKey.composeBucket("play.example.com",
                    Optional.of(WorldSubKey.format(seed)));
            assertTrue(WorldSubKey.SIBLING_SUFFIX.matcher(composed).find(),
                    "every minted composed name must sit inside the sweep glob: " + composed);
        }
    }

    @Test
    void theSweepAnchorIsAnchoredAtTheEnd() {
        assertFalse(WorldSubKey.SIBLING_SUFFIX
                        .matcher("a.world-0123456789abcdef.addr").find(),
                "an escaped address component must NOT match the sweep glob");
        assertFalse(WorldSubKey.SIBLING_SUFFIX
                        .matcher("a.world-0123456789ABCDEF").find(),
                "the sweep glob matches only the lowercase spelling this code mints");
        assertFalse(WorldSubKey.SIBLING_SUFFIX.matcher("a.world-0123").find(),
                "short hex is not the reserved tail");
    }

    // ---- composition ----

    @Test
    void composeBucketIsTheComponentAloneOrDotJoined() {
        assertEquals("addr", WorldSubKey.composeBucket("addr", Optional.empty()));
        assertEquals("addr.world-00000000000000ff",
                WorldSubKey.composeBucket("addr", Optional.of(WorldSubKey.format(0xffL))));
    }

    // ---- the reserved-tail escape ----

    @Test
    void reservedTailsAreEscapedCaseInsensitively() {
        assertEquals("x.world-0123456789abcdef.addr",
                WorldSubKey.escapeReservedTail("x.world-0123456789abcdef"));
        assertEquals("x.WORLD-0123456789ABCDEF.addr",
                WorldSubKey.escapeReservedTail("x.WORLD-0123456789ABCDEF"),
                "a hostile spelling may capitalize either half — still escaped");
    }

    @Test
    void ordinaryComponentsPassTheEscapeUntouched() {
        assertEquals("play.example.com", WorldSubKey.escapeReservedTail("play.example.com"));
        assertEquals("x.world-0123", WorldSubKey.escapeReservedTail("x.world-0123"),
                "short hex is not reserved");
        assertEquals("x.world-0123456789abcdef.addr",
                WorldSubKey.escapeReservedTail("x.world-0123456789abcdef.addr"),
                "an already-escaped name no longer ends in the tail — stable under re-escape");
    }

    @Test
    void anEscapedComponentCannotOccupyAnotherServersSeededBucket() {
        // The attack the escape closes (plan §9): a server-list entry spelled exactly like
        // victim-addr.world-<hex> must not resolve to the victim's seeded bucket name.
        String victimSeeded = WorldSubKey.composeBucket("victim",
                Optional.of(WorldSubKey.format(0x0123456789abcdefL)));
        String hostileComponent = WorldSubKey.escapeReservedTail(victimSeeded);
        assertFalse(hostileComponent.equals(victimSeeded));
    }
}
