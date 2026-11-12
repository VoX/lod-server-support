package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.menu.ActionSpec;
import dev.vox.lss.config.menu.ClientOptionCatalog;
import dev.vox.lss.config.menu.OptionSpec;
import dev.vox.lss.config.menu.PageSpec;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ExternalButtonOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedBoolean;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedExternalButton;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedGroup;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedInteger;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedOption;
import net.caffeinemc.mods.sodium.api.config.structure.RecordedPage;
import net.caffeinemc.mods.sodium.api.config.structure.RecordingConfigBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Sodium 0.8+ walker against RECORDING stubs of the public config API
 * (fabric/src/test/java/net/caffeinemc/mods/sodium/api/config — the same
 * real-package-name discipline as the legacy stubs; the real API is {@code compileOnly}
 * and never on the T1 class path). Pins the shipping renderer's shape the way
 * {@code LegacySodiumPageTest} pins the legacy one: the catalog is walked in order into
 * two pages with the catalog's groups, every option carries the catalog's id/name/
 * tooltip/impact/default/range/binding/dependency, the value formatter renders the
 * catalog labels, and EXACTLY ONE storage handler are installed across all options —
 * Sodium fires each distinct handler once per Apply, so per-option lambdas would have
 * saved the file once per changed option (implementation review). A page's buttons
 * ({@code PageSpec.actions()}) follow its option groups as one more group of external
 * buttons, and a button that cannot be built costs only itself.
 */
class LSSConfigMenuTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    private dev.vox.lss.config.menu.ClientSettingsEditSession draft;
    /** What the injected dispatcher was asked to open: {@code {action, parent}} per press. */
    private final List<Object[]> opened = new ArrayList<>();
    private final BiConsumer<ActionSpec.Action, Screen> dispatcher = (action, parent) -> opened.add(new Object[] {action, parent});
    private void register(RecordingConfigBuilder builder) {
        try {
            if (draft == null) {
                var schema = dev.vox.lss.common.config.SettingsSchema.client();
                var store = new dev.vox.lss.common.config.SettingsStore<>(temp, "lss", schema);
                store.initialize();
                draft = new dev.vox.lss.config.menu.ClientSettingsEditSession(store, schema::defaults);
            }
            new LSSConfigMenu().registerDraft(builder, draft, dev.vox.lss.config.menu.MenuContext.current(), dispatcher);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    /** The settings a page holds: every recorded row that is not a button. */
    private static List<RecordedOption<?>> settings(RecordedGroup group) {
        List<RecordedOption<?>> settings = new ArrayList<>();
        for (var built : group.options) if (built instanceof RecordedOption<?> option) settings.add(option);
        return settings;
    }

    private static List<RecordedExternalButton> buttons(RecordedGroup group) {
        List<RecordedExternalButton> buttons = new ArrayList<>();
        for (var built : group.options) if (built instanceof RecordedExternalButton button) buttons.add(button);
        return buttons;
    }


    /** Sodium 0.8 registers the page once at game start; the map ingestion native-only tooltip must
     *  follow the CURRENT terrain source, not the context captured at registration (D-25). */
    @Test
    void contextDependentTooltipsIgnoreTheRegistrationContext() {
        if (draft == null) register(new RecordingConfigBuilder());
        var stale = new dev.vox.lss.config.menu.MenuContext(true, true, true, false);
        var builder = new RecordingConfigBuilder();
        new LSSConfigMenu().registerDraft(builder, draft, stale, dispatcher);
        var maps = ClientOptionCatalog.find(ClientOptionCatalog.ID_MAP_INGESTION).orElseThrow();
        var live = dev.vox.lss.config.menu.MenuContext.current();
        assertTrue(live.nativeTerrain(), "unit context reads native terrain");
        builder.mods.get(0).pages.forEach(p -> p.groups.forEach(g -> settings(g).forEach(recorded -> {
            if (recorded.id.toString().equals(maps.id()))
                assertEquals(maps.tooltip().resolve(live), recorded.tooltip.getString());
        })));
        assertNotEquals(maps.tooltip().resolve(stale), maps.tooltip().resolve(live));
    }

    @Test
    void walksTheCatalogIntoTwoPagesWithTheCatalogsShape() {
        var builder = new RecordingConfigBuilder();
        register(builder);

        assertEquals(1, builder.mods.size());
        var mod = builder.mods.get(0);
        assertEquals(LSSConstants.MOD_ID, mod.id);
        assertNotNull(mod.name);
        assertNotNull(mod.version);
        assertNotNull(mod.icon);

        List<PageSpec> catalog = ClientOptionCatalog.pages();
        assertEquals(catalog.size(), mod.pages.size());
        for (int p = 0; p < catalog.size(); p++) {
            var page = mod.pages.get(p);
            assertEquals(catalog.get(p).titleKey(), page.name.getString(), "page title key");
            // renderer is AVAILABLE on Fabric, so the visible group shape is the catalog's
            var visibleGroups = catalog.get(p).groups().stream()
                    .map(g -> g.options().stream().toList())
                    .filter(l -> !l.isEmpty()).toList();
            // the page's buttons, when it has any, are one more group after the option groups
            int buttonGroups = catalog.get(p).actions().isEmpty() ? 0 : 1;
            assertEquals(visibleGroups.size() + buttonGroups, page.groups.size(), "group count on page " + p);
            for (int g = 0; g < visibleGroups.size(); g++) {
                assertEquals(visibleGroups.get(g).stream().map(OptionSpec::id).toList(),
                        settings(page.groups.get(g)).stream().map(o -> o.id.toString()).toList(),
                        "option ids in group " + g + " of page " + p);
                assertTrue(buttons(page.groups.get(g)).isEmpty(), "no button inside an option group");
            }
        }
    }

    @Test
    void everyOptionCarriesTheCatalogsFacts() {
        var builder = new RecordingConfigBuilder();
        register(builder);
        List<RecordedOption<?>> built = new ArrayList<>();
        builder.mods.get(0).pages.forEach(p -> p.groups.forEach(g -> built.addAll(settings(g))));
        assertEquals(12, built.size(), "twelve explicit controls (buttons are not settings)");

        for (RecordedOption<?> o : built) {
            OptionSpec spec = ClientOptionCatalog.find(o.id.toString()).orElseThrow();
            assertEquals(spec.nameKey(), o.name.getString());
            assertEquals(spec.tooltip().resolve(dev.vox.lss.config.menu.MenuContext.current()), o.tooltip.getString());
            if (spec.impact() == null) {
                assertNull(o.impact, spec.id() + " ships without an impact line");
            } else {
                assertEquals(OptionImpact.valueOf(spec.impact().name()), o.impact);
            }
            assertNotNull(o.setter, spec.id());
            assertNotNull(o.getter, spec.id());
            assertNotNull(o.storageHandler, spec.id());
            if (spec.enabledBy() == null) {
                assertNull(o.enabledProvider, spec.id());
                assertEquals(0, o.dependencies.length);
            } else {
                assertEquals(ClientOptionCatalog.enabledDependencies(spec), java.util.Arrays.stream(o.dependencies).map(Object::toString).toList());
                // The provider reads exactly the declared dependency from the pending state.
                var expected=java.util.Set.of(o.dependencies);
                assertEquals(Boolean.TRUE, o.enabledProvider.apply(new ConfigState() {
                    @Override public boolean readBooleanOption(Identifier id) { return expected.contains(id); }
                    @Override public int readIntOption(Identifier id) { return 0; }
                }));
            }
            switch (spec) {
                case OptionSpec.BoolSpec b -> {
                    var bb = assertInstanceOf(RecordedBoolean.class, o);
                    assertEquals(b.defaultValue(), bb.defaultValue);
                }
                case OptionSpec.IntSpec i -> {
                    var ib = assertInstanceOf(RecordedInteger.class, o);
                    assertEquals(i.defaultValue(), ib.defaultValue);
                    assertEquals(i.min(), ib.range.min());
                    assertEquals(i.max(), ib.range.max());
                    assertEquals(i.step(), ib.range.step());
                    var label0 = i.label().apply(i.min());
                    assertEquals(label0.isKey() ? label0.key() : label0.literal(), ib.formatter.format(i.min()).getString());
                    var labelMax = i.label().apply(i.max());
                    assertEquals(labelMax.isKey() ? labelMax.key() : labelMax.literal(), ib.formatter.format(i.max()).getString());
                }
            }
        }
    }

    // ---- page buttons (re-download plan §2.3 and decision 4) ----

    @Test
    void thePageButtonsFollowTheOptionGroupsAndOpenThroughTheDispatcher() {
        var builder = new RecordingConfigBuilder();
        register(builder);
        List<PageSpec> catalog = ClientOptionCatalog.pages();
        int buttonsBuilt = 0;
        for (int p = 0; p < catalog.size(); p++) {
            RecordedPage page = builder.mods.get(0).pages.get(p);
            List<ActionSpec> actions = catalog.get(p).actions();
            if (actions.isEmpty()) {
                for (RecordedGroup group : page.groups) assertTrue(buttons(group).isEmpty(), "page " + p + " has no buttons");
                continue;
            }
            RecordedGroup last = page.groups.get(page.groups.size() - 1);
            assertTrue(settings(last).isEmpty(), "the button group holds no setting");
            List<RecordedExternalButton> built = buttons(last);
            assertEquals(actions.stream().map(ActionSpec::id).toList(), built.stream().map(b -> b.id.toString()).toList());
            for (int i = 0; i < actions.size(); i++) {
                ActionSpec spec = actions.get(i);
                RecordedExternalButton button = built.get(i);
                assertEquals(spec.nameKey(), button.name.getString());
                assertEquals(spec.tooltipKey(), button.tooltip.getString());
                assertNull(button.enabledProvider, "always enabled: the confirm screen says what it will do");
                // Sodium hands the consumer its own settings screen; the walker forwards it untouched.
                Screen parent = new org.objenesis.ObjenesisStd().newInstance(ParentScreen.class);
                opened.clear();
                button.screenConsumer.accept(parent);
                assertEquals(1, opened.size());
                assertSame(spec.action(), opened.get(0)[0]);
                assertSame(parent, opened.get(0)[1]);
                buttonsBuilt++;
            }
        }
        assertEquals(1, buttonsBuilt, "the one shipped button: Re-download Terrain on General");
        RecordedPage general = builder.mods.get(0).pages.get(0);
        assertEquals(ClientOptionCatalog.ID_REDOWNLOAD_TERRAIN,
                buttons(general.groups.get(general.groups.size() - 1)).get(0).id.toString());
    }

    /** Sodium validates the builders after {@code registerConfigLate} returns and stops the game on
     *  a blank tooltip. A blank translation gets the literal fallback instead. */
    @Test
    void aBlankTranslatedTooltipFallsBackToLiteralText() {
        ActionSpec spec = ClientOptionCatalog.pages().get(0).actions().get(0);
        Language previous = Language.getInstance();
        Language.inject(overriding(previous, Map.of(spec.tooltipKey(), "   ")));
        try {
            var builder = new RecordingConfigBuilder();
            register(builder);
            RecordedPage general = builder.mods.get(0).pages.get(0);
            RecordedExternalButton button = buttons(general.groups.get(general.groups.size() - 1)).get(0);
            assertFalse(button.tooltip.getString().isBlank(), "Sodium would refuse a blank tooltip");
            assertEquals("Opens a screen for this action.", button.tooltip.getString());
            assertFalse(button.name.getString().isBlank());
        } finally {
            Language.inject(previous);
        }
    }

    /** A blank NAME has no sensible stand-in: the button is left out, the page stays whole. */
    @Test
    void aButtonThatCannotBeBuiltCostsOnlyTheButton() {
        ActionSpec spec = ClientOptionCatalog.pages().get(0).actions().get(0);
        Language previous = Language.getInstance();
        Language.inject(overriding(previous, Map.of(spec.nameKey(), "")));
        try {
            var builder = new RecordingConfigBuilder();
            register(builder);
            assertOnlyTheSettingsWereBuilt(builder, "a blank name");
        } finally {
            Language.inject(previous);
        }

        // An older or changed Sodium without the external-button API: a linkage error, not an exception.
        var noButtonApi = new RecordingConfigBuilder() {
            @Override public ExternalButtonOptionBuilder createExternalButtonOption(Identifier id) {
                throw new NoSuchMethodError("createExternalButtonOption");
            }
        };
        register(noButtonApi);
        assertOnlyTheSettingsWereBuilt(noButtonApi, "a missing API");

        // A missing dispatcher would leave a row that does nothing: no row instead.
        var noDispatcher = new RecordingConfigBuilder();
        new LSSConfigMenu().registerDraft(noDispatcher, draft, dev.vox.lss.config.menu.MenuContext.current(), null);
        assertOnlyTheSettingsWereBuilt(noDispatcher, "a missing dispatcher");
    }

    private static void assertOnlyTheSettingsWereBuilt(RecordingConfigBuilder builder, String why) {
        List<PageSpec> catalog = ClientOptionCatalog.pages();
        assertEquals(catalog.size(), builder.mods.get(0).pages.size(), why + ": every page is still registered");
        int settings = 0;
        for (int p = 0; p < catalog.size(); p++) {
            RecordedPage page = builder.mods.get(0).pages.get(p);
            assertEquals(catalog.get(p).groups().size(), page.groups.size(), why + ": no button group on page " + p);
            for (RecordedGroup group : page.groups) {
                assertTrue(buttons(group).isEmpty(), why);
                settings += settings(group).size();
            }
        }
        assertEquals(12, settings, why + ": all twelve settings survive");
    }

    /** A language that answers the given keys itself and everything else from {@code base}. */
    private static Language overriding(Language base, Map<String, String> values) {
        return new Language() {
            @Override public String getOrDefault(String key, String fallback) {
                return values.containsKey(key) ? values.get(key) : base.getOrDefault(key, fallback);
            }
            @Override public boolean has(String key) { return values.containsKey(key) || base.has(key); }
            @Override public boolean isDefaultRightToLeft() { return base.isDefaultRightToLeft(); }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return base.getVisualOrder(text); }
        };
    }

    /** Never constructed (a screen needs a running client): allocated bare, used as an identity. */
    private static final class ParentScreen extends Screen {
        private ParentScreen() { super(null); }
    }
}
