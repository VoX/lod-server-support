package dev.vox.lss.config.menu;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.LSSClientConfig;
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

    private static final MenuContext PLAIN = new MenuContext(true, false, false, true);

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
        assertEquals(2, LegacySodiumPage.buildWith(handles, new LSSClientConfig(), PLAIN, "LSS", h -> { }).size());
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
        List<Object> pages = LegacySodiumPage.buildWith(handles, new LSSClientConfig(),
                new MenuContext(true, false, false, false), "LSS", h -> { });
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
        List<Object> pages = LegacySodiumPage.buildWith(handles, new LSSClientConfig(), PLAIN, "LSS", h -> { });
        assertEquals(ClientOptionCatalog.pages().size(), pages.size());
        OptionPage general = (OptionPage) pages.get(0);
        OptionPage far = (OptionPage) pages.get(1);
        assertEquals("LSS", general.getName().getString(), "the first tab is the bare brand");
        assertTrue(far.getName().getString().startsWith("LSS "), "later tabs prefix the brand: " + far.getName().getString());
        assertEquals(4, general.getGroups().size());
        assertEquals(List.of(1, 1, 2, 1), general.getGroups().stream().map(g -> g.getOptions().size()).toList());
        assertEquals(1, far.getGroups().size());
        assertEquals(5, far.getGroups().get(0).getOptions().size(), "the SeeU override is hidden without SeeU");
        // Names in catalog order.
        var expected = ClientOptionCatalog.pages().get(0).options().stream().map(OptionSpec::nameKey).toList();
        assertEquals(expected, general.getOptions().stream().map(o -> o.getName().getString()).toList());
    }

    @Test
    void seeuRevealsTheOverrideAndFlipsTheTooltip() throws Throwable {
        List<Object> pages = LegacySodiumPage.buildWith(handles, new LSSClientConfig(),
                new MenuContext(true, false, true, true), "VSS", h -> { });
        OptionPage far = (OptionPage) pages.get(1);
        assertEquals(6, far.getGroups().get(0).getOptions().size());
        assertEquals("lss.config.far_players_enabled.tooltip.seeu",
                far.getOptions().get(0).getTooltip().getString());
        assertEquals("VSS", ((OptionPage) pages.get(0)).getName().getString());
    }

    @Test
    void controlsMatchTheCatalogKinds() throws Throwable {
        List<Object> pages = LegacySodiumPage.buildWith(handles, new LSSClientConfig(), PLAIN, "LSS", h -> { });
        OptionPage general = (OptionPage) pages.get(0);
        Option<?> receive = general.getOptions().get(0);
        assertInstanceOf(TickBoxControl.class, receive.getControl());
        assertEquals(OptionImpact.HIGH, receive.getImpact());
        assertEquals(Boolean.TRUE, receive.getValue());

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
        List<Object> pages = LegacySodiumPage.buildWith(handles, new LSSClientConfig(), PLAIN, "LSS", h -> { });
        OptionPage general = (OptionPage) pages.get(0);
        @SuppressWarnings("unchecked") Option<Boolean> receive = (Option<Boolean>) general.getOptions().get(0);
        Option<?> distance = general.getOptions().get(1);
        assertTrue(distance.isAvailable());
        receive.setValue(false);           // STAGED — not applied
        assertFalse(distance.isAvailable(), "greys before Apply, off the staged value");
        receive.setValue(true);
        assertTrue(distance.isAvailable());
    }

    @Test
    void theScreensApplyContractSavesEachStorageOnce() throws Throwable {
        var cfg = new LSSClientConfig();
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
        assertTrue(receive.hasChanged() && rate.hasChanged() && share.hasChanged() && tags.hasChanged());

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
        assertEquals(2, dirty.size(), "one plain storage + one push storage — the proxies must hash by identity");
        dirty.forEach(OptionStorage::save);

        assertFalse(cfg.receiveServerLods);
        assertEquals(10, cfg.lodColumnsPerSecondLimit, "slider index 1 → 10 col/s");
        assertFalse(cfg.farPlayersShareSelf);
        assertFalse(cfg.farPlayersNameTags);
        assertEquals(2, saves.size());
        assertTrue(saves.contains(SaveHook.SAVE));
        assertTrue(saves.contains(SaveHook.SAVE_AND_PUSH_FAR_PLAYER_PREFS));
        assertFalse(receive.hasChanged());
        assertEquals(cfg, dirty.iterator().next().getData());
        assertTrue(dirty.iterator().next().toString().startsWith("LssOptionStorage["));
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
