package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.config.JsonConfig;

public class LSSClientConfig extends JsonConfig {
    // Brand-primary first, other brand as fallback: the VSS jar (Brand.shortName()=="VSS") prefers
    // vss-client-config.json but adopts an existing lss-client-config.json; the LSS jar is the
    // mirror. So a fresh install gets the brand's own file, and an LSS<->VSS jar swap keeps its
    // config. Brand is loaded by the entrypoint before this class is first touched, so it reads
    // the correct brand for the running jar. See JsonConfig.brandedConfigCandidates.
    private static final String[] CANDIDATES = brandedConfigCandidates("client");

    public static LSSClientConfig CONFIG =
            load(LSSClientConfig.class, CANDIDATES, dev.vox.lss.platform.LoaderServices.get().configDir());

    // Region-major want-set scanning (docs/planning/region-scan-plan.md): the scanner
    // walks 32x32-chunk REGIONS in a spiral, completing each region before advancing, so
    // every downstream consumer (Xaero map regions, server region files, the timestamp
    // cache's tiles, region-summary tiles) sees one or two active regions instead of ~95
    // at far radius — the structural fix for far-distance Xaero map tile drops. False =
    // the legacy chunk-ring scanner, verbatim (the field A/B lever and instant rollback).
    // Applied at manager construction: a flip takes effect at the next join/SessionConfig.
    public boolean enableRegionScan = true;

    public boolean receiveServerLods = true;
    // The two-axis cache key's WORLD AXIS (docs/planning/cache-alias-keying-and-
    // reset-override-plan.md §2.3): a remote session's column-stamp cache bucket gains
    // a ".world-<16 lowercase hex>" suffix derived from vanilla's obfuscated seed (the
    // sha256-derived value every vanilla login already carries — never the raw seed),
    // so ONE ADDRESS whose world changes — a pre-generated map reset, a multi-world
    // rotation, a proxy backend switch — stops smearing every world's stamps into one
    // bucket (a pre-generated reset otherwise leaves stale "up to date" terrain that
    // never self-heals by wall clock). Default TRUE: (address, seed) can only be FINER
    // than any consumer partition derivable from the address — stock Voxy's own
    // partition is exactly this pair — so the safe direction holds without opt-in.
    // The sub-key derives fresh at every session build and dimension entry; seedless
    // sessions (waiting rooms sending seed 0, single-player, Realms, an unreadable
    // accessor) keep the bare address bucket. The first seeded session per address
    // adopts an existing bare bucket once (warm upgrade); false = bare legacy naming
    // for NEW state, but NOT a rollback lever after adoption — the adopted bucket
    // stays under its world name and a switched-off session starts cold.
    public boolean useWorldSubBuckets = true;
    // The two-axis cache key's ALIAS AXIS (plan §2.2): groups of addresses that name
    // ONE server (proxy networks reachable at several hostnames), e.g.
    //   "cacheAddressAliases": [["play.example.com", "alt.example.com", "eu.example.com"]]
    // The FIRST entry of a group is canonical: every member connection shares the
    // canonical entry's cache bucket, so the world cold-fills once instead of once per
    // entry address. Matching is trim+lowercase only — no default-port strip (SRV
    // resolution can make a.com and a.com:25565 different servers) — and the canonical
    // must be port-free (voxy-extra substitutes it verbatim into a directory name).
    // GUARDED per connection: with the Voxy ingest bridge active the group applies only
    // when Voxy's own storage root corroborates it (voxy-extra's LoD Mirror configured
    // with the same group, first entries identical) — an alias applied to LSS's stamps
    // alone would leave them COARSER than Voxy's per-address store, a permanent hole.
    // While the Xaero map bridge is armed the group never applies (Xaero has no alias
    // support). Fallback is always the typed address: finer, never wrong. Malformed
    // groups drop whole with one warning at load.
    public java.util.List<java.util.List<String>> cacheAddressAliases = new java.util.ArrayList<>();
    public int lodDistanceChunks = 0;
    // The §3 unknown-identity fallback ladder's TERMINAL default (protocol 20,
    // cross-version-identity-encoding-plan.md): a v20 identity this client's registries
    // don't know renders as this block. Never air — air punches holes in distant
    // terrain, so an air-resolving value is coerced to stone at resolve time.
    public String unknownBlockFallback = "minecraft:stone";
    // The ladder's CURATED rung: removed/renamed block NAMES mapped to visually-similar
    // local ones (ViaBackwards-diff style, e.g. "minecraft:sulfur" -> "minecraft:sandstone").
    // Ships EMPTY; user-extendable as real cross-version gaps are reported.
    public java.util.Map<String, String> crossVersionBlockFallbacks = new java.util.HashMap<>();
    // Backward compat with pre-v0.7.0 (protocol 16, v0.4.x–v0.6.2) SERVERS: if a server does
    // not answer the v18 handshake, re-handshake announcing version 16 and decode the legacy
    // wire. Default true (mirrors the server's enableV16Compat). Post-C3 this gates only the LADDER'S
    // 16 RUNG — a strict current-version client (no fallback at all) needs this AND
    // enableV19ServerCompat false. See docs/planning/v16-client-compat-design.md.
    public boolean enableV16ServerCompat = true;
    // Backward compat with protocol-19 (v0.9.x) SERVERS — the C3 discovery ladder's middle
    // rung (XVER §6): if a server does not answer the version-20 handshake within 5 s,
    // re-announce 19 before falling further to 16. A 19 session is today's decode retained
    // (source + codec bytes, native-id palettes against this client's own registry — same MC
    // version by construction). Default true; false skips straight to the v16 rung.
    public boolean enableV19ServerCompat = true;
    // Tier B of the same compat: on a v16 SERVER, drive on-demand GENERATION of cold columns
    // instead of load-only (Tier A). The egress rewrites a first-serve request to v16's generate
    // trigger, so the old server generates terrain the player has not visited. Default TRUE — this
    // is how a native protocol-16 client behaved (the old servers were built to have LOD clients
    // drive generation), so it is the faithful default and gives the full LOD experience. Verified
    // safe across the compat range: both v0.4.0 and v0.6.2 read disk-FIRST for the generate path
    // (already-generated terrain is served, not regenerated), and the transient-NOT_GENERATED heal
    // that keeps cold backfill filling is client-side (version-independent). Set false for
    // strict load-only (Tier A). No effect unless enableV16ServerCompat is also on, and never on a
    // v18 session. See docs/planning/v16-client-compat-design.md §4 (Tier B).
    public boolean enableV16Generation = true;
    // Adaptive scan cadence (docs/planning/adaptive-scan-cadence-design.md): when the
    // current want-set declaration is ≥95% answered and the pipeline is at most mildly
    // loaded, declare again after 250 ms instead of waiting out the full second (max 4 Hz;
    // the 1 Hz cadence stays as the fallback and remains the sole self-heal for silent
    // server-side drops). Removes the "map fills in one-second spurts" pattern against
    // fast/warm servers. Kill switch: false restores the fixed 1 Hz cadence everywhere.
    // No effect on legacy (protocol-16) server sessions — those always keep 1 Hz.
    public boolean enableAdaptiveScanCadence = true;
    // Scan prefix retention (docs/planning/scanner-reopened-rings-plan.md): keep the
    // scanner's confirmed-ring prefix across chunk crossings and dirty re-opens, re-walking
    // only the affected rings (a reopened-ring bitset) instead of the whole disc. Removes
    // the per-crossing full-disc classify walk — the measured render-thread hitch at large
    // LOD distances (30-90 ms every 2-3 s while moving at distance 512). Kill switch:
    // false restores the legacy collapse-to-ring-0 semantics on every reset path — the
    // field A/B lever, and the safety net for any retained-state hole (a silently orphaned
    // position self-heals under legacy semantics on the next crossing).
    public boolean enableScanPrefixRetention = true;
    // Section-store ring fast path (docs/planning/quadtree-client-state-plan.md): the
    // per-column state lives in dense 8×8-chunk section leaves carrying a derived
    // needs-mask, and the ring walk confirms a fully-satisfied ring by checking the
    // leaves it crosses (O(perimeter/8) flag reads) instead of classifying its positions
    // one by one. Turns the residual full-disc walks over SATISFIED terrain — teleports,
    // LOD-distance changes, dirty-valve overflows — from a 30-90 ms render hitch into
    // ~1.5-3 ms. (The post-cache-load hitch is fixed separately by the off-thread leaf
    // build — adopted stamps are revalidation NEEDS and never fast-skip.) Emission, ordering, and cadence are bit-identical to the
    // per-position walk by construction (differential-test pinned). Kill switch: false
    // restores the per-position walk verbatim — the field A/B lever. NOTE this gates the
    // WALK only; the section-backed state store itself is not switchable. Orthogonal to
    // enableScanPrefixRetention (the prefix/reopened-ring machinery runs identically in
    // both modes).
    public boolean enableQuadtreeScan = true;
    // Region-summary sync (region-summary-sync-plan.md §6): one ~6 KB exchange at
    // dimension entry validates the clean bulk of the cached disc (validated bits set
    // per-column against the server's per-region freshness stamps), so a warm rejoin
    // stops re-declaring ~1M positions over minutes. false = never request, never apply
    // — exactly the pre-summary per-column revalidation. There is no persisted client
    // state, so a flip can poison nothing.
    public boolean enableRegionSummarySync = true;
    // Xaero's World Map bridge (issue #223, docs/planning/xaero-map-bridge-plan.md): write
    // received LOD columns into Xaero's World Map so the map records terrain far beyond
    // vanilla render distance. Inert without the xaeroworldmap mod; checked LIVE (the Sodium
    // toggle applies mid-session — flipping off clears the bridge's queue and, on the next
    // handshake, releases the voxel-columns capability bit an Xaero-only install held).
    // Default OFF for v0.12.0 (user decision 2026-08-23): opt-in while the feature is new —
    // map writes are persistent saved data, so the surprise default is the cautious one.
    public boolean enableXaeroMapBridge = false;
    // §12 Xaero ingest backpressure (hybrid-scan-plan.md §12): the bridge reports its
    // queue occupancy through the issue-#71 consumer machinery, so the want-set tapers
    // and LOD arrival self-paces to what the map writer can commit — the map completes
    // on the first pass instead of shedding overflow drops (~10-15% slower fill while
    // the map is catching up). false = ungoverned (pre-§12 behavior: the bridge queue
    // sheds at its cap). Composes under enableIngestBackpressure; consulted only while
    // the bridge itself is enabled; inert without the mod.
    public boolean enableXaeroMapBackpressure = true;
    // Ingest-pressure request pacing (issue #71, docs/planning/ingest-backpressure-design.md):
    // scale the want-set budget down — and halt declarations entirely at a threshold — when a
    // registered LOD consumer reports a pending ingest backlog (Voxy's ingest queue depth via
    // the reflective bridge). Keeps a weak client from being fed faster than it can absorb.
    // Kill switch: false restores pre-#71 pacing (decode-queue signal only). No effect when no
    // consumer reports a backlog.
    public boolean enableIngestBackpressure = true;
    // Manual column-rate cap (docs/planning/client-column-rate-cap-design.md): bounds how many
    // LOD columns per second the server streams to this client — the MANUAL override for the
    // cases the automatic #71 taper cannot see (pressure showing up as frame time/GC rather
    // than queue depth, or a consumer that never reports an ingest backlog). Denominated in
    // columns/sec, NOT want-set size: a raw budget clamp is compensated away by the adaptive
    // cadence (smaller batches complete sooner and fire faster — budget x cadence is the
    // sustained rate). Nonzero R clamps the want-set budget to min(WANT_SET_BUDGET, R) (bounds
    // the burst) AND spaces fast re-scans so each declared batch pre-pays its interval (bounds
    // the sustained rate at R/sec); the 1 Hz fallback — the want-set's only self-heal — is
    // never gated. Composes with the #71 taper by MIN. Below 776 the want-set no longer
    // dominates the server's worst-case in-flight set: the frontier advances behind its own
    // awaited positions across more scans — slower, never wedged; that is the dial doing its
    // job. On v16 server sessions only the budget clamp applies (no fast path there). Also a
    // Sodium settings slider ("Max LOD Download Rate"). 0 (and any non-positive value) = OFF,
    // bit-identical to pre-cap behavior.
    public int lodColumnsPerSecondLimit = 0;
    // The client transfer governor (docs/planning/adaptive-transfer-rate-plan.md,
    // Mechanism A): on a congested slow link — detected by the client's own tab-list
    // ping rising >250 ms over its session baseline while LOD delivery measures under
    // 4 MB/s — an AIMD loop paces LOD downloads BELOW link capacity through the same
    // machinery as lodColumnsPerSecondLimit (min-composes with it; the manual knob
    // stays a hard bound). Fast links never reach the congestion AIMD (they ramp
    // open in ~35 s under join slow start below); a governed session logs one INFO.
    // Kill switch: false = manual-knob-only, exactly the pre-governor shape.
    public boolean enableAdaptiveTransferRate = true;
    // Join slow start (join-slow-start-plan.md, user decision 2026-08-14 — join
    // latency beats LOD fill speed): sessions START in the governor's RAMP phase
    // (64 KB/s, doubling per proven 2 s interval, open in ~35 s on a fast link)
    // instead of uncapped-until-congestion-evidence. Under the
    // enableAdaptiveTransferRate umbrella: governor off => no ramp either. Also a
    // Sodium toggle ("Slow Start on Join"). false = sessions start uncapped, the
    // pre-slow-start shape.
    public boolean enableJoinSlowStart = true;

    // ---- Far players (v0.11.0, FARP §3.3 — ARMED since E2 via the client
    // ---- capability bit; these keys exist so upgrading users can pre-configure).

    /** Receive far-player proxies (the RENDERER's master toggle). Deliberately NOT a
     *  term of the capability bit (E2 review M2): the subscription doubles as the
     *  prefs carrier for the shareSelf opt-out, so a disabled viewer still
     *  subscribes — it just renders nothing and the server skips serving it. */
    public boolean farPlayersEnabled = true;
    /** Client-side visibility ring overrides in blocks (0 = server-controlled). */
    public int farPlayersMaxDistanceBlocks = 0;
    public int farPlayersMinDistanceBlocks = 0;
    /** Render name tags over proxies. Since 2026-09-04 the renderer draws the tag ITSELF
     *  (far-player-render-hardening-plan.md WI-6): vanilla gates entity name tags at 64
     *  blocks on every MC line and proxies only exist beyond ~127, so the vanilla path could
     *  never draw one — this key was inert until then. */
    public boolean farPlayersNameTags = true;
    /** Light far players (and their mounts) full-bright instead of the sky-lit floor
     *  (far-player-render-hardening-plan.md WI-2). Default OFF: the floor already keeps a
     *  proxy from ever reading dark, and full-bright at night is a beacon. Render-only —
     *  NOT a capability-bit term and NOT part of the prefs content. */
    public boolean farPlayersFullBright = false;
    /** Share my position with other players' LOD view (target-side privacy — the
     *  server honors it in every mode; opt-in mode REQUIRES it). Default TRUE was
     *  consciously CONFIRMED at the E2 defaults flip (decisions log 2026-08-13):
     *  the server's mode/exclude/permission levers own the policy, and a false
     *  default would hide modded players while vanilla players stay visible —
     *  the inverted outcome. */
    public boolean farPlayersShareSelf = true;
    /** Cap the distance others may see me at, blocks (0 = no extra cap). */
    public int farPlayersShareDistanceBlocks = 0;
    /** Prefer LSS far players even when SeeU is installed (E3 coexist — default
     *  false: SeeU's presence disables the LSS half to avoid double proxies; this is
     *  a SEPARATE key because farPlayersEnabled defaults true and cannot express an
     *  explicit preference). Ignored without SeeU. */
    public boolean farPlayersWithSeeU = false;
    /** Renderer cap in blocks (0 = follow the server ring). The fog-alignment knob:
     *  LSS ships NO fog mixin — align this with your fog if proxies fading at the
     *  horizon bothers you (FARP §3.3 fog stance). */
    public int farPlayersMaxRenderDistanceBlocks = 0;
    /** Walk-animation distance cap in blocks (0 = never animate; animation far out is
     *  invisible sub-pixel work — 512 since 2026-09-04, user decision after the live rig:
     *  256 cut the walk cycle while a proxy was still clearly a walking figure). Deliberately
     *  NO options-page row — a config-file knob. */
    public int farPlayersMaxAnimationDistanceBlocks = 512;

    @Override
    protected String getFileName() {
        return CANDIDATES[0]; // brand-primary (creation target / default when not loaded via load())
    }

    @Override
    public void validate() {
        lodDistanceChunks = Math.clamp(lodDistanceChunks, 0, LSSConstants.MAX_LOD_DISTANCE); // 0 = use server default
        // GSON can null these from a malformed/hand-edited file — restore the defaults
        // (the resolver validates the fallback's RESOLUTION itself, config only carries it).
        if (unknownBlockFallback == null || unknownBlockFallback.isBlank()) {
            unknownBlockFallback = "minecraft:stone";
        }
        if (crossVersionBlockFallbacks == null) {
            crossVersionBlockFallbacks = new java.util.HashMap<>();
        }
        // A null/blank ENTRY (C6 review C-2): GSON maps {"k": null} to a real null
        // value in the map, Map.copyOf at resolver construction then NPEs — inside the
        // decode drain, every tick, with no ingest-failure report: LOD dead for the
        // session off one hand-edited entry. Drop such entries fail-open, warn once.
        if (crossVersionBlockFallbacks.entrySet().removeIf(e ->
                e.getKey() == null || e.getKey().isBlank()
                        || e.getValue() == null || e.getValue().isBlank())) {
            dev.vox.lss.common.LSSLogger.warn("crossVersionBlockFallbacks contained"
                    + " null/blank entries — dropped (fail-open); fix the config file");
        }
        // <= 0 normalizes to 0 (off) — a negative hand-edit means "off", and clamping it to the
        // floor would silently ARM a cap the user meant to disable. Positive values clamp to
        // [10, 100000] (floor lowered from 50, 2026-08-14 — a user asked for ~20 col/s, so
        // "no plausible use below 50" was falsified; the machinery is proven far lower — the
        // transfer governor drives the same actuators down to 1 col/s). Below 10 the scanner
        // still functions but starves the frontier to a near-wedge cadence, so single-digit
        // rates stay clamped up; the ceiling is inert (the mechanism no-ops above ~3200) but
        // bounds what a typo can store.
        farPlayersMaxDistanceBlocks = Math.clamp(farPlayersMaxDistanceBlocks, 0, 16384);
        farPlayersMinDistanceBlocks = Math.clamp(farPlayersMinDistanceBlocks, 0,
                farPlayersMaxDistanceBlocks > 0 ? farPlayersMaxDistanceBlocks : 16384);
        farPlayersShareDistanceBlocks = Math.clamp(farPlayersShareDistanceBlocks, 0, 16384);
        farPlayersMaxRenderDistanceBlocks = Math.clamp(farPlayersMaxRenderDistanceBlocks, 0, 16384);
        farPlayersMaxAnimationDistanceBlocks = Math.clamp(farPlayersMaxAnimationDistanceBlocks, 0, 16384);
        if (lodColumnsPerSecondLimit <= 0) {
            lodColumnsPerSecondLimit = 0;
        } else {
            lodColumnsPerSecondLimit = Math.clamp(lodColumnsPerSecondLimit, 10, 100_000);
        }
        // Alias-axis validation (cache-alias-keying-and-reset-override-plan.md §2.2):
        // a malformed group drops WHOLE with one warning per load (the
        // crossVersionBlockFallbacks fail-open convention). The FIELD is deliberately
        // NOT rewritten to the survivors (panel fix): the config re-saves on load, so
        // a rewrite would permanently ERASE the user's dropped groups from their file
        // with one scrolled warning as the only trace — session code re-validates the
        // raw field silently instead (validated() is deterministic and cheap).
        if (cacheAddressAliases == null) {
            cacheAddressAliases = new java.util.ArrayList<>();
        }
        dev.vox.lss.networking.client.CacheKeyAliases.validated(
                cacheAddressAliases, dev.vox.lss.common.LSSLogger::warn);
    }
}
