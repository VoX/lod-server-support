package dev.vox.lss.common.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class SettingsPatchTest {
    private static Map<String, String> validate(Map<String, String> values) {
        int global = ServerConfigBase.clampGenGlobal(Integer.parseInt(values.get("global")));
        int player = ServerConfigBase.clampGenPerPlayer(Integer.parseInt(values.get("player")), global);
        return Map.of("global", "" + global, "player", "" + player);
    }
    @Test void bothGenerationLimitsValidateFinalCandidateIndependentOfKeyOrder() {
        Object scope = new Object();
        for (int target : new int[]{5, 80}) {
            var a = new LinkedHashMap<String, String>(); a.put("global", "" + target); a.put("player", "" + target);
            var b = new LinkedHashMap<String, String>(); b.put("player", "" + target); b.put("global", "" + target);
            var current = Map.of("global", "40", "player", "40");
            var first = SettingsPatch.preview(scope, current, a, current.keySet(), SettingsPatchTest::validate);
            var second = SettingsPatch.preview(scope, current, b, current.keySet(), SettingsPatchTest::validate);
            assertEquals(first.changes(), second.changes());
            assertEquals("" + target, SettingsPatch.recheck(first, scope, current, SettingsPatchTest::validate).get("player"));
        }
    }
    @Test void stalePreviewScopeReplacementAndConflictingUndoAreRejected() {
        Object scope = new Object();
        var current = Map.of("receive", "false");
        var preview = SettingsPatch.preview(scope, current, Map.of("receive", "true"), Set.of("receive"), v -> v);
        assertThrows(IllegalStateException.class, () -> SettingsPatch.recheck(preview, new Object(), current, v -> v));
        assertThrows(IllegalStateException.class, () -> SettingsPatch.recheck(preview, scope, Map.of("receive", "true"), v -> v));
        assertThrows(IllegalStateException.class, () -> SettingsPatch.undo(preview, scope, current, v -> v));
        assertEquals(Map.of("receive", "false", "unrelated", "edited"), SettingsPatch.undo(preview, scope,
                Map.of("receive", "true", "unrelated", "edited"), v -> v));
    }
    @Test void parseFailureAndDerivedProtectedChangeNeverPublish() {
        var original = Map.of("global", "40", "player", "40");
        assertThrows(NumberFormatException.class, () -> SettingsPatch.preview(new Object(), original,
                Map.of("global", "typo"), original.keySet(), SettingsPatchTest::validate));
        assertThrows(IllegalArgumentException.class, () -> SettingsPatch.preview(new Object(), original,
                Map.of("global", "5"), Set.of("global"), SettingsPatchTest::validate));
        assertEquals("40", original.get("global"));
    }
}
