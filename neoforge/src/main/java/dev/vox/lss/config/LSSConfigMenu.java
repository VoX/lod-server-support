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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        var cfg = LSSClientConfig.CONFIG;
        var ctx = MenuContext.current();

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
