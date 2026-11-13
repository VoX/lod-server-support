package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.config.menu.ActionSpec;
import dev.vox.lss.config.menu.ClientMenuActions;
import dev.vox.lss.config.menu.ClientOptionCatalog;
import dev.vox.lss.config.menu.GroupSpec;
import dev.vox.lss.config.menu.Label;
import dev.vox.lss.config.menu.MenuContext;
import dev.vox.lss.config.menu.OptionSpec;
import dev.vox.lss.config.menu.PageSpec;
import dev.vox.lss.config.menu.SaveHook;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionPageBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * The Sodium 0.8+ config-API renderer, NEOFORGE TWIN of the Fabric walker (same FQN,
 * same catalog walk — sodium-options-page-generations-plan.md D3 as amended for
 * NeoForge 2026-08-26). Discovery differs per loader: Fabric registers through the
 * {@code sodium:config_api_user} ENTRYPOINT; sodium-neoforge's {@code ConfigLoaderForge}
 * instead reads the SAME key from each mod's {@code [modproperties]} in
 * neoforge.mods.toml and instantiates the named class — this class — reflectively.
 * Compiles against the {@code net.caffeinemc} COMPILE-ONLY STUBS in this source set
 * (excluded from every shipped jar); links against the real Sodium at runtime, and is
 * simply never instantiated when Sodium is absent or pre-0.8 (the legacy 0.6/0.7 tabs
 * mixin owns that generation).
 *
 * <p>Loader-specific surface is confined to the metadata block: ModList/IModInfo for
 * display name + version (the VSS TOML rewrite brands the screen with no code fork,
 * mirroring the Fabric descriptor rule) and logoFile→resource mapping for the icon.
 */
public class LSSConfigMenu implements ConfigEntryPoint {

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        registerDraft(builder, LSSClientConfig.CONFIG.edits(), MenuContext.current(), ClientMenuActions::open);
    }

    /** {@code actions} opens a page button's screen over the settings screen it was pressed on;
     *  a parameter so the walker test can press the recorded button. */
    void registerDraft(ConfigBuilder builder, dev.vox.lss.config.menu.ClientSettingsEditSession cfg, MenuContext ctx,
                       BiConsumer<ActionSpec.Action, Screen> actions) {

        Optional<IModInfo> info = ModList.get().getModContainerById(LSSConstants.MOD_ID)
                .map(c -> c.getModInfo());
        var version = info.map(i -> i.getVersion().toString()).orElse("unknown");
        var displayName = info.map(IModInfo::getDisplayName).orElse(dev.vox.lss.common.Brand.displayName());
        var mod = builder.registerModOptions(LSSConstants.MOD_ID, displayName, version)
                .setIcon(iconFromMetadata(info));
        // ONE handler per SaveHook, shared by every option that uses it (see the Fabric
        // twin's comment — Sodium fires each pending handler once per Apply).
        Map<SaveHook, StorageEventHandler> handlers = new EnumMap<>(SaveHook.class);
        for (SaveHook hook : SaveHook.values()) {
            handlers.put(hook, () -> hook.run(cfg));
        }

        for (PageSpec pageSpec : ClientOptionCatalog.pages()) {
            var page = builder.createOptionPage();
            page.setName(Component.translatable(pageSpec.titleKey()));
            for (GroupSpec groupSpec : pageSpec.groups()) {
                var group = builder.createOptionGroup();
                boolean any = false;
                for (OptionSpec spec : groupSpec.options()) {
                    if (!spec.visibility().test(ctx)) {
                        continue;
                    }
                    group.addOption(buildOption(builder, spec, cfg, ctx, handlers.get(spec.saveHook())));
                    any = true;
                }
                if (any) {
                    page.addOptionGroup(group);
                }
            }
            addActions(builder, page, pageSpec, actions);
            mod.addPage(page);
        }
    }

    /** The tooltip a button gets when its translation is blank: Sodium refuses a blank one. */
    private static final String ACTION_TOOLTIP_FALLBACK = "Opens a screen for this action.";

    /**
     * The page's buttons ({@link PageSpec#actions()}) as one more group after its options.
     * Sodium checks every builder AFTER this entry point has returned and stops the game on a
     * bad one (a missing or blank name or tooltip, no screen consumer). So each button is
     * checked here and built completely before anything is attached to the page, and whatever
     * goes wrong costs the buttons, never the page or the game.
     */
    private static void addActions(ConfigBuilder builder, OptionPageBuilder page, PageSpec pageSpec,
                                   BiConsumer<ActionSpec.Action, Screen> actions) {
        if (pageSpec.actions().isEmpty()) {
            return;
        }
        try {
            Objects.requireNonNull(actions, "action dispatcher");
            List<OptionBuilder> buttons = new ArrayList<>();
            for (ActionSpec spec : pageSpec.actions()) {
                Component name = Component.translatable(spec.nameKey());
                if (name.getString().isBlank()) {
                    throw new IllegalStateException(spec.id() + " has a blank name");
                }
                Component tooltip = Component.translatable(spec.tooltipKey());
                if (tooltip.getString().isBlank()) {
                    tooltip = Component.literal(ACTION_TOOLTIP_FALLBACK);
                }
                ActionSpec.Action action = spec.action();
                buttons.add(builder.createExternalButtonOption(ResourceLocation.parse(spec.id()))
                        .setName(name)
                        .setTooltip(tooltip)
                        .setScreenConsumer(parent -> actions.accept(action, parent)));
            }
            var group = builder.createOptionGroup();
            for (OptionBuilder button : buttons) {
                group.addOption(button);
            }
            page.addOptionGroup(group);
        } catch (Throwable failure) {
            dev.vox.lss.common.LSSLogger.warn(dev.vox.lss.common.Brand.shortName() + " options page: the buttons of the "
                    + pageSpec.id() + " page were left out (" + failure + ")");
        }
    }

    private static OptionBuilder buildOption(ConfigBuilder builder, OptionSpec spec,
                                             dev.vox.lss.config.menu.ClientSettingsEditSession cfg, MenuContext ctx,
                                             StorageEventHandler save) {
        ResourceLocation id = ResourceLocation.parse(spec.id());
        StatefulOptionBuilder<?> option = switch (spec) {
            case OptionSpec.BoolSpec s -> {
                var o = builder.createBooleanOption(id);
                o.setDefaultValue(s.defaultValue());
                o.setBinding(v -> s.setter().accept(cfg, v), () -> s.getter().apply(cfg));
                yield o;
            }
            case OptionSpec.IntSpec s -> {
                var o = builder.createIntegerOption(id);
                o.setDefaultValue(s.defaultValue());
                o.setRange(new Range(s.min(), s.max(), s.step()));
                o.setValueFormatter(v -> component(s.label().apply(v)));
                o.setBinding(v -> s.setter().accept(cfg, v), () -> s.displayValue(cfg));
                yield o;
            }
        };
        option.setName(Component.translatable(spec.nameKey()));
        // Sodium 0.8 registers once at game start: context-dependent facts resolve at display.
        option.setTooltip(unused -> Component.translatable(spec.tooltip().resolveAtDisplay(ctx, MenuContext::current)));
        if (spec.impact() != null) {
            option.setImpact(OptionImpact.valueOf(spec.impact().name()));
        }
        option.setStorageHandler(save);
        if (spec.enabledBy() != null) {
            ResourceLocation[] deps=ClientOptionCatalog.enabledDependencies(spec).stream().map(ResourceLocation::parse).toArray(ResourceLocation[]::new);
            option.setEnabledProvider(state -> {
                for(ResourceLocation dep:deps)if(!state.readBooleanOption(dep))return false;
                return true;
            }, deps);
        }
        return option;
    }

    private static Component component(Label label) {
        return label.isKey() ? Component.translatable(label.key()) : Component.literal(label.literal());
    }

    /** The TOML {@code logoFile} as a resource ResourceLocation when it is an
     *  assets path, falling back to the LSS icon (shipped in this jar). Display-only —
     *  malformed metadata degrades, never throws. */
    private static ResourceLocation iconFromMetadata(Optional<IModInfo> info) {
        return info.flatMap(IModInfo::getLogoFile)
                .filter(p -> p.startsWith("assets/"))
                .map(p -> p.substring("assets/".length()))
                .map(p -> {
                    int slash = p.indexOf('/');
                    return slash > 0
                            ? ResourceLocation.tryParse(p.substring(0, slash) + ":" + p.substring(slash + 1))
                            : null;
                })
                .filter(Objects::nonNull)
                .orElse(ResourceLocation.parse("lss:icon.png"));
    }
}
