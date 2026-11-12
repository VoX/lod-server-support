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
 * this list: the Sodium 0.8+ config-API walker ({@code LSSConfigMenu}, per-loader
 * twins), the legacy Sodium 0.6/0.7 reflective builder ({@code LegacySodiumPage},
 * per-loader twins). Adding or changing an option is an edit HERE and in the lang file — never in
 * a renderer, which is what keeps the pages identical across Sodium generations and
 * makes the change cherry-pick to every support line unchanged.
 *
 * <p>Deliberately MC-free and loader-free (xplat purity): names are translation KEYS,
 * value labels are {@link Label}s, environment facts come in through {@link MenuContext}.
 * The catalog test pins: unique {@code lss:} ids, every key present in {@code en_us.json},
 * defaults equal to a fresh config's fields, binding round-trips, {@code enabledBy}
 * dependencies across the shared catalog, the slider curve inside the validate() clamps, and the
 * shared disk-only storage hook.
 *
 * <p>Page/option ORDER is display order and is part of the shipped shape (n12: the
 * main page first, then far players).
 */
public final class ClientOptionCatalog {

    public static final String ID_RECEIVE_SERVER_LODS = "lss:receive_server_lods";
    public static final String ID_LOD_SOURCE = "lss:lod_source";
    public static final String ID_LOD_DISTANCE = "lss:lod_distance";
    public static final String ID_COLUMN_RATE_LIMIT = "lss:column_rate_limit";
    public static final String ID_JOIN_SLOW_START = "lss:join_slow_start";
    public static final String ID_MAP_INGESTION = "lss:map_ingestion";
    public static final String ID_FAR_PLAYERS_ENABLED = "lss:far_players_enabled";
    public static final String ID_FAR_PLAYERS_SHARE_SELF = "lss:far_players_share_self";
    public static final String ID_FAR_PLAYERS_NAME_TAGS = "lss:far_players_name_tags";
    public static final String ID_FAR_PLAYERS_FULL_BRIGHT = "lss:far_players_full_bright";
    public static final String ID_FAR_PLAYERS_RENDER_DISTANCE = "lss:far_players_render_distance";
    public static final String ID_FAR_PLAYERS_EXTEND_FAR_PLANE = "lss:far_players_extend_far_plane";

    /** A button, not a setting ({@link ActionSpec}): {@link #find} never resolves it. */
    public static final String ID_REDOWNLOAD_TERRAIN = "lss:redownload_terrain";

    public static final String PAGE_GENERAL = "general";
    public static final String PAGE_FAR_PLAYERS = "far_players";


    private static final List<PageSpec> PAGES = List.of(generalPage(), farPlayersPage());

    private ClientOptionCatalog() {
    }

    /** The pages in display order. Immutable. */
    public static List<PageSpec> pages() {
        return PAGES;
    }

    /** Options only. A page's buttons ({@link PageSpec#actions()}) have ids too and never
     *  resolve here: the draft refresh passes every key of Sodium's option map through this. */
    public static Optional<OptionSpec> find(String id) {
        return PAGES.stream().flatMap(p -> p.options().stream())
                .filter(o -> o.id().equals(id)).findFirst();
    }

    /** Complete serialized inventory, including intentionally file-only settings. */
    public static java.util.List<dev.vox.lss.common.config.SettingDescriptor> serializedDescriptors() {
        return dev.vox.lss.common.config.ClientSerializedSettings.descriptors().stream()
                .map(ClientOptionCatalog::withControlMetadata).toList();
    }

    private static dev.vox.lss.common.config.SettingDescriptor withControlMetadata(
            dev.vox.lss.common.config.SettingDescriptor stored) {
        String id = switch (stored.key()) {
            case "lod.receive" -> ID_RECEIVE_SERVER_LODS;
            case "lod.source" -> ID_LOD_SOURCE;
            case "lod.distance_chunks" -> ID_LOD_DISTANCE;
            case "lod.download.max_columns_per_second" -> ID_COLUMN_RATE_LIMIT;
            case "lod.download.slow_start_on_join" -> ID_JOIN_SLOW_START;
            case "integrations.map_ingestion.enabled" -> ID_MAP_INGESTION;
            case "far_players.enabled" -> ID_FAR_PLAYERS_ENABLED;
            case "far_players.sharing.enabled" -> ID_FAR_PLAYERS_SHARE_SELF;
            case "far_players.name_tags" -> ID_FAR_PLAYERS_NAME_TAGS;
            case "far_players.full_bright" -> ID_FAR_PLAYERS_FULL_BRIGHT;
            case "far_players.render_distance_blocks" -> ID_FAR_PLAYERS_RENDER_DISTANCE;
            case "far_players.extend_far_plane" -> ID_FAR_PLAYERS_EXTEND_FAR_PLANE;
            default -> null;
        };
        if (id == null) return stored;
        var option = find(id).orElseThrow();
        return new dev.vox.lss.common.config.SettingDescriptor(stored.key(), stored.type(), stored.units(),
                option.nameKey(), stored.defaultPolicy(), stored.domain(), stored.validationBinding(),
                stored.scopes(), stored.inheritance(), option.visibility().name(),
                "save draft; explicit reload; " + option.saveHook(), false, option.saveHook().name(),
                dev.vox.lss.common.config.SettingDescriptor.Exposure.UI);
    }

    public static java.util.List<dev.vox.lss.common.config.SettingBinding<dev.vox.lss.config.LSSClientConfig>> serializedBindings() {
        return serializedDescriptors().stream().map(descriptor ->
                new dev.vox.lss.common.config.SettingBinding<dev.vox.lss.config.LSSClientConfig>(descriptor,
                        config -> config.snapshot().values().get(descriptor.key()))).toList();
    }

    private static PageSpec generalPage() {
        // Receive Server LODs — the master toggle every other main-page option hangs off.
        var receive = BoolSpec.builder(ID_RECEIVE_SERVER_LODS)
                .name("lss.config.receive_server_lods")
                .tooltip("lss.config.receive_server_lods.tooltip")
                .impact(Impact.HIGH)
                .defaultValue(true)
                .bind(c -> c.bool("lod.receive"), (c, v) -> c.set("lod.receive", v))
                .build();

        // LOD Distance: 0 = the server's distance. No impact line (the shipped shape).
        var distance = IntSpec.builder(ID_LOD_DISTANCE)
                .name("lss.config.lod_distance")
                .tooltip("lss.config.lod_distance.tooltip")
                .defaultValue(0)
                .range(0, LSSConstants.MAX_LOD_DISTANCE, 1)
                .label(v -> v == 0 ? Label.key("lss.config.lod_distance.server_default") : Label.number(v))
                .bind(c -> c.integer("lod.distance_chunks"), (c, v) -> c.set("lod.distance_chunks", v))
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
                .bind(c -> RateSliderStops.nearestIndex(c.integer("lod.download.max_columns_per_second")),
                        (c, idx) -> c.set("lod.download.max_columns_per_second", RateSliderStops.STOPS[idx]))
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Slow Start on Join (join-slow-start-plan.md, user direction: toggle in the
        // menu, default enabled). Inert while the enableAdaptiveTransferRate umbrella
        // is off (config-file-only key) — the tooltip says so when that is the case
        // at menu build.
        var slowStart = BoolSpec.builder(ID_JOIN_SLOW_START)
                .name("lss.config.join_slow_start")
                .tooltip(Tooltip.conditional(Condition.GOVERNOR_ON,
                        "lss.config.join_slow_start.tooltip",
                        "lss.config.join_slow_start.tooltip.governor_off"))
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.bool("lod.download.slow_start_on_join"), (c, v) -> c.set("lod.download.slow_start_on_join", v))
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Map ingestion (map-ingestion plan §2.5): write received LODs into installed map
        // mods (Xaero's World Map, JourneyMap). Apply reloads, then reconnect activates it;
        // on for new settings files. With no supported map mod the toggle is inert — say so
        // where the user is looking.
        var maps = BoolSpec.builder(ID_MAP_INGESTION)
                .name("lss.config.map_ingestion")
                .tooltip(Tooltip.conditional(Condition.MAP_MOD_SUPPORTED,
                        "lss.config.map_ingestion.tooltip",
                        "lss.config.map_ingestion.tooltip.not_installed")
                        .nativeOnly("lss.config.map_ingestion.tooltip.native_only")
                        // Map-policy plan A4: the connected server's request, read when shown.
                        .serverBlocked("lss.config.map_ingestion.tooltip.server_blocked"))
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.bool("integrations.map_ingestion.enabled"), (c, v) -> c.set("integrations.map_ingestion.enabled", v))
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Allow DH server terrain when native LSS is unavailable; reconnect applies it.
        // ON by default (2026-10-10); the experimental sidecar persists only the opt-out.
        // Sync/live preferences stay file-only there.
        var source = BoolSpec.builder(ID_LOD_SOURCE)
                .name("lss.config.lod_source")
                .tooltip("lss.config.lod_source.tooltip")
                .impact(Impact.HIGH)
                .defaultValue(true)
                .bind(c -> "distant_horizons".equals(c.get("lod.source")),
                        (c, useDh) -> c.set("lod.source", useDh ? "distant_horizons" : "lss"))
                .enabledBy(ID_RECEIVE_SERVER_LODS)
                .build();

        // Re-download Terrain (re-download plan, decision 1): what /lss clearcache does, where
        // players look for it. It clears the download records so the server sends the terrain
        // again; terrain the LOD mod already saved stays. Always enabled: the confirm screen
        // says what it will do for the current connection.
        var redownload = new ActionSpec(ID_REDOWNLOAD_TERRAIN,
                "lss.config.redownload_terrain",
                "lss.config.redownload_terrain.tooltip",
                ActionSpec.Action.REDOWNLOAD_TERRAIN);

        return new PageSpec(PAGE_GENERAL, "lss.config.page",
                List.of(GroupSpec.of(receive),
                        GroupSpec.of(distance),
                        GroupSpec.of(rate, slowStart),
                        GroupSpec.of(maps, source)),
                List.of(redownload));
    }

    /** Include ancestors so source-specific controls also follow the reception master. */
    public static java.util.List<String> enabledDependencies(OptionSpec option) {
        var dependencies=new java.util.ArrayList<String>();String id=option.enabledBy();
        while(id!=null && !dependencies.contains(id)) {
            dependencies.add(id);id=find(id).orElseThrow().enabledBy();
        }
        return java.util.List.copyOf(dependencies);
    }

    // ---- Far players (E2, FARP §3.3): its own page — a distinct feature with its own
    //      privacy semantics, not another LOD slider. Every option saves
    //      a draft; explicit reload publishes it.
    //      The renderer-only options are RENDER_AVAILABLE-gated (hidden on NeoForge v1,
    //      whose render path is a stub); "Share My Position" is the prefs carrier and is
    //      never hidden — a NeoForge user's opt-out must stay deliverable.
    private static PageSpec farPlayersPage() {
        var save = SaveHook.SAVE;

        var enabled = BoolSpec.builder(ID_FAR_PLAYERS_ENABLED)
                .name("lss.config.far_players_enabled")
                .tooltip("lss.config.far_players_enabled.tooltip")
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.bool("far_players.enabled"), (c, v) -> c.set("far_players.enabled", v))
                .saveHook(save)
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
                .bind(c -> c.bool("far_players.sharing.enabled"), (c, v) -> c.set("far_players.sharing.enabled", v))
                .saveHook(save)
                .build();

        var tags = BoolSpec.builder(ID_FAR_PLAYERS_NAME_TAGS)
                .name("lss.config.far_players_name_tags")
                .tooltip("lss.config.far_players_name_tags.tooltip")
                .impact(Impact.LOW)
                .defaultValue(true)
                .bind(c -> c.bool("far_players.name_tags"), (c, v) -> c.set("far_players.name_tags", v))
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(save)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        // Full-bright proxies (far-player-render-hardening-plan.md WI-2): render-only, so it
        // is not a capability-bit term. Saving this row never sends preferences.
        var fullBright = BoolSpec.builder(ID_FAR_PLAYERS_FULL_BRIGHT)
                .name("lss.config.far_players_full_bright")
                .tooltip("lss.config.far_players_full_bright.tooltip")
                .impact(Impact.LOW)
                .defaultValue(false)
                .bind(c -> c.bool("far_players.full_bright"), (c, v) -> c.set("far_players.full_bright", v))
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(save)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        var render = IntSpec.builder(ID_FAR_PLAYERS_RENDER_DISTANCE)
                .name("lss.config.far_players_render_distance")
                .tooltip("lss.config.far_players_render_distance.tooltip")
                .impact(Impact.LOW)
                .defaultValue(0)
                .range(0, 16384, 128)
                .label(v -> v == 0 ? Label.key("lss.config.far_players_render_distance.server") : Label.number(v))
                .bind(c -> c.integer("far_players.render_distance_blocks"), (c, v) -> c.set("far_players.render_distance_blocks", v))
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(save)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        // Far-plane extension (far-plane plan #364): render-only and off by default; it lives in
        // the experimental sidecar, so turning it on never makes the main file unreadable by
        // older versions. After Render Limit, which caps it.
        var extendFarPlane = BoolSpec.builder(ID_FAR_PLAYERS_EXTEND_FAR_PLANE)
                .name("lss.config.far_players_extend_far_plane")
                .tooltip("lss.config.far_players_extend_far_plane.tooltip")
                .impact(Impact.LOW)
                .defaultValue(false)
                .bind(c -> c.bool("far_players.extend_far_plane"), (c, v) -> c.set("far_players.extend_far_plane", v))
                .enabledBy(ID_FAR_PLAYERS_ENABLED)
                .saveHook(save)
                .visibility(Visibility.RENDER_AVAILABLE)
                .build();

        return PageSpec.of(PAGE_FAR_PLAYERS, "lss.config.far_players.page",
                GroupSpec.of(enabled, share, tags, fullBright, render, extendFarPlane));
    }
}
