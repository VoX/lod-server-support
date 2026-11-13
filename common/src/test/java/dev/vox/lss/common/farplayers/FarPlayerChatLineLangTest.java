package dev.vox.lss.common.farplayers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's far-player chat lines are translatable with an English fallback (review
 * fold B): a client whose 0.21.0 lang files carry the key shows its own language; every
 * other client (an older mod, no mod) shows the fallback. So the fallback must be the
 * en_us value word for word, all three shipped locales must carry both keys, and no value
 * may use a format specifier (the component is sent without arguments).
 *
 * <p>The VSS jar rewrites lang VALUES ("LOD Server Support" and the standalone word LSS).
 * The lines name the permission node the server actually checks, in lowercase, and no
 * brand: the rewrite must leave every value exactly as it is, or a VSS client would be
 * told about a node spelled differently from the fallback.
 */
class FarPlayerChatLineLangTest {

    private static Map<String, JsonObject> locales() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root = root.getParent();
        var out = new LinkedHashMap<String, JsonObject>();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw"))
            out.put(locale, JsonParser.parseString(Files.readString(root.resolve(
                    "fabric/src/main/resources/assets/lss/lang/" + locale + ".json"))).getAsJsonObject());
        return out;
    }

    /** The VSS jar's lang-value rewrite (fabric/build.gradle, vssJar). */
    private static String vssRewrite(String value) {
        return value.replace("LOD Server Support", "Voxy Server Side").replaceAll("\\bLSS\\b", "VSS");
    }

    @Test
    void theFallbackIsTheEnglishValueAndEveryLocaleCarriesBothLines() throws Exception {
        var locales = locales();
        var problems = new ArrayList<String>();     // every problem in one run, not the first
        for (var line : List.of(FarPlayerBroadcastService.requiredSharingNotice(),
                FarPlayerBroadcastService.holderNotice())) {
            String key = line.translationKey();
            if (!key.startsWith("lss.far_players.")) problems.add(key + ": not a far-player key");
            for (var locale : locales.entrySet()) {
                String where = locale.getKey() + " " + key;
                if (!locale.getValue().has(key)) {
                    problems.add(where + ": missing");
                    continue;
                }
                String value = locale.getValue().get(key).getAsString();
                if (value.isBlank()) problems.add(where + ": blank");
                if (value.contains("%")) problems.add(where + ": the line is sent without arguments");
                if (!value.equals(vssRewrite(value))) problems.add(where + ": the VSS lang rewrite changes it");
                if (locale.getKey().equals("en_us")) {
                    if (!value.equals(line.fallback()))
                        problems.add(where + ": the fallback is not the en_us value, word for word");
                } else if (value.equals(line.fallback())) {
                    problems.add(where + ": not translated");
                }
            }
        }
        assertEquals(List.of(), problems);
        assertNotEquals(FarPlayerBroadcastService.requiredSharingNotice().translationKey(),
                FarPlayerBroadcastService.holderNotice().translationKey());
    }

    @Test
    void bothLinesNameTheOptionByItsLabelInThatLocale() throws Exception {
        // "Share My Position" is the option's label; a player looks for those exact words.
        for (var locale : locales().entrySet()) {
            String label = locale.getValue().get("lss.config.far_players_share_self").getAsString();
            for (var line : List.of(FarPlayerBroadcastService.requiredSharingNotice(),
                    FarPlayerBroadcastService.holderNotice())) {
                assertTrue(locale.getValue().get(line.translationKey()).getAsString().contains(label),
                        locale.getKey() + " " + line.translationKey() + " must name the option as '" + label + "'");
            }
        }
    }

    @Test
    void theHolderLineKeepsTheLiteralNodeAndSettingInEveryLocale() throws Exception {
        String key = FarPlayerBroadcastService.holderNotice().translationKey();
        for (var locale : locales().entrySet()) {
            String value = locale.getValue().get(key).getAsString();
            for (String literal : List.of("lss.farplayers.hidden", "far_players.hide_permission_holders",
                    "0.21.0", "true", "false"))
                assertTrue(value.contains(literal), locale.getKey() + " must keep '" + literal + "': " + value);
        }
    }
}
