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

        var props = new Properties();
        props.load(new StringReader(Files.readString(locate("gradle.properties"))));
        assertEquals(props.getProperty("minecraft_version"), apiVersion,
                "api-version must move in lockstep with the minecraft_version the build targets");

        var bundle = Pattern.compile("paperweight\\.paperDevBundle\\('([^']+)'\\)")
                .matcher(Files.readString(locate("paper/build.gradle")));
        assertTrue(bundle.find(), "paper/build.gradle must declare paperweight.paperDevBundle('...')");
        String devBundle = bundle.group(1);
        assertTrue(devBundle.startsWith(apiVersion + ".") || devBundle.startsWith(apiVersion + "-R"),
                "dev bundle " + devBundle + " must be a build of api-version " + apiVersion
                        + " (new '<mc>.build.N' or old '<mc>-R0.1-SNAPSHOT' scheme)");
    }

    @Test
    void foliaSupportedIsDeclared() {
        // Flipped 2026-07-02: the pump now runs on GlobalRegionScheduler and lifecycle
        // ingress is mailboxed (FoliaWiringContractTest pins both), so the old reason for
        // the absence pin — BukkitRunnable main-thread assumptions — is gone. Folia refuses
        // to load plugins without this flag; removing it silently drops Folia support.
        assertTrue(yml.getBoolean("folia-supported"),
                "folia-supported: true is required for Folia to load the plugin");
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
