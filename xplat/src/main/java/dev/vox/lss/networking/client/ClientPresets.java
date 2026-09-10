package dev.vox.lss.networking.client;

import dev.vox.lss.common.config.SettingsPatch;
import dev.vox.lss.config.LSSClientConfig;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** Client-owner qualitative patches. Privacy, aliases, fallback and storage identity are excluded. */
public final class ClientPresets {
    private static Object scope = new Object();
    private static Object world, connection, configuration;
    private static SettingsPatch.Preview preview, lastApplication;
    public static synchronized void invalidate() {
        scope = new Object();
        world = null; connection = null; configuration = null;
        preview = null; lastApplication = null;
    }
    private static void checkScope() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (world != mc.level || connection != mc.getConnection() || configuration != LSSClientConfig.CONFIG) {
            invalidate();
            world = mc.level; connection = mc.getConnection(); configuration = LSSClientConfig.CONFIG;
        }
    }
    public static synchronized List<String> command(String action) {
        checkScope();
        var cfg = LSSClientConfig.CONFIG;
        var current = Map.of("receiveServerLods", Boolean.toString(cfg.receiveServerLods),
                "enableXaeroMapBridge", Boolean.toString(cfg.enableXaeroMapBridge));
        switch (action) {
            case "map-only", "map-only-xaero-writes" -> {
                boolean mapWrites = action.equals("map-only-xaero-writes");
                var patch = mapWrites ? Map.of("receiveServerLods", "true", "enableXaeroMapBridge", "true")
                        : Map.of("receiveServerLods", "true");
                preview = SettingsPatch.preview(scope, mapWrites ? current : Map.of("receiveServerLods", current.get("receiveServerLods")),
                        patch, patch.keySet(), ClientPresets::validate);
                return List.of("Client preset preview: " + preview.changes(),
                        mapWrites ? "Explicit choice: enable persistent Xaero map writes." : "Xaero map-write preference unchanged. Use map-only-xaero-writes to explicitly select persistent map writes.",
                        "A compatible map consumer is required. This changes LSS settings only; it does not select delivery recipients or disable another mod.",
                        "Use preset apply to apply and save.");
            }
            case "apply" -> {
                if (preview == null) throw new IllegalStateException("Preview a client preset first.");
                var candidate = SettingsPatch.recheck(preview, scope, relevantCurrent(preview, current), ClientPresets::validate);
                publish(cfg, candidate);
                lastApplication = preview; preview = null;
                return List.of("Client preset applied; " + (cfg.trySave() ? "saved." : "not saved — check client log."));
            }
            case "undo" -> {
                if (lastApplication == null) throw new IllegalStateException("No client preset application to undo.");
                var candidate = SettingsPatch.undo(lastApplication, scope, current, ClientPresets::validate);
                publish(cfg, candidate);
                lastApplication = null; preview = null;
                return List.of("Last client preset settings restored; " + (cfg.trySave() ? "saved." : "not saved — check client log."));
            }
            default -> throw new IllegalArgumentException("Preset actions: map-only | map-only-xaero-writes | apply | undo");
        }
    }
    private static Map<String, String> relevantCurrent(SettingsPatch.Preview preview, Map<String, String> current) {
        var relevant = new java.util.LinkedHashMap<String, String>();
        preview.relevantInputs().keySet().forEach(key -> relevant.put(key, current.get(key)));
        return Map.copyOf(relevant);
    }
    private static Map<String, String> validate(Map<String, String> values) {
        // Both fields have an identity boolean validator; parsing completes before publication.
        for (String value : values.values())
            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("expected true or false");
        return Map.copyOf(values);
    }
    private static void publish(LSSClientConfig cfg, Map<String, String> values) {
        if (values.containsKey("receiveServerLods")) cfg.receiveServerLods = Boolean.parseBoolean(values.get("receiveServerLods"));
        if (values.containsKey("enableXaeroMapBridge")) cfg.enableXaeroMapBridge = Boolean.parseBoolean(values.get("enableXaeroMapBridge"));
        // The selected independent boolean settings publish on the client owner; one
        // reconciliation observes their final values. No far-player preference is changed.
        ClientNetGlue.reconcileClientConfig();
    }
    private ClientPresets() {}
}
