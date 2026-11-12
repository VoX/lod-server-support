package dev.vox.lss.config.menu;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vox.lss.common.config.SettingsStore;
import dev.vox.lss.common.config.SettingsSchema;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The options catalog's contract (sodium-options-page-generations-plan.md §4) — the
 * pins that used to live in nobody's test because the page was hand-built per Sodium
 * API: unique {@code lss:} ids; EVERY translation key the catalog can ever resolve to is
 * in {@code en_us.json} (tooltips through {@link Tooltip#keys()}, labels over the whole
 * slider domain); each option's declared default EQUALS a fresh config's field value
 * (the v0.11/v0.12 pages duplicated defaults by hand with no pin); bindings round-trip;
 * {@code enabledBy} names a tick box in the shared catalog; the far-player page pushes prefs
 */
class ClientOptionCatalogTest {
    @Test
    void menuApplyPreservesFileOnlySynchronizationAndDefersFallbackPermissionToTheNextPhysicalConnection() throws Exception {
        var schema=SettingsSchema.client();
        var store=new SettingsStore<>(temp,"lss",schema);
        store.initialize();
        store.saveDraft(store.read().hash(), java.util.Map.of("lod.dh.sync_on_load", false, "lod.dh.real_time_updates", false));
        try(var handle=new dev.vox.lss.common.config.SettingsHandle<>(store)) {
            Object connection=new Object();handle.beginSession(connection);
            var reloads=new java.util.concurrent.atomic.AtomicInteger();
            var cfg=new ClientSettingsEditSession(store,()->handle.state().effective(),()->handle.state().configured(),()->{
                try { handle.commit(handle.prepareReload());reloads.incrementAndGet(); }
                catch(IOException failure) { throw new AssertionError(failure); }
            });
            var source=(OptionSpec.BoolSpec)ClientOptionCatalog.find(ClientOptionCatalog.ID_LOD_SOURCE).orElseThrow();
            assertTrue(source.defaultValue());assertTrue(source.getter().apply(cfg),"DH server terrain is allowed by default");
            source.setter().accept(cfg,false);
            assertTrue(cfg.apply());assertEquals(1,reloads.get());
            assertEquals("lss",store.read().configured().lod().source());
            assertEquals("distant_horizons",handle.state().effective().lod().source());
            assertFalse(handle.state().effective().lod().dh().syncOnLoad());
            assertFalse(handle.state().effective().lod().dh().realTimeUpdates());
            assertEquals(Set.of("lod.source"),handle.state().pendingReconnect());
            handle.beginSession(connection);assertEquals("distant_horizons",handle.state().effective().lod().source());
            handle.endSession(connection);handle.beginSession(new Object());
            assertEquals("lss",handle.state().effective().lod().source());
            assertTrue(handle.state().pendingReconnect().isEmpty());
        }
    }

    @org.junit.jupiter.api.io.TempDir Path temp;
    private ClientSettingsEditSession draft() {
        try {
            var store = new SettingsStore<>(temp, "lss", SettingsSchema.client());
            store.initialize();
            return new ClientSettingsEditSession(store, SettingsSchema.client()::defaults);
        } catch (IOException failure) { throw new AssertionError(failure); }
    }


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
        assertEquals(List.of(ClientOptionCatalog.PAGE_GENERAL, ClientOptionCatalog.PAGE_FAR_PLAYERS),
                ClientOptionCatalog.pages().stream().map(PageSpec::id).toList());
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
        var fresh = draft();
        for (OptionSpec o : allOptions()) {
            Object expected = switch (o) {
                case OptionSpec.BoolSpec b -> b.defaultValue();
                case OptionSpec.IntSpec i -> i.defaultValue();
            };
            assertEquals(expected, o.read(fresh),
                    o.id() + ": the catalog default must equal schema default"
                            + " (through the option's own getter)");
        }
    }

    @Test
    void bindingsRoundTrip() {
        var cfg = draft();
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
    void enabledByNamesATickBoxOnTheSameOrAnEarlierPage() {
        var pages = ClientOptionCatalog.pages();
        for (int page = 0; page < pages.size(); page++) {
            Set<String> available = new HashSet<>();
            for (int earlier = 0; earlier <= page; earlier++)
                pages.get(earlier).options().forEach(o -> available.add(o.id()));
            for (OptionSpec o : pages.get(page).options()) {
                if (o.enabledBy() == null) continue;
                assertTrue(available.contains(o.enabledBy()), o.id() + ": dependency must be on this or an earlier page");
                assertTrue(ClientOptionCatalog.find(o.enabledBy()).orElseThrow() instanceof OptionSpec.BoolSpec,
                        o.id() + ": enabledBy must name a tick box");
            }
        }
        // All General options follow the reception master, including DH fallback.
        for (PageSpec page : pages.subList(0, 1)) {
            for (OptionSpec o : page.options()) {
                if (o.id().equals(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS)) continue;
                assertTrue(ClientOptionCatalog.enabledDependencies(o).contains(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS), o.id());
            }
        }
    }

    @Test
    void everyPageSavesOnlyADiskDraft() {
        for (OptionSpec o : allOptions())
            assertEquals(SaveHook.SAVE, o.saveHook(), o.id() + ": every page shares the disk-only transaction");
    }

    @Test
    void rendererOnlyOptionsAreConditionalAndSharingAlwaysVisible() {
        var renderOnly = allOptions().stream().filter(o -> o.visibility() == Visibility.RENDER_AVAILABLE).map(OptionSpec::id).toList();
        assertEquals(List.of(ClientOptionCatalog.ID_FAR_PLAYERS_ENABLED, ClientOptionCatalog.ID_FAR_PLAYERS_NAME_TAGS,
                ClientOptionCatalog.ID_FAR_PLAYERS_FULL_BRIGHT, ClientOptionCatalog.ID_FAR_PLAYERS_RENDER_DISTANCE,
                ClientOptionCatalog.ID_FAR_PLAYERS_EXTEND_FAR_PLANE), renderOnly);
        assertEquals(Visibility.ALWAYS, ClientOptionCatalog.find(ClientOptionCatalog.ID_FAR_PLAYERS_SHARE_SELF).orElseThrow().visibility());
        assertTrue(Visibility.RENDER_AVAILABLE.test(new MenuContext(false, false, true)));
        assertFalse(Visibility.RENDER_AVAILABLE.test(new MenuContext(true, true, false)));
    }

    @Test
    void theShippedShapeIsPinned() {
        // ids, in display order (the 0.8+ API keys per-option state by id — never renumber)
        assertEquals(List.of("lss:receive_server_lods", "lss:lod_distance", "lss:column_rate_limit",
                        "lss:join_slow_start", "lss:map_ingestion", "lss:lod_source", "lss:far_players_enabled",
                        "lss:far_players_share_self", "lss:far_players_name_tags",
                        "lss:far_players_full_bright", "lss:far_players_render_distance",
                        "lss:far_players_extend_far_plane"),
                allOptions().stream().map(OptionSpec::id).toList());
        // group shape + titles
        assertEquals(List.of(1, 1, 2, 2), ClientOptionCatalog.pages().get(0).groups().stream().map(g -> g.options().size()).toList());
        assertEquals(List.of(6), ClientOptionCatalog.pages().get(1).groups().stream().map(g -> g.options().size()).toList());
        assertEquals("lss.config.page", ClientOptionCatalog.pages().get(0).titleKey());
        assertEquals("lss.config.far_players.page", ClientOptionCatalog.pages().get(1).titleKey());
        // impacts: HIGH on the master toggle, none on the LOD-distance slider, LOW elsewhere
        for (OptionSpec o : allOptions()) {
            Impact expected = switch (o.id()) {
                case "lss:receive_server_lods", "lss:lod_source" -> Impact.HIGH;
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
        var cfg = draft();
        for (int i = 0; i < RateSliderStops.STOPS.length; i++) {
            rate.setter().accept(cfg, i);
            assertEquals(RateSliderStops.STOPS[i], cfg.integer("lod.download.max_columns_per_second"), "stop " + i + " survives validate()");
            assertEquals(i, rate.getter().apply(cfg), "stop " + i + " reads back as itself");
        }
        assertTrue(rate.label().apply(0).isKey(), "0 displays as the Unlimited key");
        assertEquals("10", rate.label().apply(1).literal());
    }

    @Test
    void conditionalTooltipsFlipWithTheContext() {
        var slow = ClientOptionCatalog.find(ClientOptionCatalog.ID_JOIN_SLOW_START).orElseThrow();
        assertEquals("lss.config.join_slow_start.tooltip", slow.tooltip().resolve(new MenuContext(true, false, true)));
        assertEquals("lss.config.join_slow_start.tooltip.governor_off", slow.tooltip().resolve(new MenuContext(false, false, true)));
        var xaero = ClientOptionCatalog.find(ClientOptionCatalog.ID_MAP_INGESTION).orElseThrow();
        assertEquals("lss.config.map_ingestion.tooltip", xaero.tooltip().resolve(new MenuContext(true, true, true)));
        assertEquals("lss.config.map_ingestion.tooltip.not_installed", xaero.tooltip().resolve(new MenuContext(true, false, true)));
        assertEquals("lss.config.map_ingestion.tooltip.native_only", xaero.tooltip().resolve(new MenuContext(true, true, true, false)));
        assertEquals("lss.config.map_ingestion.tooltip.native_only", xaero.tooltip().resolve(new MenuContext(false, false, true, false)));
        assertEquals("lss.config.join_slow_start.tooltip", slow.tooltip().resolve(new MenuContext(true, true, true, false)));
        assertEquals(4, xaero.tooltip().keys().size(), "on, not installed, native-only, server-blocked");
        var fp = ClientOptionCatalog.find(ClientOptionCatalog.ID_FAR_PLAYERS_ENABLED).orElseThrow();
        assertEquals("lss.config.far_players_enabled.tooltip", fp.tooltip().resolve(new MenuContext(true, false, true)));
        assertEquals(2, slow.tooltip().keys().size());
        assertEquals(1, ClientOptionCatalog.find(ClientOptionCatalog.ID_RECEIVE_SERVER_LODS).orElseThrow().tooltip().keys().size());
    }

    /** Map-policy plan A4: "Turned off by this server" is the map toggle's THIRD
     *  display-time condition — the connected server's request, read when the tooltip
     *  shows (every renderer resolves through {@code resolveAtDisplay} or a page-open
     *  {@code MenuContext.current()}); DH mode still wins (no native session to block),
     *  and the local setting is untouched. */
    @Test
    void theMapToggleSaysTurnedOffByThisServerWhileTheServerDenies() {
        var maps = ClientOptionCatalog.find(ClientOptionCatalog.ID_MAP_INGESTION).orElseThrow().tooltip();
        var blocked = new MenuContext(true, true, true, true, true);
        assertEquals("lss.config.map_ingestion.tooltip.server_blocked", maps.resolve(blocked));
        assertEquals("lss.config.map_ingestion.tooltip.not_installed",
                maps.resolve(new MenuContext(true, false, true, true, true)),
                "the status ladder's order (review fold A5): with no map mod installed the "
                        + "status reads ABSENT, so the tooltip says not installed too");
        assertEquals("lss.config.map_ingestion.tooltip.native_only",
                maps.resolve(new MenuContext(true, true, true, false, true)), "DH mode wins");
        assertEquals("lss.config.map_ingestion.tooltip",
                maps.resolve(new MenuContext(true, true, true, true, false)));
        assertTrue(maps.resolvesAtDisplay());
        assertEquals("lss.config.map_ingestion.tooltip.server_blocked",
                maps.resolveAtDisplay(new MenuContext(true, true, true, true, false), () -> blocked),
                "a modern page registered before the join reads the live server fact");
        var slow = ClientOptionCatalog.find(ClientOptionCatalog.ID_JOIN_SLOW_START).orElseThrow().tooltip();
        assertEquals("lss.config.join_slow_start.tooltip", slow.resolve(blocked),
                "only the map toggle carries the server condition");
        assertFalse(MenuContext.all().mapIngestionServerBlocked());
    }

    @Test
    void theLiveContextResolvesInAUnitJvm() {
        // Loader-less/config-less contexts must read as "absent", never throw (the
        // renderers call this on the render thread).
        MenuContext ctx = MenuContext.current();
        assertNotNull(ctx);
        assertFalse(ctx.mapModSupported());
        assertFalse(ctx.mapIngestionServerBlocked(), "no connection never claims a server block");
    }

    private static List<OptionSpec> allOptions() {
        return ClientOptionCatalog.pages().stream().flatMap(p -> p.options().stream()).toList();
    }

    private static List<ActionSpec> allActions() {
        return ClientOptionCatalog.pages().stream().flatMap(p -> p.actions().stream()).toList();
    }

    // ---- actions: the pages' buttons. Not options and not settings (re-download plan §2.1). ----

    @Test
    void theShippedActionsArePinned() {
        assertEquals(List.of("lss:redownload_terrain"),
                ClientOptionCatalog.pages().get(0).actions().stream().map(ActionSpec::id).toList(),
                "the General page carries the one button");
        assertTrue(ClientOptionCatalog.pages().get(1).actions().isEmpty(), "Far Players has no buttons");
        ActionSpec redownload = ClientOptionCatalog.pages().get(0).actions().get(0);
        assertEquals(ClientOptionCatalog.ID_REDOWNLOAD_TERRAIN, redownload.id());
        assertEquals(ActionSpec.Action.REDOWNLOAD_TERRAIN, redownload.action());
        assertEquals("lss.config.redownload_terrain", redownload.nameKey());
        assertEquals("lss.config.redownload_terrain.tooltip", redownload.tooltipKey());
    }

    /** Sodium 0.8+ keeps an external button in the same map as real options, and the draft
     *  refresh passes every key of that map through {@code find}: an action that resolved
     *  there would be treated as a setting with a value. */
    @Test
    void actionIdsAreUniqueNamespacedAndNeverResolveAsOptions() {
        Set<String> optionIds = new HashSet<>();
        allOptions().forEach(o -> optionIds.add(o.id()));
        Set<String> seen = new HashSet<>();
        for (ActionSpec a : allActions()) {
            assertTrue(a.id().startsWith("lss:"), a.id());
            assertTrue(seen.add(a.id()), "duplicate action id " + a.id());
            assertFalse(optionIds.contains(a.id()), a.id() + " is also an option id");
            assertTrue(ClientOptionCatalog.find(a.id()).isEmpty(), a.id() + ": find(id) is options-only");
        }
        assertFalse(seen.isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new ActionSpec("redownload_terrain", "name", "tooltip", ActionSpec.Action.REDOWNLOAD_TERRAIN),
                "an action id without the lss: namespace is refused");
    }

    /** Sodium refuses a blank name or tooltip when it builds the page, after the mod's entry
     *  point has returned, and that failure stops the game. The key-set test above only proves
     *  the keys exist; this proves each locale gives them text. */
    @Test
    void everyActionNameAndTooltipHasTextInEveryLocale() throws IOException {
        for (String locale : new String[] {"en_us", "zh_cn", "zh_tw"}) {
            JsonObject file = JsonParser.parseString(Files.readString(
                    locate("fabric/src/main/resources/assets/lss/lang/" + locale + ".json"))).getAsJsonObject();
            for (ActionSpec a : allActions()) {
                for (String key : List.of(a.nameKey(), a.tooltipKey())) {
                    assertTrue(file.has(key), locale + ".json lacks " + key);
                    assertFalse(file.get(key).getAsString().isBlank(), locale + ".json: " + key + " is blank");
                }
            }
        }
    }

    /** The VSS jar rewrites "LSS" in lang VALUES but not a lowercase command, so the button and
     *  dialog text must never spell one: the command root is a {@code %s} argument filled from
     *  the brand. Scoped to this feature's keys. */
    @Test
    void theRedownloadTextSpellsNoCommandRoot() throws IOException {
        var commandRoot = java.util.regex.Pattern.compile("/(?:lss|vss)\\b");
        int checked = 0;
        for (String locale : new String[] {"en_us", "zh_cn", "zh_tw"}) {
            JsonObject file = JsonParser.parseString(Files.readString(
                    locate("fabric/src/main/resources/assets/lss/lang/" + locale + ".json"))).getAsJsonObject();
            for (String key : file.keySet()) {
                if (!key.startsWith("lss.config.redownload_terrain") && !key.startsWith("lss.redownload.")) continue;
                checked++;
                assertFalse(commandRoot.matcher(file.get(key).getAsString()).find(),
                        locale + ".json: " + key + " names a command root; use /%s");
            }
        }
        assertTrue(checked >= 6, "the scan must see the button's keys in all three locales");
    }

    /** D-25: display-time resolution reads the current context for every context-dependent
     *  tooltip and the registration context for fixed ones; both menu twins call it. */
    @Test
    void contextDependentTooltipsResolveAgainstTheCurrentContext() throws Exception {
        var registered = new MenuContext(true, true, true, true);
        var later = new MenuContext(false, true, true, false);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        for (var page : ClientOptionCatalog.pages()) for (var group : page.groups()) for (var option : group.options()) {
            var tooltip = option.tooltip();
            String shown = tooltip.resolveAtDisplay(registered, () -> { calls.incrementAndGet(); return later; });
            assertEquals(tooltip.resolve(tooltip.resolvesAtDisplay() ? later : registered), shown, option.id());
        }
        assertTrue(calls.get() >= 2, "governor and native-only tooltips read the live context");
        var xaero = ClientOptionCatalog.find(ClientOptionCatalog.ID_MAP_INGESTION).orElseThrow().tooltip();
        assertTrue(xaero.resolvesAtDisplay());
        assertEquals("lss.config.map_ingestion.tooltip.native_only", xaero.resolveAtDisplay(registered, () -> later));
        for (String twin : List.of("fabric/src/main/java/dev/vox/lss/config/LSSConfigMenu.java",
                "neoforge/src/main/java/dev/vox/lss/config/LSSConfigMenu.java"))
            assertTrue(Files.readString(locate(twin)).contains("resolveAtDisplay(ctx, MenuContext::current)"), twin);
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
