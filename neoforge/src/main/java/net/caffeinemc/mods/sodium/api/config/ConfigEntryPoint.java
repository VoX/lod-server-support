package net.caffeinemc.mods.sodium.api.config;

import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;

/**
 * COMPILE-ONLY STUB of Sodium 0.8's config API (sodium-options-page-generations-plan.md,
 * NeoForge amendment 2026-08-26): signatures mirrored EXACTLY (javap) from
 * sodium-neoforge 0.8.12-beta.1 so {@code LSSConfigMenu} links against the REAL
 * classes at runtime. The whole {@code net.caffeinemc} tree is EXCLUDED from every
 * shipped jar (build.gradle + release_check) — shipping it would collide with
 * Sodium's own module. Only the members LSS calls are declared.
 */
public interface ConfigEntryPoint {
    default void registerConfigEarly(ConfigBuilder builder) {
    }

    void registerConfigLate(ConfigBuilder builder);
}
