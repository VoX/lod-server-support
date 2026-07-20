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
 * soak staging rely on, and {@code folia-supported} must stay ABSENT on the 26.2 line —
 * declaring it would auto-load the plugin on a future Folia 26.2 with live-unvalidated
 * Folia paths (the Folia soaks are pending upstream; re-add the flag with that validation).
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
                "registerCommands() reads the declared command name from plugin.yml; without this "
                        + "section the command vanishes (the VSS repackage rewrites the key to vsslod)");
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

        var props = new Properties();
        props.load(new StringReader(Files.readString(locate("gradle.properties"))));
        assertEquals(props.getProperty("minecraft_version"), apiVersion,
                "api-version must move in lockstep with the minecraft_version the build targets");

        var bundle = Pattern.compile("paperweight\\.paperDevBundle\\('([^']+)'\\)")
                .matcher(Files.readString(locate("paper/build.gradle")));
        assertTrue(bundle.find(), "paper/build.gradle must declare paperweight.paperDevBundle('...')");
        assertTrue(bundle.group(1).startsWith(apiVersion + "."),
                "dev bundle " + bundle.group(1) + " must be a build of api-version " + apiVersion);
    }

    @Test
    void foliaSupportedIsAbsentOnThe262Line() {
        // Flipped again 2026-07-19 (was declared-true since 2026-07-02): no Folia build
        // exists for MC 26.2, so the Folia code paths (regionized probing, hold-release,
        // lifecycle mailbox — FoliaWiringContractTest still pins their wiring) have never
        // run against a real 26.2 Folia. Declaring the flag would auto-load v0.7.0 jars the
        // day Folia ships 26.2, with a known deferred Folia-only race outstanding
        // (docs/planning/v0.7.1-candidates.md #1). Re-add `folia-supported: true` together
        // with the SOAK_PLATFORM=folia validation once Folia publishes a 26.2 build.
        assertFalse(yml.contains("folia-supported"),
                "folia-supported must stay absent until Folia 26.2 exists and the Folia soak"
                        + " passes — declaring it ships live-unvalidated Folia paths");
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
