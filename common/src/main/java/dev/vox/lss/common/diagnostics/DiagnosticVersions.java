package dev.vox.lss.common.diagnostics;

import java.util.EnumMap;
import java.util.Map;

/** Only named component versions are admissible; arbitrary loader metadata/logs are not. */
public record DiagnosticVersions(Map<Component, String> components) {
    public enum Component { LSS, MINECRAFT, LOADER, SODIUM, XAERO, VOXY, CONNECTOR, C2ME }
    public DiagnosticVersions {
        var sanitized = new EnumMap<Component, String>(Component.class);
        for (var component : Component.values()) {
            String value = components.getOrDefault(component, "unknown");
            sanitized.put(component, value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._+~-]{0,95}")
                    ? value : "redacted");
        }
        components = Map.copyOf(sanitized);
    }
    public static DiagnosticVersions unknown() { return new DiagnosticVersions(Map.of()); }
}
