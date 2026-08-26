package dev.vox.lss.common.store;

/**
 * The store's registry-identity fingerprint (4-agent round R2-M3 + review A3). Stored
 * wire bytes embed GLOBAL block-state and biome ids, both assignment-order dependent —
 * a mod/datapack change shifts them while region files stay untouched, so only this
 * fingerprint can trigger the drop-and-rebuild. BOTH halves are id-ordered identity
 * hashes: the original block half was a bare COUNT, so an id-permuting registry change
 * of identical total size (a mod swap landing on the same state count) served every
 * warm column as the wrong blocks with no self-heal.
 *
 * <p>Pure string hashing so the platform services stay textual twins and the format +
 * permutation-sensitivity are pinnable without a MinecraftServer
 * ({@code RegistryFingerprintTest}).
 */
public final class RegistryFingerprint {

    private RegistryFingerprint() {}

    /**
     * @param blockStateIdentities every block state's identity string in GLOBAL-ID
     *     order (BlockState toString: block id + property values)
     * @param biomeKeys every biome key string in id order
     * @return {@code bs:<hex>/bio:<hex>} — the format is load-bearing (a bare count
     *     must be un-representable)
     */
    public static String of(Iterable<String> blockStateIdentities, Iterable<String> biomeKeys) {
        return "bs:" + Long.toHexString(fnvOver(blockStateIdentities))
                + "/bio:" + Long.toHexString(fnvOver(biomeKeys));
    }

    /**
     * Order-INSENSITIVE twin of {@link #of} (v0.13.1 —
     * docs/planning/store-registry-permutation-plan.md): the same FNV chain over a
     * SORTED copy of each list. Some mods (VisualWorkbench-class dynamic registration)
     * permute global ids every boot while the identity SET stays fixed; wire-v20 store
     * rows are identity-addressed, so a store proven all-v20 may survive a pure
     * permutation — this hash is that proof's content half. Distinct {@code bsc}/{@code
     * bioc} prefixes keep the two hash kinds un-confusable with {@link #of}'s; the
     * format stays load-bearing (a bare count must be un-representable).
     *
     * @return {@code bsc:<hex>/bioc:<hex>}
     */
    public static String contentOf(Iterable<String> blockStateIdentities,
                                   Iterable<String> biomeKeys) {
        return "bsc:" + Long.toHexString(fnvOver(sorted(blockStateIdentities)))
                + "/bioc:" + Long.toHexString(fnvOver(sorted(biomeKeys)));
    }

    private static Iterable<String> sorted(Iterable<String> items) {
        var copy = new java.util.ArrayList<String>();
        for (String s : items) copy.add(s);
        java.util.Collections.sort(copy);
        return copy;
    }

    private static long fnvOver(Iterable<String> items) {
        long hash = 0xcbf29ce484222325L;
        for (String s : items) {
            for (int i = 0; i < s.length(); i++) {
                hash ^= s.charAt(i);
                hash *= 0x100000001b3L;
            }
            hash ^= ':';
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
