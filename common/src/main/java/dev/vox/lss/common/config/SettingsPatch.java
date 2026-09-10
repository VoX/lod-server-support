package dev.vox.lss.common.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Side-effect-free preview/undo engine. The platform owns publication and persistence.
 * It must validate a scratch copy, publish a final effective state on the owner, then
 * reconcile once. This class does not claim atomic visibility for mutable config readers. */
public final class SettingsPatch {
    public record Change(String before, String after) {}
    public record Preview(Object scopeIdentity, Map<String, String> relevantInputs,
                          Map<String, Change> changes) {
        public Preview { relevantInputs = Map.copyOf(relevantInputs); changes = Map.copyOf(changes); }
    }
    public static Preview preview(Object scope, Map<String, String> current,
            Map<String, String> requested, java.util.Set<String> allowedKeys,
            Function<Map<String, String>, Map<String, String>> validateScratch) {
        if (!allowedKeys.containsAll(requested.keySet())) throw new IllegalArgumentException("key outside selected scope/preset");
        var candidate = new LinkedHashMap<>(current);
        candidate.putAll(requested);
        var validated = Map.copyOf(validateScratch.apply(Map.copyOf(candidate)));
        if (!validated.keySet().equals(current.keySet())) throw new IllegalArgumentException("validator changed schema");
        var changes = new LinkedHashMap<String, Change>();
        for (String key : current.keySet()) {
            if (!Objects.equals(current.get(key), validated.get(key))) {
                if (!allowedKeys.contains(key)) throw new IllegalArgumentException("validation changes a protected key");
                changes.put(key, new Change(current.get(key), validated.get(key)));
            }
        }
        // Caller supplies the validation closure's relevant input map, not the whole config.
        return new Preview(scope, current, changes);
    }
    public static Map<String, String> recheck(Preview preview, Object scope,
            Map<String, String> relevantCurrent,
            Function<Map<String, String>, Map<String, String>> validateScratch) {
        requireScope(preview, scope);
        if (!preview.relevantInputs().equals(relevantCurrent))
            throw new IllegalStateException("settings changed; refresh preview");
        var candidate = new LinkedHashMap<>(relevantCurrent);
        preview.changes().forEach((key, change) -> candidate.put(key, change.after()));
        var validated = Map.copyOf(validateScratch.apply(Map.copyOf(candidate)));
        if (!validated.equals(candidate)) throw new IllegalStateException("validation changed; refresh preview");
        return validated;
    }
    public static Map<String, String> undo(Preview applied, Object scope,
            Map<String, String> current,
            Function<Map<String, String>, Map<String, String>> validateScratch) {
        requireScope(applied, scope);
        var candidate = new LinkedHashMap<>(current);
        applied.changes().forEach((key, change) -> {
            if (!Objects.equals(current.get(key), change.after()))
                throw new IllegalStateException("undo conflicts with a later edit");
            candidate.put(key, change.before());
        });
        var validated = Map.copyOf(validateScratch.apply(Map.copyOf(candidate)));
        if (!validated.equals(candidate)) throw new IllegalStateException("undo violates current validation");
        return validated;
    }
    private static void requireScope(Preview preview, Object scope) {
        if (preview.scopeIdentity() != scope) throw new IllegalStateException("scope replaced; preview/undo expired");
    }
    private SettingsPatch() {}
}
