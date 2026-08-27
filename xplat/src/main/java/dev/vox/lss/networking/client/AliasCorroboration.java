package dev.vox.lss.networking.client;

/**
 * The corroboration guard on the ALIAS axis
 * (docs/planning/cache-alias-keying-and-reset-override-plan.md §2.2): a matched
 * {@code cacheAddressAliases} group is APPLIED to a connection only when doing so
 * cannot make LSS's cache partition COARSER than the LOD consumer's — stamps
 * answering "fresh" against an empty consumer store is a permanent hole no self-heal
 * reaches (the design principle from PR #243's own analysis). The WORLD axis needs no
 * such guard (plan §2.3): {@code (address, seed)} can only be finer.
 *
 * <p>PURE — the caller supplies what it observed. The comparison table is transcribed
 * from voxy-extra's ACTUAL LoD Mirror mixin ({@code VoxyClientInstanceMixin}, verified
 * at the v1 plan review — §8's headline MAJOR was specifying this against the wrong
 * artifact): when the mirror rewrites a member connection it substitutes the group's
 * FIRST entry verbatim via {@code path.resolveSibling(listFirst)} (no munge), and when
 * the connection IS the canonical it leaves Voxy's own derivation — the address with
 * {@code :} replaced by {@code _}. Matching is exact and case-sensitive, exactly like
 * the mixin's {@code equals}.
 */
public final class AliasCorroboration {

    private AliasCorroboration() {
    }

    /** Whether the matched group is applied to this connection's cache key. */
    public enum Outcome { APPLY, FALL_BACK }

    /**
     * The evaluation, its diag/log token, and the warning (null on the silent shapes).
     * Fallback is the safe direction: finer costs re-downloads, never holes.
     */
    public record Result(Outcome outcome, String token, String warn) {

        static Result apply(String token) {
            return new Result(Outcome.APPLY, token, null);
        }

        static Result fallBack(String token, String warn) {
            return new Result(Outcome.FALL_BACK, token, warn);
        }
    }

    /** Voxy's own address-to-directory munge ({@code VoxyClientInstance.getBasePath}:
     *  {@code info.ip.replace(":", "_")}) — the observed dir name when the mirror is
     *  inactive or the connection is the canonical itself. */
    public static String voxyMunge(String addr) {
        return addr.replace(":", "_");
    }

    /**
     * The corroboration ladder, in guard order:
     *
     * <ol>
     *   <li>Xaero map bridge ARMED (flag ∧ installed ∧ resolved) → FALL_BACK: Xaero's
     *       world id is per-address and no aliasing exists for it — an applied alias
     *       would be coarser than the map store whatever Voxy says.</li>
     *   <li>No Voxy consumer registered → APPLY as configured (the pure-API user's
     *       documented risk — there is nothing observable to corroborate against).</li>
     *   <li>Voxy present but unprobeable ({@code observedDirName} null) → FALL_BACK
     *       (fail CLOSED — §8's headline fold: "unobservable" must never read as
     *       "corroborated").</li>
     *   <li>The observed dir equals the canonical VERBATIM (voxy-extra's
     *       {@code resolveSibling(listFirst)} substitution) or Voxy's {@code :→_}
     *       munge of it (the canonical-connection shape) → corroborated → APPLY.</li>
     *   <li>The observed dir equals the CONNECT address's munge (and that is not one
     *       of the canonical forms) → Voxy is storing per-address: FALL_BACK with the
     *       actionable warn.</li>
     *   <li>Anything else → FALL_BACK.</li>
     * </ol>
     *
     * @param voxyConsumerRegistered the Voxy ingest bridge registered a consumer
     * @param xaeroBridgeArmed       the Xaero map bridge is armed for this session
     * @param observedDirName        the live Voxy storage root's directory NAME, or
     *                               null when unprobeable
     * @param connectAddr            the raw typed connect address
     * @param canonicalRaw           the matched group's canonical (first) entry, raw
     */
    public static Result evaluate(boolean voxyConsumerRegistered, boolean xaeroBridgeArmed,
                                  String observedDirName, String connectAddr,
                                  String canonicalRaw) {
        if (xaeroBridgeArmed) {
            return Result.fallBack("xaero-armed",
                    "cacheAddressAliases: not applied while the Xaero map bridge is active — "
                            + "Xaero's map store is per-address and has no alias support; the "
                            + "cache stays keyed by the typed address (finer, never wrong)");
        }
        if (!voxyConsumerRegistered) {
            return Result.apply("no-consumer");
        }
        if (observedDirName == null) {
            return Result.fallBack("voxy-unprobeable",
                    "cacheAddressAliases: Voxy is installed but its storage root could not be "
                            + "observed (Voxy may not be running for this session, or its "
                            + "internals drifted) — not applying the alias group (fail-safe; "
                            + "the cache stays keyed by the typed address)");
        }
        if (observedDirName.equals(canonicalRaw)
                || observedDirName.equals(voxyMunge(canonicalRaw))) {
            return Result.apply("voxy-corroborated");
        }
        if (observedDirName.equals(voxyMunge(connectAddr))) {
            return Result.fallBack("voxy-unaliased",
                    "cacheAddressAliases: Voxy is storing this connection per-address ("
                            + observedDirName + ") — install/configure voxy-extra's LoD Mirror "
                            + "with the same group, and make the FIRST entry of both lists "
                            + "identical; until then the cache stays keyed by the typed address");
        }
        return Result.fallBack("voxy-mismatch",
                "cacheAddressAliases: Voxy's storage root (" + observedDirName + ") matches "
                        + "neither the group's canonical nor this connection's address — not "
                        + "applying the alias group (fail-safe)");
    }
}
