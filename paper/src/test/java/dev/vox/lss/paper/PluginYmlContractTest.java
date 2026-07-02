package dev.vox.lss.paper;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@code paper/src/main/resources/plugin.yml}, parsed with Bukkit's own
 * {@link YamlConfiguration} from the SOURCE tree: the classpath copy has {@code ${version}}
 * already expanded by processResources, which would make the placeholder pin vacuous.
 * (Expansion inside the built jar is release_check.py's job.) A typo in any of these fields
 * is invisible until a real Paper server refuses to load — or silently mis-loads — the
 * plugin: an unresolvable {@code main} or wrong {@code api-version} aborts plugin load, a
 * renamed plugin moves the {@code plugins/LodServerSupport/} data folder the config and
 * soak staging rely on, and {@code folia-supported} must stay {@code true} now that the
 * pump runs on GlobalRegionScheduler — removing it silently drops Folia support.
 */
class PluginYmlContractTest {

    private static String rawText;
    private static YamlConfiguration yml;

    @BeforeAll
    static void load() throws Exception {
        rawText = Files.readString(locate("paper/src/main/resources/plugin.yml"));
        yml = new YamlConfiguration();
        // 'lss.admin' is a literal permission key; the default '.' separator would split it.
        yml.options().pathSeparator('/');
        yml.loadFromString(rawText);
    }

    /** Walks up from the working dir (paper/ under Gradle, the repo root elsewhere). */
    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative + " above " + Path.of("").toAbsolutePath());
    }

    @Test
    void mainClassResolvesToThePluginEntryPoint() throws Exception {
        String main = yml.getString("main");
        assertNotNull(main, "plugin.yml must declare main");
        Class<?> resolved = Class.forName(main);
        assertEquals(LSSPaperPlugin.class, resolved, "main must point at the real entry point");
        assertTrue(JavaPlugin.class.isAssignableFrom(resolved), "main must be a JavaPlugin");
    }

    @Test
    void pluginNameIsTheDataFolderContract() {
        // PaperConfig lives at plugins/LodServerSupport/lss-server-config.json and the soak
        // harness stages configs by that path; renaming the plugin silently orphans both.
        assertEquals("LodServerSupport", yml.getString("name"));
    }

    @Test
    void lsslodCommandIsDeclaredBehindTheAdminPermission() {
        assertNotNull(yml.getConfigurationSection("commands/lsslod"),
                "PaperCommands is wired to getCommand(\"lsslod\"); without this section the command vanishes");
        assertEquals("lss.admin", yml.getString("commands/lsslod/permission"));
    }

    @Test
    void adminPermissionDefaultsToOp() {
        assertEquals("op", yml.getString("permissions/lss.admin/default"),
                "stats/diag expose server internals; the permission must not default to everyone");
    }

    @Test
    void apiVersionMatchesTheDevBundleMinecraftVersion() throws Exception {
        String apiVersion = yml.getString("api-version");
        assertNotNull(apiVersion);

        // 1.20.1 backport: Bukkit <= 1.20.4 only accepts two-part api-versions
        // (CraftMagicNumbers.SUPPORTED_API = ["1.13" .. "1.20"]); a three-part value is an
        // InvalidPluginException at load. api-version is therefore the dev bundle's
        // minecraft version truncated to major.minor.
        var bundle = Pattern.compile("paperweight\\.paperDevBundle\\('([^']+)'\\)")
                .matcher(Files.readString(locate("paper/build.gradle")));
        assertTrue(bundle.find(), "paper/build.gradle must declare paperweight.paperDevBundle('...')");
        var mcVersion = bundle.group(1).split("-")[0];             // 1.20.1
        var parts = mcVersion.split("\\.");
        assertEquals(parts[0] + "." + parts[1], apiVersion,
                "api-version must be the dev bundle's minecraft version truncated to major.minor");
    }

    @Test
    void foliaSupportedIsAbsentOnThisLine() {
        // 1.20.1 line: Folia 1.20.1 exists only as frozen 2023 alphas (build 17) whose
        // async-load semantics defeat the generation path — observed live: 1 of 1840
        // generations completed; every other chunk was null after the load completed, even
        // on the region completion thread, and cross-region ticket pinning is forbidden.
        // Shipping the flag would let the plugin ENABLE and then silently fail its core
        // job, so this line drops the claim: without the flag Folia refuses the plugin
        // loudly at load. (main and the 1.21.11 line keep folia-supported: true.)
        assertFalse(rawText.contains("folia-supported"),
                "folia-supported must NOT be declared on the 1.20.1 line (frozen-alpha Folia"
                        + " breaks generation; absence makes Folia reject the plugin loudly)");
    }

    @Test
    void versionIsTheProcessResourcesPlaceholder() {
        // The literal placeholder must survive in the source: processResources expands it at
        // build time, and release_check.py (HD-045) verifies the expansion in the built jar.
        assertEquals("${version}", yml.getString("version"));
        assertTrue(rawText.contains("version: '${version}'"),
                "the placeholder must stay single-quoted so the YAML stays parseable pre-expansion");
    }

    @Test
    void pluginYmlShipsOnTheClasspath() {
        assertNotNull(LSSPaperPlugin.class.getResource("/plugin.yml"),
                "plugin.yml must be packaged at the jar root or Paper will not recognize the plugin");
    }
}
