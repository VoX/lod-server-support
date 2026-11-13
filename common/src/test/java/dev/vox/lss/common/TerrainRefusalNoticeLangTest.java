package dev.vox.lss.common;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The permission line is a server-sent translatable with an English fallback (0.22.0 plan §1):
 * a client with the key shows its own language, every other client the fallback. So the
 * fallback must be the en_us value word for word, every locale must carry the key, and the
 * VSS build's lang rewrite must leave it alone (the server cannot know the client's brand).
 */
class TerrainRefusalNoticeLangTest {

    @Test
    void theFallbackIsTheEnglishValueAndEveryLocaleCarriesTheLine() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root = root.getParent();
        var problems = new ArrayList<String>();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw")) {
            var lang = JsonParser.parseString(Files.readString(root.resolve(
                    "fabric/src/main/resources/assets/lss/lang/" + locale + ".json"))).getAsJsonObject();
            String where = locale + " " + TerrainRefusalNotice.KEY;
            if (!lang.has(TerrainRefusalNotice.KEY)) {
                problems.add(where + ": missing");
                continue;
            }
            String value = lang.get(TerrainRefusalNotice.KEY).getAsString();
            if (value.isBlank()) problems.add(where + ": blank");
            if (value.contains("%")) problems.add(where + ": the line is sent without arguments");
            String rewritten = value.replace("LOD Server Support", "Voxy Server Side").replaceAll("\\bLSS\\b", "VSS");
            if (!value.equals(rewritten)) problems.add(where + ": the VSS lang rewrite changes it");
            if (value.toLowerCase(java.util.Locale.ROOT).matches(".*\\b(lss|vss)\\.[a-z].*")) {
                problems.add(where + ": names a permission node");
            }
            if (locale.equals("en_us")) {
                if (!value.equals(TerrainRefusalNotice.FALLBACK)) {
                    problems.add(where + ": the fallback is not the en_us value, word for word");
                }
            } else if (value.equals(TerrainRefusalNotice.FALLBACK)) {
                problems.add(where + ": not translated");
            }
        }
        assertEquals(List.of(), problems);
    }
}
