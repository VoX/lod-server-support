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
 * soak staging rely on, and {@code folia-supported} must stay DECLARED on the 26.1 line —
 * Folia publishes real MC 26.1.2 builds (this line never lost them, unlike 26.2 during its
 * pre-26.2-1 gap), so the guarded failure is a jar that silently STOPS loading on Folia.
 * (R-7 direction-flip note for future re-ports: a fresh cut inherits main's PRESENCE pin
 * and must actively re-derive the per-line flavor — presence is only correct on lines
 * Folia actually publishes for.)
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
    void exactlyOneCommandIsDeclared() {
        // registerCommands() resolves the command name as getCommands().keySet().first(), so a
        // single declared command is what makes that deterministic (and lets the VSS repackage
        // rename the key without a code fork). A second command would make the pick ambiguous.
        assertEquals(1, yml.getConfigurationSection("commands").getKeys(false).size(),
                "the plugin must declare exactly one command (registerCommands picks the first)");
    }

    @Test
    void adminPermissionDefaultsToOp() {
        assertEquals("op", yml.getString("permissions/lss.admin/default"),
                "stats/diag expose server internals; the permission must not default to everyone");
    }

    @Test
    void bothFarPlayerPrivacyNodesAreDeclaredDefaultFalse() {
        // Review-wave V-M1 (the load-bearing dual declaration): the enforcement
        // dual-checks lss.* OR vss.*, and Bukkit resolves an UNDECLARED node to the op
        // default — deleting EITHER declaration (or flipping a default) silently hides
        // every op from far players on the jars where that spelling is undeclared.
        // Both spellings ship in BOTH jars; the vssJar rewrite must not rename them.
        assertEquals("false", yml.getString("permissions/lss.farplayers.hidden/default"),
                "lss.farplayers.hidden must be declared default false");
        assertEquals("false", yml.getString("permissions/vss.farplayers.hidden/default"),
                "vss.farplayers.hidden must be declared default false");
        // The constants' HOME is common since far-player-render-hardening-plan.md WI-7a (the
        // Fabric/NeoForge snapshot reads and the NeoForge node registration enforce the same
        // strings) — this is the one pin tying the declaration to all three modules.
        assertEquals("lss.farplayers.hidden", dev.vox.lss.common.LSSPermissions.FARPLAYERS_HIDDEN_LSS);
        assertEquals("vss.farplayers.hidden", dev.vox.lss.common.LSSPermissions.FARPLAYERS_HIDDEN_VSS);
    }

    @Test
    void bothServiceUseNodesAreDeclaredDefaultTrue() {
        // The service gate (requireServicePermission) requires lss.use AND vss.use — the
        // deny model's De Morgan mirror, so one negative grant on either spelling bites. Same
        // load-bearing dual declaration as the far-player nodes above: Bukkit resolves an
        // UNDECLARED node to the OP default, so deleting either declaration would leave the
        // missing spelling silently op-only on the jar that lost it — and an LSS<->VSS jar
        // swap would drop every grant.
        //
        // The VALUE is the user decision of 2026-08-25: `true`, not `op`. Everyone holds the
        // node unless an admin says otherwise, so arming requireServicePermission on its own
        // denies NOBODY — it is the original behavior, and the gate becomes a deny tool
        // driven by explicit negative grants. Flipping this to `op` (or `false`) would black
        // out every non-op on the day an operator first tries the switch.
        assertEquals("true", yml.getString("permissions/lss.use/default"),
                "lss.use must be declared default true — arming the gate alone must serve everyone as before");
        assertEquals("true", yml.getString("permissions/vss.use/default"),
                "vss.use must be declared default true — arming the gate alone must serve everyone as before");
        assertEquals(LSSPaperPlugin.PERMISSION_SERVICE_LSS, "lss.use",
                "the declared node and the enforced constant must be the same string");
        assertEquals(LSSPaperPlugin.PERMISSION_SERVICE_VSS, "vss.use",
                "the declared node and the enforced constant must be the same string");
        // The constants' HOME moved to common (service-permission-gate-plan.md §2.1):
        // xplat's gate and NeoForge's node registration enforce the same strings, and
        // this is the one pin tying all three modules' spellings together.
        assertEquals("lss.use", dev.vox.lss.common.LSSPermissions.SERVICE_LSS);
        assertEquals("vss.use", dev.vox.lss.common.LSSPermissions.SERVICE_VSS);
    }

    @Test
    void apiVersionMatchesTheDevBundleMinecraftVersion() throws Exception {
        // V-1/P3: the SOURCE carries the template token; the value IS gradle.properties'
        // minecraft_version by construction (paper processResources), so the lockstep
        // assertion moves to the data side.
        assertEquals("${api_version}", yml.getString("api-version"),
                "plugin.yml's api-version must stay templated from minecraft_version");

        var props = new Properties();
        props.load(new StringReader(Files.readString(locate("gradle.properties"))));
        String apiVersion = props.getProperty("minecraft_version");
        assertNotNull(apiVersion);

        var bundle = Pattern.compile("paperweight\\.paperDevBundle\\('([^']+)'\\)")
                .matcher(Files.readString(locate("paper/build.gradle")));
        assertTrue(bundle.find(), "paper/build.gradle must declare paperweight.paperDevBundle('...')");
        assertTrue(bundle.group(1).startsWith(apiVersion + "."),
                "dev bundle " + bundle.group(1) + " must be a build of api-version " + apiVersion);
    }

    @Test
    void foliaSupportedIsDeclaredBecauseFoliaPublishes2612() {
        // 26.1-LINE FLAVOR (D3 fresh re-port, R-7 direction-flip check applied): Folia
        // publishes real MC 26.1.2 builds, so declaring the flag is correct on THIS line —
        // the guarded failure is a jar that silently stops loading on Folia. The pin was
        // inherited from main (26.2, where Folia's 26.2-1 restored it 2026-08-01) and
        // re-derived rather than assumed: presence would be WRONG on a line Folia does not
        // publish for. FoliaWiringContractTest still pins the wiring (no legacy scheduler,
        // lifecycle through the mailbox).
        assertTrue(yml.contains("folia-supported"),
                "folia-supported must be declared — Folia ships 26.1.2 builds and the"
                        + " single jar serves Paper and Folia");
        assertTrue(yml.getBoolean("folia-supported"),
                "...and it must be true; a false/absent flag makes Folia refuse the jar");
        assertTrue(rawText.contains("folia-supported: true"),
                "release_check.py greps the RAW line, so the source must carry that exact form");
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
    void viaVersionIsASoftdependForTheClassloaderWarning() {
        // C5 (review m7): without the softdepend, Spigot's PluginClassLoader still
        // resolves com.viaversion... through the global group (the probe works), but
        // logs the "not a depend, softdepend or loadbefore" warning on the first
        // handshake with Via installed. A HARD depend would be wrong: the guard is
        // fail-open and the plugin must load without Via.
        var softdepend = yml.getStringList("softdepend");
        org.junit.jupiter.api.Assertions.assertTrue(softdepend.contains("ViaVersion"),
                "ViaVersion must be a softdepend (never a depend)");
        org.junit.jupiter.api.Assertions.assertNull(yml.get("depend"),
                "no hard depends — the plugin loads standalone");
    }

    @Test
    void pluginYmlShipsOnTheClasspath() {
        assertNotNull(LSSPaperPlugin.class.getResource("/plugin.yml"),
                "plugin.yml must be packaged at the jar root or Paper will not recognize the plugin");
    }
}
