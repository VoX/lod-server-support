package dev.vox.lss.common.config;

import java.util.Set;

/** Engine-independent metadata; bindings remain in RuntimeSettings/ClientOptionCatalog.
 * Stored values are described separately from effective runtime policy (notably AUTO). */
public record SettingDescriptor(String key, Type type, String units, String documentationKey,
        String defaultPolicy, String domain, String validationBinding, Set<Scope> scopes,
        String inheritance, String availability, String applyTiming, boolean restartRequired,
        String controlAction, Exposure exposure) {
    public enum Type { BOOLEAN, INTEGER, DECIMAL, STRING, LIST, MAP }
    public enum Scope { CLIENT, SERVER, WORLD_DISTANCE }
    public enum Exposure { UI, RUNTIME, ADVANCED, INTERNAL }
    public SettingDescriptor { scopes = Set.copyOf(scopes); }
    public boolean supports(Scope scope) { return scopes.contains(scope); }
}
