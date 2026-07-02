package dev.vox.lss;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A gametest class that is not listed in the {@code fabric-gametest} entrypoint of
 * fabric/src/gametest/resources/fabric.mod.json compiles cleanly and silently never runs —
 * this exact failure already happened once (three classes committed dead; "47 of 55
 * &#64;GameTest ran" while CI stayed green, and one dead class carried a wrong assertion
 * discovered only on registration). This Tier-1 test makes the listing load-bearing:
 * every source file with &#64;GameTest methods must appear in the entrypoint, every
 * FabricClientGameTest under the client entrypoint, and no entry may be stale.
 */
class GameTestEntrypointContractTest {

    private static final Pattern GAMETEST_ANNOTATION = Pattern.compile("(?m)^\\s*@GameTest\\b");
    private static final String TEST_PACKAGE = "dev.vox.lss.test";

    @Test
    void everyGameTestClassIsRegisteredInTheEntrypointAndViceVersa() throws IOException {
        Path sources = locate("fabric/src/gametest/java/dev/vox/lss/test");
        JsonObject entrypoints = JsonParser.parseString(Files.readString(
                        locate("fabric/src/gametest/resources/fabric.mod.json")))
                .getAsJsonObject().getAsJsonObject("entrypoints");
        Set<String> listedServer = names(entrypoints, "fabric-gametest");
        Set<String> listedClient = names(entrypoints, "fabric-client-gametest");

        Set<String> foundServer = new TreeSet<>();
        Set<String> foundClient = new TreeSet<>();
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                String fqcn = TEST_PACKAGE + "." + name.substring(0, name.length() - ".java".length());
                String source = Files.readString(file);
                if (GAMETEST_ANNOTATION.matcher(source).find()) {
                    foundServer.add(fqcn);
                }
                if (source.contains("implements FabricClientGameTest")) {
                    foundClient.add(fqcn);
                }
            }
        }
        assertFalse(foundServer.isEmpty(), "no @GameTest sources found — scan is broken");

        assertEquals(foundServer, listedServer,
                "fabric-gametest entrypoint must list exactly the classes with @GameTest methods"
                        + " — an unlisted class compiles but NEVER runs; a stale entry breaks the runner");
        assertEquals(foundClient, listedClient,
                "fabric-client-gametest entrypoint must list exactly the FabricClientGameTest classes");
    }

    private static Set<String> names(JsonObject entrypoints, String key) {
        Set<String> out = new TreeSet<>();
        entrypoints.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above "
                + Path.of("").toAbsolutePath());
    }
}
