package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the Sodium 0.8+ options-page wiring on NeoForge (LSSConfigMenu's javadoc):
 * sodium-neoforge's ConfigLoaderForge discovers config users by reading the
 * {@code sodium:config_api_user} MODPROPERTY from neoforge.mods.toml and
 * reflectively instantiating the named class as a {@code ConfigEntryPoint}. Four
 * legs, each guarding a distinct silent-failure mode: a dropped TOML property
 * (page silently absent), a renamed walker class or one that stops implementing
 * the interface / loses its no-arg constructor (Sodium warn-and-skips — no page,
 * no crash), and a twin that stops WALKING THE CATALOG (hand-written options or a
 * dropped visibility filter would drift from the Fabric page with every gate
 * green — the fabric ClientMenuEntrypointContractTest twin leg). The stubs
 * themselves must never SHIP — release_check's NEOFORGE_FORBIDDEN pins
 * {@code net/caffeinemc/} out of the jars — and their fidelity to the REAL
 * sodium-neoforge artifact is {@link SodiumNeoGoldenParityTest}'s job.
 */
class SodiumConfigApiContractTest {

    private static final String WALKER = "dev.vox.lss.config.LSSConfigMenu";
    private static final String ENTRY_POINT_IFACE = "net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint";

    @Test
    void theTomlDeclaresTheWalkerAsTheConfigApiUser() throws Exception {
        try (InputStream in = SodiumConfigApiContractTest.class
                .getResourceAsStream("/META-INF/neoforge.mods.toml")) {
            assertNotNull(in, "neoforge.mods.toml on the test classpath");
            String toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(toml.contains("[modproperties.lss]"),
                    "the modproperties table must exist — ConfigLoaderForge reads it");
            assertTrue(toml.contains("\"sodium:config_api_user\"=\"" + WALKER + "\""),
                    "the property must name the walker verbatim");
        }
    }

    @Test
    void theWalkerImplementsTheEntryPointWithANoArgConstructor() throws Exception {
        // The walker cannot be REFLECTED on here: any member access links the class,
        // and verification resolves the MC types (Component, ResourceLocation) its
        // method bodies reference — absent from this plain-JUnit classpath. So the
        // two walker pins are read straight off the classfile (ClassfileSurface):
        // the interfaces list and the ctor table need no linking and cannot drift
        // from what ConfigLoaderForge will see.
        ClassfileSurface.Surface walker = parse("/" + WALKER.replace('.', '/') + ".class");
        assertTrue(walker.interfaces().contains(ENTRY_POINT_IFACE.replace('.', '/')),
                "LSSConfigMenu must implement " + ENTRY_POINT_IFACE
                        + " (the stub mirrors the real interface; the runtime link is by name)");
        assertTrue(walker.methods().stream().anyMatch(m ->
                        m.name().equals("<init>") && m.descriptor().equals("()V")
                                && (m.access() & ClassfileSurface.ACC_PUBLIC) != 0),
                "a PUBLIC no-arg ctor — stricter than Sodium's getDeclaredConstructor+"
                        + "setAccessible route strictly needs, kept strict so instantiation "
                        + "never depends on setAccessible succeeding");
        // The stub interface must carry BOTH methods with the real shapes: the abstract
        // late hook (we implement it) and the default early hook (we inherit it). The
        // stubs reference only stub types, so reflection is safe on THEM.
        Class<?> iface = Class.forName(ENTRY_POINT_IFACE);
        assertEquals(2, iface.getMethods().length, "exactly the two entry-point methods");
        assertTrue(iface.getMethod("registerConfigEarly",
                        Class.forName("net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder"))
                .isDefault(), "registerConfigEarly stays a default method");
    }

    /**
     * The fabric {@code ClientMenuEntrypointContractTest.theModernWalkerCarriesNoHandWrittenOptions}
     * leg, twinned for the NeoForge walker source (that test is path-scoped to fabric/):
     * the commit's whole premise is "catalog walk identical to Fabric's", so the twin must
     * keep iterating the catalog, keep the MenuContext visibility filter, and carry no
     * hand-written option keys or ids.
     */
    @Test
    void theTwinKeepsWalkingTheCatalog() throws IOException {
        String src = stripComments(Files.readString(
                locate("neoforge/src/main/java/dev/vox/lss/config/LSSConfigMenu.java")));
        assertTrue(src.contains("ClientOptionCatalog.pages()"), "the twin must iterate the catalog");
        assertTrue(src.contains(".visibility().test("),
                "the MenuContext visibility filter must stay — it is what hides the "
                        + "far-player render options on NeoForge");
        assertTrue(!src.contains("\"lss.config."),
                "translation keys come from the catalog, never literals");
        assertTrue(!src.replace("\"lss:icon.png\"", "").contains(".parse(\"lss:"),
                "option ids come from the catalog (the icon fallback is the one literal)");
    }

    private static ClassfileSurface.Surface parse(String resource) throws IOException {
        try (InputStream raw = SodiumConfigApiContractTest.class.getResourceAsStream(resource)) {
            assertNotNull(raw, resource + " on the test classpath");
            return ClassfileSurface.parse(raw);
        }
    }

    /** Repo-root-relative locate: walk up from the working dir until the path exists. */
    static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("cannot locate " + repoRelative
                + " above " + Path.of("").toAbsolutePath());
    }

    static String stripComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }
}
