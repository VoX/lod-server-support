package dev.vox.lss.config.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vox.lss.config.LSSClientConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The options catalog's contract (sodium-options-page-generations-plan.md §4) — the
 * pins that used to live in nobody's test because the page was hand-built per Sodium
 * API: unique {@code lss:} ids; EVERY translation key the catalog can ever resolve to is
 * in {@code en_us.json} (tooltips through {@link Tooltip#keys()}, labels over the whole
 * slider domain); each option's declared default EQUALS a fresh config's field value
 * (the v0.11/v0.12 pages duplicated defaults by hand with no pin); bindings round-trip;
 * {@code enabledBy} names a tick box on the SAME page; the far-player page pushes prefs
 * on save (the E2 review's M2 as data); the SeeU override is the one hidden option.
 */
class ClientOptionCatalogTest {

    private static JsonObject lang;

    @BeforeAll
    static void loadLang() throws IOException {
        lang = JsonParser.parseString(Files.readString(
                locate("fabric/src/main/resources/assets/lss/lang/en_us.json"))).getAsJsonObject();
    }

    @Test
    void idsAreUniqueAndNamespaced() {
        Set<String> seen = new HashSet<>();
        for (OptionSpec o : allOptions()) {
            assertTrue(o.id().startsWith("lss:"), o.id());
            assertTrue(seen.add(o.id()), "duplicate option id " + o.id());
        }
        Set<String> pages = new HashSet<>();
        for (PageSpec p : ClientOptionCatalog.pages()) {
            assertTrue(pages.add(p.id()), "duplicate page id " + p.id());
        }
        assertEquals(2, ClientOptionCatalog.pages().size(), "the main page and far players (n12 order)");
        assertEquals(ClientOptionCatalog.PAGE_GENERAL, ClientOptionCatalog.pages().get(0).id(),
                "the main LSS page comes first");
    }

    @Test
    void everyTranslationKeyExistsInTheLangFile() {
        List<String> missing = new ArrayList<>();
        for (PageSpec p : ClientOptionCatalog.pages()) {
            if (!lang.has(p.titleKey())) missing.add(p.titleKey());
            for (OptionSpec o : p.options()) {
                if (!lang.has(o.nameKey())) missing.add(o.nameKey());
                for (String k : o.tooltip().keys()) {
                    if (!lang.has(k)) missing.add(k);
                }
                if (o instanceof OptionSpec.IntSpec s) {
                    for (int v = s.min(); v <= s.max(); v += s.step()) {
                        Label l = s.label().apply(v);
                        if (l.isKey() && !lang.has(l.key())) missing.add(l.key() + " (value " + v + ")");
                    }
                    assertFalse(s.label().apply(s.max()).isKey() && !lang.has(s.label().apply(s.max()).key()));
                }
            }
        }
        assertTrue(missing.isEmpty(), "catalog keys missing from en_us.json: " + missing);
    }

    /** Pins the KEY SETS only — a value copied verbatim from English still passes; translation
     *  coverage is a human job. */
    @Test
    void everyLangFileCarriesTheSameKeys() throws IOException {
        // The zh files are REAL translations, not English copies, and nothing else pins their
        // parity (release_check compares LSS vs VSS within ONE locale) — a line port that
        // drops a zh key would otherwise fall back to the raw key on screen.
        for (String locale : new String[] {"zh_cn", "zh_tw"}) {
            JsonObject other = JsonParser.parseString(Files.readString(
                    locate("fabric/src/main/resources/assets/lss/lang/" + locale + ".json"))).getAsJsonObject();
            assertEquals(lang.keySet(), other.keySet(), locale + ".json must carry exactly en_us.json's keys");
        }
    }

    @Test
    void declaredDefaultsEqualAFreshConfig() {
        var fresh = new LSSClientConfig();
        for (OptionSpec o : allOptions()) {
            Object expected = switch (o) {
                case OptionSpec.BoolSpec b -> b.defaultValue();
                case OptionSpec.IntSpec i -> i.defaultValue();
            };
            assertEquals(expected, o.read(fresh),
                    o.id() + ": the catalog default must equal LSSClientConfig's field initializer"
                            + " (through the option's own getter)");
        }
    }

    @Test
    void bindingsRoundTrip() {
        var cfg = new LSSClientConfig();
        for (OptionSpec o : allOptions()) {
            switch (o) {
                case OptionSpec.BoolSpec b -> {
                    b.setter().accept(cfg, !b.defaultValue());
                    assertEquals(!b.defaultValue(), b.getter().apply(cfg), o.id());
                    b.setter().accept(cfg, b.defaultValue());
                    assertEquals(b.defaultValue(), b.getter().apply(cfg), o.id());
                }
                case OptionSpec.IntSpec i -> {
                    for (int v : new int[]{i.min(), i.max(), i.min() + ((i.max() - i.min()) / (2 * i.step())) * i.step()}) {
                        i.setter().accept(cfg, v);
                        assertEquals(v, i.getter().apply(cfg), o.id() + " at " + v);
                    }
                }
            }
        }
    }

    @Test
    void enabledByNamesATickBoxOnTheSamePage() {
        for (PageSpec p : ClientOptionCatalog.pages()) {
            Set<String> ids = new HashSet<>();
            p.options().forEach(o -> ids.add(o.id()));
            for (OptionSpec o : p.options()) {
                if (o.enabledBy() == null) continue;
                assertTrue(ids.contains(o.enabledBy()),
                        o.id() + " depends on " + o.enabledBy() + " which is not on page " + p.id());
                assertTrue(ClientOptionCatalog.find(o.enabledBy()).orElseThrow() instanceof OptionSpec.BoolSpec,
                        o.id() + ": enabledBy must name a tick box");
            }
        }
        // The shipped shape: every main-page option except the master toggle hangs off it.
        for (OptionSpec o : ClientOptionCatalog.pages().get(0).options()) {
            if (o.id().equals(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS)) continue;
            assertEquals(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS, o.enabledBy(), o.id());
        }
    }

    @Test
    void farPlayerPagePushesPrefsAndTheMainPageOnlySaves() {
        for (OptionSpec o : ClientOptionCatalog.pages().get(0).options()) {
            assertEquals(SaveHook.SAVE, o.saveHook(), o.id());
        }
        for (OptionSpec o : ClientOptionCatalog.pages().get(1).options()) {
            assertEquals(SaveHook.SAVE_AND_PUSH_FAR_PLAYER_PREFS, o.saveHook(),
                    o.id() + ": a mid-session far-player flip must push prefs now (E2 review M2)");
        }
    }

    @Test
    void exactlyTheSeeuOverrideAndTheRendererOnlyOptionsAreConditional() {
        var seeuOnly = allOptions().stream().filter(o -> o.visibility() == Visibility.SEEU_ONLY).map(OptionSpec::id).toList();
        assertEquals(List.of(ClientOptionCatalog.ID_FAR_PLAYERS_WITH_SEEU), seeuOnly);
        var renderOnly = allOptions().stream().filter(o -> o.visibility() == Visibility.RENDER_AVAILABLE).map(OptionSpec::id).toList();
        assertEquals(List.of(ClientOptionCatalog.ID_FAR_PLAYERS_ENABLED, ClientOptionCatalog.ID_FAR_PLAYERS_NAME_TAGS,
                ClientOptionCatalog.ID_FAR_PLAYERS_FULL_BRIGHT, ClientOptionCatalog.ID_FAR_PLAYERS_RENDER_DISTANCE), renderOnly,
                "the four renderer-only options hide where nothing renders (NeoForge v1)");
        assertEquals(Visibility.ALWAYS, ClientOptionCatalog.find(ClientOptionCatalog.ID_FAR_PLAYERS_SHARE_SELF).orElseThrow().visibility(),
                "Share My Position is the prefs carrier — never hidden");
        assertTrue(Visibility.SEEU_ONLY.test(new MenuContext(true, false, true, true)));
        assertFalse(Visibility.SEEU_ONLY.test(new MenuContext(true, true, false, true)));
        assertTrue(Visibility.RENDER_AVAILABLE.test(new MenuContext(false, false, false, true)));
        assertFalse(Visibility.RENDER_AVAILABLE.test(new MenuContext(true, true, true, false)));
    }

    @Test
    void theShippedShapeIsPinned() {
        // ids, in display order (the 0.8+ API keys per-option state by id — never renumber)
        assertEquals(List.of("lss:receive_server_lods", "lss:lod_distance", "lss:column_rate_limit",
                        "lss:join_slow_start", "lss:xaero_map_bridge", "lss:far_players_enabled",
                        "lss:far_players_share_self", "lss:far_players_name_tags",
                        "lss:far_players_full_bright", "lss:far_players_render_distance",
                        "lss:far_players_with_seeu"),
                allOptions().stream().map(OptionSpec::id).toList());
        // group shape + titles
        assertEquals(List.of(1, 1, 2, 1), ClientOptionCatalog.pages().get(0).groups().stream().map(g -> g.options().size()).toList());
        assertEquals(List.of(6), ClientOptionCatalog.pages().get(1).groups().stream().map(g -> g.options().size()).toList());
        assertEquals("lss.config.page", ClientOptionCatalog.pages().get(0).titleKey());
        assertEquals("lss.config.far_players.page", ClientOptionCatalog.pages().get(1).titleKey());
        // impacts: HIGH on the master toggle, none on the LOD-distance slider, LOW elsewhere
        for (OptionSpec o : allOptions()) {
            Impact expected = switch (o.id()) {
                case "lss:receive_server_lods" -> Impact.HIGH;
                case "lss:lod_distance" -> null;
                default -> Impact.LOW;
            };
            assertEquals(expected, o.impact(), o.id());
        }
        // ranges — and Sodium's own SliderControl invariant: (max - min) % step == 0, which a
        // violation turns into a thrown page build (both tabs lost, not one slider)
        var distance = (OptionSpec.IntSpec) ClientOptionCatalog.find("lss:lod_distance").orElseThrow();
        assertEquals(List.of(0, dev.vox.lss.common.LSSConstants.MAX_LOD_DISTANCE, 1), List.of(distance.min(), distance.max(), distance.step()));
        var render = (OptionSpec.IntSpec) ClientOptionCatalog.find("lss:far_players_render_distance").orElseThrow();
        assertEquals(List.of(0, 16384, 128), List.of(render.min(), render.max(), render.step()));
        for (OptionSpec o : allOptions()) {
            if (o instanceof OptionSpec.IntSpec i) {
                assertEquals(0, (i.max() - i.min()) % i.step(), o.id() + ": Sodium requires (max-min) divisible by step");
            }
        }
    }

    @Test
    void theRateSliderCurveStaysInsideTheClamp() {
        var rate = (OptionSpec.IntSpec) ClientOptionCatalog.find(ClientOptionCatalog.ID_COLUMN_RATE_LIMIT).orElseThrow();
        assertEquals(0, rate.min());
        assertEquals(RateSliderStops.STOPS.length - 1, rate.max());
        assertEquals(0, RateSliderStops.STOPS[0], "stop 0 is OFF");
        assertEquals(10, RateSliderStops.STOPS[1], "the lowest nonzero stop IS the validate() floor");
        assertEquals(3200, RateSliderStops.STOPS[RateSliderStops.STOPS.length - 1], "the mechanism no-ops above 3200");
        for (int i = 1; i < RateSliderStops.STOPS.length; i++) {
            assertTrue(RateSliderStops.STOPS[i] > RateSliderStops.STOPS[i - 1], "stops strictly increase");
        }
        var cfg = new LSSClientConfig();
        for (int i = 0; i < RateSliderStops.STOPS.length; i++) {
            rate.setter().accept(cfg, i);
            cfg.validate();
            assertEquals(RateSliderStops.STOPS[i], cfg.lodColumnsPerSecondLimit, "stop " + i + " survives validate()");
            assertEquals(i, rate.getter().apply(cfg), "stop " + i + " reads back as itself");
        }
        assertTrue(rate.label().apply(0).isKey(), "0 displays as the Unlimited key");
        assertEquals("10", rate.label().apply(1).literal());
    }

    @Test
    void conditionalTooltipsFlipWithTheContext() {
        var slow = ClientOptionCatalog.find(ClientOptionCatalog.ID_JOIN_SLOW_START).orElseThrow();
        assertEquals("lss.config.join_slow_start.tooltip", slow.tooltip().resolve(new MenuContext(true, false, false, true)));
        assertEquals("lss.config.join_slow_start.tooltip.governor_off", slow.tooltip().resolve(new MenuContext(false, false, false, true)));
        var xaero = ClientOptionCatalog.find(ClientOptionCatalog.ID_XAERO_MAP_BRIDGE).orElseThrow();
        assertEquals("lss.config.xaero_map_bridge.tooltip", xaero.tooltip().resolve(new MenuContext(true, true, false, true)));
        assertEquals("lss.config.xaero_map_bridge.tooltip.not_installed", xaero.tooltip().resolve(new MenuContext(true, false, false, true)));
        var fp = ClientOptionCatalog.find(ClientOptionCatalog.ID_FAR_PLAYERS_ENABLED).orElseThrow();
        assertEquals("lss.config.far_players_enabled.tooltip", fp.tooltip().resolve(new MenuContext(true, false, false, true)));
        assertEquals("lss.config.far_players_enabled.tooltip.seeu", fp.tooltip().resolve(new MenuContext(true, false, true, true)));
        assertEquals(2, slow.tooltip().keys().size());
        assertEquals(1, ClientOptionCatalog.find(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS).orElseThrow().tooltip().keys().size());
    }

    @Test
    void theLiveContextResolvesInAUnitJvm() {
        // Loader-less/config-less contexts must read as "absent", never throw (the
        // renderers call this on the render thread).
        MenuContext ctx = MenuContext.current();
        assertNotNull(ctx);
        assertFalse(ctx.xaeroPresent());
        assertFalse(ctx.seeuPresent());
    }

    private static List<OptionSpec> allOptions() {
        return ClientOptionCatalog.pages().stream().flatMap(p -> p.options().stream()).toList();
    }

    static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative);
    }
}
