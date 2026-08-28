package dev.vox.lss.config;

import dev.vox.lss.common.LSSConstants;
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
import net.caffeinemc.mods.sodium.api.config.structure.StatefulOptionBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The Sodium 0.8+ config-API renderer (sodium-options-page-generations-plan.md D3): a
 * thin WALKER over {@link ClientOptionCatalog} — every option's name, tooltip, impact,
 * default, range, binding, dependency and save hook come from the catalog, so a new
 * option is a catalog edit that never touches this file. Registered through the
 * {@code sodium:config_api_user} entrypoint; present only on lines whose Sodium has the
 * public config API (the 0.6/0.7 lines render the same catalog through the legacy
 * reflective builder instead).
 *
 * <p>LINE FLAVOR: the identifier type ({@code ResourceLocation} on 26.x/1.21.11,
 * {@code ResourceLocation} on 1.21.x) is the only per-line edit this file carries.
 */
public class LSSConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        var cfg = LSSClientConfig.CONFIG;
        var ctx = MenuContext.current();

        var container = FabricLoader.getInstance().getModContainer(LSSConstants.MOD_ID);
        var version = container
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        // Display-only branding flows from the jar's OWN descriptor: the Voxy Server Side
        // repackage rewrites fabric.mod.json name/icon (identity fields untouched), so the
        // VSS jar brands this screen without a code fork. MOD_ID stays the registration key.
        var displayName = container
                .map(c -> c.getMetadata().getName())
                .orElse(dev.vox.lss.common.Brand.displayName());
        var mod = builder.registerModOptions(LSSConstants.MOD_ID, displayName, version)
                .setIcon(iconFromMetadata(container));
        // ONE handler per SaveHook, shared by every option that uses it: Sodium keeps the
        // pending handlers in an identity Set and fires each once per Apply, so per-option
        // lambdas would save the file once per changed option (implementation review) —
        // and the legacy renderer's two storage proxies are the same shape (plan D8).
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
            mod.addPage(page);
        }
    }

    private static OptionBuilder buildOption(ConfigBuilder builder, OptionSpec spec,
                                             LSSClientConfig cfg, MenuContext ctx,
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
                o.setBinding(v -> s.setter().accept(cfg, v), () -> s.getter().apply(cfg));
                yield o;
            }
        };
        option.setName(Component.translatable(spec.nameKey()));
        option.setTooltip(Component.translatable(spec.tooltip().resolve(ctx)));
        if (spec.impact() != null) {
            option.setImpact(OptionImpact.valueOf(spec.impact().name()));
        }
        option.setStorageHandler(save);
        if (spec.enabledBy() != null) {
            ResourceLocation dep = ResourceLocation.parse(spec.enabledBy());
            option.setEnabledProvider(state -> state.readBooleanOption(dep), dep);
        }
        return option;
    }

    private static Component component(Label label) {
        return label.isKey() ? Component.translatable(label.key()) : Component.literal(label.literal());
    }

    /** The descriptor's `icon` path as a resource ResourceLocation ("assets/&lt;ns&gt;/&lt;path&gt;" →
     *  "&lt;ns&gt;:&lt;path&gt;"), falling back to the LSS icon. Display-only — a malformed or
     *  missing path must degrade, never throw. */
    private static ResourceLocation iconFromMetadata(Optional<ModContainer> container) {
        return container.flatMap(c -> c.getMetadata().getIconPath(32))
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
