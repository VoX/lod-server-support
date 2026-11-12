package dev.vox.lss.config.menu;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.config.SettingsStore;
import dev.vox.lss.common.config.SettingsSchema;
import net.caffeinemc.mods.sodium.client.gui.options.Option;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy reflective renderer against real-package-name STUBS of Sodium 0.6/0.7's
 * internal options API (the Voxy/Moonrise/Xaero discipline — the stubs under
 * {@code fabric/src/test/java/net/caffeinemc/…} mirror the public surface
 * {@code LegacySodiumPage.SURFACE} resolves, with the REAL staging semantics). Pins
 * (sodium-options-page-generations-plan.md §4): the pages mirror the catalog (order,
 * groups, visibility, brand-prefixed titles), tick box vs slider by kind with the
 * catalog's domain and labels, impact by name, the dependency supplier follows the
 * dependency's STAGED value, the SCREEN's apply contract (apply the changed options,
 * save each distinct storage ONCE — the proxies survive a {@code HashSet}; the push
 * hook fires exactly for the far-player page), and every failure shape degrades to an
 * empty page list.
 */
class LegacySodiumPageTest {
    @Test void selectedTabRemainsReachableAfterAnExtraWidePrecedingTab() {
        assertEquals(2,LegacySodiumPage.tabStart(320,2,List.of(60,220,100)));
        assertEquals(1,LegacySodiumPage.tabStart(341,2,List.of(60,100,100)));
        assertEquals(1,LegacySodiumPage.tabEnd(341,0,List.of(60,240,30,30)));
        assertEquals(4,LegacySodiumPage.tabEnd(341,2,List.of(60,240,30,30)));
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private ClientSettingsEditSession draft() {
        try {
            var store = new SettingsStore<>(temp, "lss", SettingsSchema.client());
            store.initialize();
            return new ClientSettingsEditSession(store, SettingsSchema.client()::defaults);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }


    private static final MenuContext PLAIN = new MenuContext(true, false, true);

    private LegacySodiumPage.Handles handles;

    @BeforeEach
    void resolve() throws ReflectiveOperationException {
        LegacySodiumPage.resetForTests();
        SodiumGeneration.resetForTests();
        OptionImpl.FAIL_BUILD = false;
        handles = LegacySodiumPage.resolve(SodiumGeneration.CAFFEINE_PREFIX, getClass().getClassLoader());
    }

    @AfterEach
    void reset() {
        OptionImpl.FAIL_BUILD = false;
        LegacySodiumPage.resetForTests();
        SodiumGeneration.resetForTests();
    }

    @Test void donationFooterMustNotCoverGeneralOrQualityControls() {
        for(int[] size:new int[][]{{426,240},{341,256},{320,256}}) {
            var general=List.of(new LegacySodiumPage.Bounds(6,size[1]-34,210,20));
            assertFalse(LegacySodiumPage.footerFree(size[0],size[1],124,general));
        }
        assertTrue(LegacySodiumPage.footerFree(426,240,124,List.of()));
    }

    @Test
    void resolvesEverySurfaceMemberAgainstTheStubs() {
        assertNotNull(handles);
        assertFalse(handles.enabledIsStatic(), "0.6+ setEnabled takes a BooleanSupplier");
        assertTrue(handles.storageClass().isInterface());
        assertTrue(handles.formatterClass().isInterface());
    }

    /** 0.7's two setTooltip/1 overloads: the resolver must bind the Component one — the stub
     *  declares the Function overload FIRST and throws if it is ever invoked. */
    @Test
    void theResolverPrefersTheComponentTooltipOverload() throws Throwable {
        assertEquals(net.minecraft.network.chat.Component.class,
                LegacySodiumPage.method(OptionImpl.Builder.class, "setTooltip", 1, false,
                        net.minecraft.network.chat.Component.class).getParameterTypes()[0]);
        // and the whole build survives the decoy (a bound Function overload would throw)
        assertEquals(2, LegacySodiumPage.buildWith(handles, draft(), PLAIN, "LSS", h -> { }).size());
    }

    static class StaticEnabledBuilder {
        public StaticEnabledBuilder setEnabled(boolean enabled) {
            return this;
        }
    }

    /** Sodium 0.5's setEnabled(boolean): detected by parameter type (the static-greying arm). */
    @Test
    void aSodiumZeroPointFiveStyleSetEnabledIsDetectedAsStatic() throws Exception {
        assertEquals(boolean.class, LegacySodiumPage.method(StaticEnabledBuilder.class, "setEnabled", 1, false,
                java.util.function.BooleanSupplier.class).getParameterTypes()[0]);
    }

    @Test
    void hiddenRendererHidesTheRendererOnlyFarPlayerOptions() throws Throwable {
        List<Object> pages = LegacySodiumPage.buildWith(handles, draft(),
                new MenuContext(true, false, false), "LSS", h -> { });
        OptionPage far = (OptionPage) pages.get(1);
        assertEquals(List.of("lss.config.far_players_share_self"),
                far.getOptions().stream().map(o -> o.getName().getString()).toList(),
                "NeoForge v1 (no renderer): only the prefs carrier stays");
    }

    @Test
    void anUnknownPrefixFailsAtResolveNotAtBuild() {
        assertThrows(ClassNotFoundException.class,
                () -> LegacySodiumPage.resolve("me.nobody.mods.sodium", getClass().getClassLoader()));
    }

    @Test
    void pagesMirrorTheCatalogWithBrandedTitles() throws Throwable {
        List<Object> pages = LegacySodiumPage.buildWith(handles, draft(), PLAIN, "LSS", h -> { });
        assertEquals(ClientOptionCatalog.pages().size(), pages.size());
        OptionPage general = (OptionPage) pages.get(0);
        OptionPage far = (OptionPage) pages.get(1);
        assertEquals("LSS", general.getName().getString(), "the first tab is the bare brand");
        assertTrue(far.getName().getString().startsWith("LSS "), "later tabs prefix the brand: " + far.getName().getString());
        assertEquals(4, general.getGroups().size());
        assertEquals(List.of(1, 1, 2, 2), general.getGroups().stream().map(g -> g.getOptions().size()).toList());
        assertEquals(1, far.getGroups().size());
        assertEquals(6, far.getGroups().get(0).getOptions().size(), "six explicit far-player controls");
        // Names in catalog order.
        var expected = ClientOptionCatalog.pages().get(0).options().stream().map(OptionSpec::nameKey).toList();
        assertEquals(expected, general.getOptions().stream().map(o -> o.getName().getString()).toList());
    }

    @Test
    void controlsMatchTheCatalogKinds() throws Throwable {
        List<Object> pages = LegacySodiumPage.buildWith(handles, draft(), PLAIN, "LSS", h -> { });
        OptionPage general = (OptionPage) pages.get(0);
        Option<?> receive = general.getOptions().get(0);
        assertInstanceOf(TickBoxControl.class, receive.getControl());
        assertEquals(OptionImpact.HIGH, receive.getImpact());
        assertEquals(Boolean.TRUE, receive.getValue());

        assertInstanceOf(TickBoxControl.class,general.getOptions().get(5).getControl());

        Option<?> distance = general.getOptions().get(1);
        var slider = assertInstanceOf(SliderControl.class, distance.getControl());
        assertEquals(0, slider.min());
        assertEquals(LSSConstants.MAX_LOD_DISTANCE, slider.max());
        assertEquals(1, slider.interval());
        assertNull(distance.getImpact(), "the LOD-distance slider carries no impact line (shipped shape)");
        assertEquals("lss.config.lod_distance.server_default", slider.formatter().format(0).getString());
        assertEquals("5", slider.formatter().format(5).getString());

        Option<?> rate = general.getOptions().get(2);
        var rateSlider = assertInstanceOf(SliderControl.class, rate.getControl());
        assertEquals(RateSliderStops.STOPS.length - 1, rateSlider.max());
        assertEquals("lss.config.column_rate_limit.unlimited", rateSlider.formatter().format(0).getString());
        assertEquals("10", rateSlider.formatter().format(1).getString());
        assertEquals("lss.config.join_slow_start.tooltip", general.getOptions().get(3).getTooltip().getString());
    }

    @Test
    void theDependencySupplierFollowsTheStagedValue() throws Throwable {
        List<Object> pages = LegacySodiumPage.buildWith(handles, draft(), PLAIN, "LSS", h -> { });
        OptionPage general = (OptionPage) pages.get(0);
        @SuppressWarnings("unchecked") Option<Boolean> receive = (Option<Boolean>) general.getOptions().get(0);
        Option<?> distance = general.getOptions().get(1);
        assertTrue(distance.isAvailable());
        @SuppressWarnings("unchecked") Option<Boolean> source=(Option<Boolean>)general.getOptions().get(5);
        assertTrue(source.isAvailable());
        assertTrue(source.getValue(),"DH server terrain is allowed by default");
        source.setValue(false);
        receive.setValue(false);           // STAGED — not applied
        assertFalse(distance.isAvailable(), "greys before Apply, off the staged value");
        assertFalse(source.isAvailable(), "DH fallback follows the staged reception master");
        receive.setValue(true);
        assertTrue(distance.isAvailable());
        assertTrue(source.isAvailable());
    }

    @Test
    void theScreensApplyContractSavesEachStorageOnce() throws Throwable {
        var cfg = draft();
        List<SaveHook> saves = new ArrayList<>();
        List<Object> pages = LegacySodiumPage.buildWith(handles, cfg, PLAIN, "LSS", saves::add);
        OptionPage general = (OptionPage) pages.get(0);
        OptionPage far = (OptionPage) pages.get(1);
        @SuppressWarnings("unchecked") Option<Boolean> receive = (Option<Boolean>) general.getOptions().get(0);
        @SuppressWarnings("unchecked") Option<Integer> rate = (Option<Integer>) general.getOptions().get(2);
        @SuppressWarnings("unchecked") Option<Boolean> share = (Option<Boolean>) far.getOptions().get(1);
        @SuppressWarnings("unchecked") Option<Boolean> tags = (Option<Boolean>) far.getOptions().get(2);
        receive.setValue(false);
        rate.setValue(1);
        share.setValue(false);
        tags.setValue(false);
        @SuppressWarnings("unchecked") Option<Boolean> source = (Option<Boolean>) general.getOptions().get(5);
        source.setValue(false);
        assertTrue(receive.hasChanged() && rate.hasChanged() && share.hasChanged() && tags.hasChanged() && source.hasChanged());

        // What SodiumOptionsGUI.applyChanges() does (javap 0.6.13/0.7.3): apply every
        // changed option, collect their storages in a HashSet, save each once.
        Set<OptionStorage<?>> dirty = new HashSet<>();
        for (OptionPage p : List.of(general, far)) {
            for (Option<?> o : p.getOptions()) {
                if (o.hasChanged()) {
                    o.applyChanges();
                    dirty.add(o.getStorage());
                }
            }
        }
        assertEquals(1, dirty.size(), "one storage transaction across both pages");
        dirty.forEach(OptionStorage::save);

        assertEquals("lss", cfg.get("lod.source"));
        assertFalse(cfg.bool("lod.receive"));
        assertEquals(10, cfg.integer("lod.download.max_columns_per_second"), "slider index 1 → 10 col/s");
        assertFalse(cfg.bool("far_players.sharing.enabled"));
        assertFalse(cfg.bool("far_players.name_tags"));
        assertEquals(1, saves.size());
        assertTrue(saves.contains(SaveHook.SAVE));
        assertFalse(receive.hasChanged());
        assertEquals(cfg, dirty.iterator().next().getData());
        assertTrue(dirty.iterator().next().toString().startsWith("LssOptionStorage["));
    }

    @Test
    void cleanedLegacyOptionsRetainFailedDraftAndRetryWithoutReentry() throws Throwable {
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        var schema = SettingsSchema.client();
        var store = new SettingsStore<>(temp, "lss", schema, line -> {},
                (temporary, destination) -> { if (fail.get()) throw new java.io.IOException("disk full"); });
        var live = new dev.vox.lss.common.config.SettingsHandle<>(store);
        var reloads = new java.util.concurrent.atomic.AtomicInteger();
        var cfg = new ClientSettingsEditSession(store, () -> live.state().effective(),
                () -> live.state().configured(), () -> {
                    try { reloads.incrementAndGet(); live.commit(live.prepareReload()); }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
        var pages = LegacySodiumPage.buildWith(handles, cfg, PLAIN, "LSS", hook -> hook.run(cfg));
        @SuppressWarnings("unchecked") var share = (Option<Boolean>) ((OptionPage) pages.get(1)).getOptions().get(1);
        share.setValue(false);
        share.applyChanges();
        assertFalse(share.hasChanged(), "Sodium marks clean before saving");
        fail.set(true);
        share.getStorage().save();
        assertEquals(ClientSettingsEditSession.Outcome.FAILED, cfg.outcome());
        assertTrue(cfg.hasRetainedEdits());
        cfg.open(new Object());
        var reopened = LegacySodiumPage.buildWith(handles, cfg, PLAIN, "LSS", hook -> hook.run(cfg));
        assertEquals(false, ((OptionPage) reopened.get(1)).getOptions().get(1).getValue());
        assertEquals(0, reloads.get(), "failed save must not request reload");
        fail.set(false);
        assertTrue(cfg.apply());
        assertEquals(1, reloads.get(), "recovery must reload the saved draft");
        assertFalse(store.read().configured().farPlayers().sharing().enabled());
        assertFalse(live.state().effective().farPlayers().sharing().enabled());
    }

    @Test
    void aThrowingSodiumDegradesToNoPages() {
        // The production path: the stub screen resource is on the class path → the legacy
        // prefix resolves (ignoring the 0.8 API stubs the walker test also puts there — the
        // hook runs inside the legacy screen's own constructor, which is proof enough) →
        // real resolve against the stubs → the build throws.
        assertEquals(SodiumGeneration.CAFFEINE_PREFIX, SodiumGeneration.legacyPrefixIgnoringModern());
        OptionImpl.FAIL_BUILD = true;
        assertEquals(List.of(), LegacySodiumPage.build());
        OptionImpl.FAIL_BUILD = false;
        assertEquals(2, LegacySodiumPage.build().size(), "a later build is not latched by a build failure");
    }

    @Test
    void theProductionPathBuildsAgainstTheLiveConfig() {
        assertEquals(2, LegacySodiumPage.build().size());
    }
}
