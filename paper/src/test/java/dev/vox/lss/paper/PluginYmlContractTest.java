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
 * soak staging rely on, and {@code folia-supported} must stay DECLARED on the 26.2 line —
 * Folia ships a real 26.2 build (26.2-1, 2026-07-28) and all four Folia soak scenarios
 * passed against it (2026-08-01), so the guarded failure is now a jar that silently
 * STOPS loading on Folia. (The flag was absent while no Folia 26.2 existed; that
 * direction inverts on fresh re-ports to lines Folia does not publish for.)
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
        // 1.21.1 line: this line's dev bundle uses the OLD '<mc>-R0.1-SNAPSHOT' scheme
        // (26.x moved to '<mc>.build.N') — accept both, the 1.21.8 support branch's
        // recorded form. The api-version VALUE stays the templated three-part
        // minecraft_version (granular api-version is valid Paper 1.20.5+; 1.21.8
        // records the same three-part shape).
        assertTrue(bundle.group(1).startsWith(apiVersion + ".")
                        || bundle.group(1).startsWith(apiVersion + "-R"),
                "dev bundle " + bundle.group(1) + " must be a build of api-version " + apiVersion
                        + " (new '<mc>.build.N' or old '<mc>-R0.1-SNAPSHOT' scheme)");
    }

    @Test
    void foliaSupportedStaysAbsentBecauseFoliaHasNo1211Build() {
        // 1.21.1 line: the DIRECTION of this pin is re-derived per line (surfaces row 7).
        // Folia never published a 1.21.1 build (its 1.21 family starts at 1.21.4 —
        // fill.papermc.io verified 2026-08-15), so the flag must stay ABSENT: declaring it
        // would claim support for a platform that does not exist (the same reasoning that
        // kept it absent on 26.2 during 2026-07-19..2026-08-01, inverted). release_check.py
        // carries the matching absence check. FoliaWiringContractTest still pins the wiring
        // (no legacy scheduler, lifecycle through the mailbox) — the code paths stay, inert.
        // Declaration-shaped match only (line-anchored key, release_check's regex): the
        // explanatory COMMENT above the absence names the token and must not trip this.
        assertTrue(!rawText.lines().anyMatch(l -> l.strip().startsWith("folia-supported:")),
                "folia-supported must stay ABSENT on the 1.21.1 line — no Folia build exists"
                        + " for this MC version, and declaring support for a nonexistent"
                        + " platform is the exact failure the v0.7.0-era drop guarded");
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
