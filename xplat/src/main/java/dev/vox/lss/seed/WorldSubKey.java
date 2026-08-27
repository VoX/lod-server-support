package dev.vox.lss.seed;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The WORLD AXIS of the two-axis cache key
 * (docs/planning/cache-alias-keying-and-reset-override-plan.md §2.1/§2.3): a remote
 * session's stamp-cache bucket is {@code <addressComponent>[.world-<16 hex>]} — the
 * address axis picks the server's own namespace, and this class decides the optional
 * world SUB-key inside it. Reworked from PR #243's {@code WorldSeedKey}: the pure
 * {@code Context} predicate shape survives; the seed's POSITION changed from "the whole
 * identity" to "a sub-partition under the address" (plan §1.2 — under the address a
 * server-chosen seed can only sub-partition that server's OWN namespace, so the forged-
 * seed cross-server reach, the shared-seed collision, and the coarser-than-Voxy hazard
 * all become unrepresentable), which is also why the {@code liveLssSession} replay guard
 * term was retired with the seed-named deletion it existed to protect (§2.3's recorded
 * walk).
 *
 * <p>PURE BY CONSTRUCTION — no Minecraft, no IO, no state. Everything arrives as a
 * {@link Context} of plain values; {@link ClientWorldSeed} is the half that reads those
 * values off the live client.
 *
 * <h2>The predicate</h2>
 * A sub-key exists only when <b>the switch is on AND the connection is a remote server
 * AND it is not a Realm AND no integrated server is running AND the seed was readable
 * AND the seed is not 0</b>. Anything else leaves the bare address bucket — the
 * fallback IS the pre-plan layout, byte for byte.
 *
 * <ul>
 *   <li><b>switch</b> ({@code useWorldSubBuckets}, default true): the world axis needs
 *       no corroboration guard because {@code (address, seed)} can only be FINER than
 *       any consumer partition derivable from the address (plan §2.3) — stock Voxy's
 *       own partition is {@code (address, worldid(seed, dim))}, exactly this pair.</li>
 *   <li><b>remote / not a Realm / no integrated server</b>: single-player worlds have
 *       seeds, and "there is a seed, use it" would silently re-partition every
 *       single-player cache; Realm addresses are ephemeral per-session handles.</li>
 *   <li><b>seed readable</b>: the {@code @Accessor} may be absent (a loader whose mixin
 *       config missed it) or {@code Minecraft.level} may be null. An unreadable read at
 *       a mid-session derive point CARRIES the previous sub-key forward
 *       ({@link #carryForward}); at first build it degrades to the bare bucket.</li>
 *   <li><b>seed != 0</b>: what a server with no real world sends — NanoLimbo-class
 *       waiting rooms call {@code setSeed(0L)} literally. Seedless sessions read and
 *       write the bare bucket and never adopt (plan §2.3).</li>
 * </ul>
 *
 * <h2>The suffix namespace is a contract</h2>
 * {@link #KEY_FORMAT} (lowercase fixed-width hex under {@link Locale#ROOT}) is
 * sanitize-invariant by construction, so the composed bucket name survives
 * {@code ColumnCacheStore.sanitizeForFilePath} verbatim. {@link #SIBLING_SUFFIX} is the
 * reset sweep's anchored glob over entry NAMES, and {@link #escapeReservedTail} keeps a
 * hostile or odd RAW server-list entry out of the reserved namespace: an address whose
 * sanitized form ends in {@code .world-<16 hex>} (case-insensitively) must not occupy
 * another server's seeded-bucket name — writes there would poison the victim's stamps —
 * nor sit inside its reset glob (plan §2.1/§9).
 */
public final class WorldSubKey {

    /**
     * The sub-key directory-name fragment: {@code world-} + 16 lowercase hex digits,
     * zero-padded. Fixed width makes the names sort and compare as a block; already
     * {@code sanitizeForFilePath}-safe by construction (only {@code [a-z0-9-]}) — a
     * property the tests assert, not an excuse to skip sanitising.
     */
    public static final String KEY_FORMAT = "world-%016x";

    /**
     * The reserved bucket-name TAIL as this class mints it — the reset sweep's anchor
     * over cache-dir entry names (plan §2.4: anchored, entry names only, lowercase hex
     * because that is the only spelling ever minted).
     */
    public static final Pattern SIBLING_SUFFIX = Pattern.compile("\\.world-[0-9a-f]{16}$");

    /** The same tail case-insensitively — the {@link #escapeReservedTail} check (a raw
     *  address is arbitrary user/server input and may spell the hex either case). */
    private static final Pattern RESERVED_TAIL_ANY_CASE =
            Pattern.compile("(?i)\\.world-[0-9a-f]{16}$");

    private WorldSubKey() {
    }

    /** {@link #KEY_FORMAT} applied under {@link Locale#ROOT}. */
    public static String format(long seed) {
        return String.format(Locale.ROOT, KEY_FORMAT, seed);
    }

    /**
     * Everything the derivation depends on, as plain values.
     *
     * @param subBucketsEnabled the {@code useWorldSubBuckets} switch
     * @param remoteServer      a remote server entry exists ({@code getCurrentServer()}
     *                          non-null with an address)
     * @param realm             that entry is a Realm
     * @param singleplayer      an integrated server is running
     * @param seedAvailable     the obfuscated seed could actually be read
     * @param seed              the seed read (meaningless when {@code !seedAvailable})
     */
    public record Context(boolean subBucketsEnabled,
                          boolean remoteServer,
                          boolean realm,
                          boolean singleplayer,
                          boolean seedAvailable,
                          long seed) {

        /** The no-sub-keys context — the default for rigs that never wire a live read. */
        public static Context disabled() {
            return new Context(false, false, false, false, false, 0L);
        }
    }

    /**
     * The sub-key this derivation yields: present iff every predicate term holds,
     * empty otherwise (bare bucket). Never latched by callers across a READABLE
     * different answer — plan §2.1's fresh-per-derive rule is what keeps the partition
     * as fine as Voxy's across backend switches and multi-world rotations.
     */
    public static Optional<String> subKey(Context c) {
        if (!c.subBucketsEnabled()) return Optional.empty();
        if (!c.remoteServer()) return Optional.empty();
        if (c.realm()) return Optional.empty();
        if (c.singleplayer()) return Optional.empty();
        if (!c.seedAvailable()) return Optional.empty();
        if (c.seed() == 0L) return Optional.empty();
        return Optional.of(format(c.seed()));
    }

    /**
     * True when the ONLY failing term is readability: the session would sub-key if the
     * seed could be read, so a mid-session derive point keeps its PREVIOUS sub-key
     * rather than silently dropping to the bare bucket (plan §2.1 — "the previous
     * sub-key carries forward ONLY when the fresh read is unreadable, never across a
     * readable different seed"). Every definitive answer — switch off, single-player,
     * Realm, seed 0 — returns false and replaces the previous sub-key with
     * {@link #subKey}'s (possibly empty) fresh answer.
     */
    public static boolean carryForward(Context c) {
        return c.subBucketsEnabled()
                && c.remoteServer()
                && !c.realm()
                && !c.singleplayer()
                && !c.seedAvailable();
    }

    /** The composed FLAT bucket name: the address component alone, or
     *  {@code <component>.<sub-key>}. */
    public static String composeBucket(String addressComponent, Optional<String> subKey) {
        return subKey.map(k -> addressComponent + "." + k).orElse(addressComponent);
    }

    /**
     * Escapes a SANITIZED address component that ends in the reserved
     * {@code .world-<16 hex>} tail (case-insensitively) by appending {@code .addr}: the
     * escaped name can no longer match the anchored sweep glob, and it cannot collide
     * with any seeded bucket this class mints (those always end in the bare tail). One
     * append suffices — the appended literal ends the name outside the reserved
     * namespace whatever precedes it.
     */
    public static String escapeReservedTail(String sanitizedComponent) {
        return RESERVED_TAIL_ANY_CASE.matcher(sanitizedComponent).find()
                ? sanitizedComponent + ".addr" : sanitizedComponent;
    }
}
