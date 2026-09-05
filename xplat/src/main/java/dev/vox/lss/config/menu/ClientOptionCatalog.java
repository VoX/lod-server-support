package dev.vox.lss.config.menu;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.menu.OptionSpec.BoolSpec;
import dev.vox.lss.config.menu.OptionSpec.IntSpec;
import dev.vox.lss.config.menu.Tooltip.Condition;

import java.util.List;
import java.util.Optional;

/**
 * THE in-game options catalog — the single source of truth for what the LSS settings
 * pages contain (sodium-options-page-generations-plan.md D1). Every renderer walks
 * this list: the Sodium 0.8+ config-API walker ({@code LSSConfigMenu}, fabric), the
 * legacy Sodium 0.6/0.7 reflective builder ({@code LegacySodiumPage}, per-loader
 * twins). Adding or changing an option is an edit HERE and in the lang file — never in
 * a renderer, which is what keeps the pages identical across Sodium generations and
 * makes the change cherry-pick to every support line unchanged.
 *
 * <p>Deliberately MC-free and loader-free (xplat purity): names are translation KEYS,
 * value labels are {@link Label}s, environment facts come in through {@link MenuContext}.
 * The catalog test pins: unique {@code lss:} ids, every key present in {@code en_us.json},
 * defaults equal to a fresh config's fields, binding round-trips, {@code enabledBy}
 * dependencies on the same page, the slider curve inside the validate() clamps, and the
 * far-player page's push hook.
 *
 * <p>Page/option ORDER is display order and is part of the shipped shape (n12: the
 * main page first, far players second).
 */
public final class ClientOptionCatalog {

    public static final String ID_RECEIVE_SERVER_LODS = "lss:receive_server_lods";
    public static final String ID_LOD_DISTANCE = "lss:lod_distance";
    public static final String ID_COLUMN_RATE_LIMIT = "lss:column_rate_limit";
    public static final String ID_JOIN_SLOW_START = "lss:join_slow_start";
    public static final String ID_XAERO_MAP_BRIDGE = "lss:xaero_map_bridge";
    public static final String ID_FAR_PLAYERS_ENABLED = "lss:far_players_enabled";
    public static final String ID_FAR_PLAYERS_SHARE_SELF = "lss:far_players_share_self";
    public static final String ID_FAR_PLAYERS_NAME_TAGS = "lss:far_players_name_tags";
    public static final String ID_FAR_PLAYERS_FULL_BRIGHT = "lss:far_players_full_bright";
    public static final String ID_FAR_PLAYERS_RENDER_DISTANCE = "lss:far_players_render_distance";
    public static final String ID_FAR_PLAYERS_WITH_SEEU = "lss:far_players_with_seeu";

    public static final String PAGE_GENERAL = "general";
    public static final String PAGE_FAR_PLAYERS = "far_players";

    private static final List<PageSpec> PAGES = List.of(generalPage(), farPlayersPage());

    private ClientOptionCatalog() {
    }

    /** The pages in display order. Immutable. */
    public static List<PageSpec> pages() {
        return PAGES;
    }

    public static Optional<OptionSpec> find(String id) {
        return PAGES.stream().flatMap(p -> p.options().stream())
                .filter(o -> o.id().equals(id)).findFirst();
    }

    private static PageSpec generalPage() {
        // Receive Server LODs — the master toggle every other main-page option hangs off.
        var receive = BoolSpec.builder(ID_RECEIVE_SERVER_LODS)
                .name("lss.config.receive_server_lods")
                .tooltip("lss.config.receive_server_lods.tooltip")
                .impact(Impact.HIGH)
                .defaultValue(true)
                .bind(c -> c.receiveServerLods, (c, v) -> c.receiveServerLods = v)
                .build();

        // LOD Distance: 0 = the server's distance. No impact line (the shipped shape).
        var distance = IntSpec.builder(ID_LOD_DISTANCE)
                .name("lss.config.lod_distance")
                .tooltip("lss.config.lod_distance.tooltip")
                .defaultValue(0)
                .range(0, LSSConstants.MAX_LOD_DISTANCE, 1)
                .label(v -> v == 0 ? Label.key("lss.config.lod_distance.server_default") : Label.number(v))
                .bind(c -> c.lodDistanceChunks, (c, v) -> c.lodDistanceChunks = v)
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Max LOD download rate — the manual column-rate cap
        // (docs/planning/client-column-rate-cap-design.md). The slider is CURVED
        // (2026-08-14 granularity request): the option's int is an INDEX into
        // RateSliderStops.STOPS, not the rate, so the low end steps by 10 (a user can
        // pick 20) while the top stays reachable in one drag. Slider top = 3200 because
        // that is where the mechanism provably no-ops (800-budget batches space to
        // exactly the 5-tick fast floor); larger hand-edited values are legal and inert
        // (they display snapped to the top stop but are only rewritten if the user
        // actually moves THIS slider — Sodium writes only modified options). Every
        // nonzero stop round-trips the validate() clamp unchanged: the lowest stop equals
        // the [10, 100000] floor by construction (ConfigValidationTest pins it).
        var rate = IntSpec.builder(ID_COLUMN_RATE_LIMIT)
                .name("lss.config.column_rate_limit")
                .tooltip("lss.config.column_rate_limit.tooltip")
                .impact(Impact.LOW)
                .defaultValue(0)
                .range(0, RateSliderStops.STOPS.length - 1, 1)
                .label(idx -> idx == 0
                        ? Label.key("lss.config.column_rate_limit.unlimited")
                        : Label.number(RateSliderStops.STOPS[idx]))
                .bind(c -> RateSliderStops.nearestIndex(c.lodColumnsPerSecondLimit),
                        (c, idx) -> c.lodColumnsPerSecondLimit = RateSliderStops.STOPS[idx])
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Slow Start on Join (join-slow-start-plan.md, user direction: toggle in the
        // menu, default enabled). Inert while the enableAdaptiveTransferRate umbrella
        // is off (config-file-only key) — the tooltip says so when that is the case
        // at menu build (the SeeU conditional-tooltip precedent).
        var slowStart = BoolSpec.builder(ID_JOIN_SLOW_START)
                .name("lss.config.join_slow_start")
                .tooltip(Tooltip.conditional(Condition.GOVERNOR_ON,
                        "lss.config.join_slow_start.tooltip",
                        "lss.config.join_slow_start.tooltip.governor_off"))
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.enableJoinSlowStart, (c, v) -> c.enableJoinSlowStart = v)
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Xaero's World Map bridge (issue #223, xaero-map-bridge-plan.md §2.9): write
        // received LODs into Xaero's map. Checked LIVE (flip applies mid-session); with
        // Xaero absent the toggle is inert — say so where the user is looking.
        var xaero = BoolSpec.builder(ID_XAERO_MAP_BRIDGE)
                .name("lss.config.xaero_map_bridge")
                .tooltip(Tooltip.conditional(Condition.XAERO_PRESENT,
                        "lss.config.xaero_map_bridge.tooltip",
                        "lss.config.xaero_map_bridge.tooltip.not_installed"))
                .impact(Impact.LOW)
                .defaultValue(false)
                .bind(c -> c.enableXaeroMapBridge, (c, v) -> c.enableXaeroMapBridge = v)
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        return PageSpec.of(PAGE_GENERAL, "lss.config.page",
                GroupSpec.of(receive),
                GroupSpec.of(distance),
                GroupSpec.of(rate, slowStart),
                GroupSpec.of(xaero));
    }

    // ---- Far players (E2, FARP §3.3): its own page — a distinct feature with its own
    //      privacy semantics, not another LOD slider. Every option here persists AND
    //      pushes the prefs NOW (E2 review M2/m4) — SaveHook.SAVE_AND_PUSH_FAR_PLAYER_PREFS.
    //      The renderer-only options are RENDER_AVAILABLE-gated (hidden wherever a loader/line's
    //      render path is a stub; on THIS line both loaders render since v0.14.0, so they show
    //      on NeoForge too); "Share My Position" is the prefs carrier and is never hidden —
    //      a client's opt-out must stay deliverable even where nothing renders.
    private static PageSpec farPlayersPage() {
        var push = SaveHook.SAVE_AND_PUSH_FAR_PLAYER_PREFS;

        // With SeeU installed the coexist gate overrides this toggle — say so where the
        // user is looking (E3, the plan §6 discoverability requirement).
        var enabled = BoolSpec.builder(ID_FAR_PLAYERS_ENABLED)
                .name("lss.config.far_players_enabled")
                .tooltip(Tooltip.conditional(Condition.SEEU_ABSENT,
                        "lss.config.far_players_enabled.tooltip",
                        "lss.config.far_players_enabled.tooltip.seeu"))
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.farPlayersEnabled, (c, v) -> c.farPlayersEnabled = v)
                .saveHook(push)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        // "Share your position with other players' LOD view" — the E2 defaults
        // decision's wording obligation (decisions log 2026-08-13): plain words, no
        // jargon, because default-true at server-default-on means installing = sharing.
        var share = BoolSpec.builder(ID_FAR_PLAYERS_SHARE_SELF)
                .name("lss.config.far_players_share_self")
                .tooltip("lss.config.far_players_share_self.tooltip")
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.farPlayersShareSelf, (c, v) -> c.farPlayersShareSelf = v)
                .saveHook(push)
                .build();

        var tags = BoolSpec.builder(ID_FAR_PLAYERS_NAME_TAGS)
                .name("lss.config.far_players_name_tags")
                .tooltip("lss.config.far_players_name_tags.tooltip")
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.farPlayersNameTags, (c, v) -> c.farPlayersNameTags = v)
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(push)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        // Full-bright proxies (far-player-render-hardening-plan.md WI-2): render-only, so it
        // is NOT a capability-bit term — but it rides the page-uniform push hook like every
        // far-players row (pinned; the prefs changed-guard makes the push a no-op).
        var fullBright = BoolSpec.builder(ID_FAR_PLAYERS_FULL_BRIGHT)
                .name("lss.config.far_players_full_bright")
                .tooltip("lss.config.far_players_full_bright.tooltip")
                .impact(Impact.LOW)
                .defaultValue(false)
                .bind(c -> c.farPlayersFullBright, (c, v) -> c.farPlayersFullBright = v)
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(push)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        var render = IntSpec.builder(ID_FAR_PLAYERS_RENDER_DISTANCE)
                .name("lss.config.far_players_render_distance")
                .tooltip("lss.config.far_players_render_distance.tooltip")
                .impact(Impact.LOW)
                .defaultValue(0)
                .range(0, 16384, 128)
                .label(v -> v == 0 ? Label.key("lss.config.far_players_render_distance.server") : Label.number(v))
                .bind(c -> c.farPlayersMaxRenderDistanceBlocks, (c, v) -> c.farPlayersMaxRenderDistanceBlocks = v)
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(push)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        // The coexist override — only meaningful (and only shown) with SeeU installed.
        var withSeeU = BoolSpec.builder(ID_FAR_PLAYERS_WITH_SEEU)
                .name("lss.config.far_players_with_seeu")
                .tooltip("lss.config.far_players_with_seeu.tooltip")
                .impact(Impact.LOW)
                .defaultValue(false)
                .bind(c -> c.farPlayersWithSeeU, (c, v) -> c.farPlayersWithSeeU = v)
                .saveHook(push)
                .visibility(Visibility.SEEU_ONLY)
                .build();

        return PageSpec.of(PAGE_FAR_PLAYERS, "lss.config.far_players.page",
                GroupSpec.of(enabled, share, tags, fullBright, render, withSeeU));
    }
}
