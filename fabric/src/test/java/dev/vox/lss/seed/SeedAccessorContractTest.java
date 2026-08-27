package dev.vox.lss.seed;

import dev.vox.lss.testutil.SourcePaths;
import net.minecraft.world.level.biome.BiomeManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The world axis (plan §2.3): the {@code @Accessor} that reads the obfuscated seed must target a field that really
 * exists, and must be registered on BOTH loaders.
 *
 * <p>Both halves exist because both failure modes are SILENT.
 *
 * <ul>
 *   <li><b>A renamed field.</b> A source-text {@code contains} check would stay green
 *       through an MC bump that renames {@code biomeZoomSeed}, while the mixin quietly
 *       fails to apply and every session falls back to the bare address bucket. So the field is
 *       pinned REFLECTIVELY against the real class, the
 *       {@code ChannelAccessorContractTest} shape — the source-text assertions below only
 *       pin that the accessor names the field explicitly, which is a different claim.</li>
 *   <li><b>A missing NeoForge entry.</b> Nothing crashes and nothing warns: the mixin never
 *       applies, the {@code instanceof} in {@link ClientWorldSeed} never matches, and
 *       NeoForge keeps the bare address bucket while Fabric moves to the world
 *       sub-bucket — same config, same server, two caches, no error anywhere. Pinning the FILES is also what lets
 *       this assert NeoForge behaviour without running NeoForge.</li>
 * </ul>
 */
class SeedAccessorContractTest {

    private static final String MIXIN = "AccessorBiomeManager";

    /** The {@code "client"} array of a mixin config, as raw text. */
    private static String clientNode(Path config) throws IOException {
        String text = Files.readString(config);
        int start = text.indexOf("\"client\"");
        assertTrue(start >= 0, config + " has no client mixin node");
        int open = text.indexOf('[', start);
        int close = text.indexOf(']', open);
        assertTrue(open >= 0 && close > open, config + " has a malformed client node");
        return text.substring(open, close + 1);
    }

    @Test
    void theSeedAccessorTargetsAFieldThatActuallyExists() throws Exception {
        var field = BiomeManager.class.getDeclaredField("biomeZoomSeed");
        assertEquals(long.class, field.getType(),
                "vanilla's BiomeManager.biomeZoomSeed changed type — the accessor's return"
                        + " type and ClientWorldSeed must move with it");
    }

    @Test
    void theSeedAccessorIsRegisteredOnFabric() throws IOException {
        Path config = SourcePaths.repoFile("fabric/src/main/resources/lss.mixins.json");
        assertTrue(clientNode(config).contains("\"" + MIXIN + "\""),
                MIXIN + " missing from the client node of " + config);
    }

    @Test
    void theSeedAccessorIsRegisteredOnNeoForge() throws IOException {
        Path config = SourcePaths.repoFile("neoforge/src/main/resources/lss.neoforge.mixins.json");
        assertTrue(clientNode(config).contains("\"" + MIXIN + "\""),
                MIXIN + " missing from the client node of " + config + " — NeoForge would "
                        + "silently fall back to the address cache key while Fabric uses "
                        + "the world sub-bucket");
    }

    @Test
    void theAccessorNamesTheFieldExplicitlyAndCarriesTheLssPrefix() throws IOException {
        String source = Files.readString(
                SourcePaths.mainSource("dev/vox/lss/mixin/AccessorBiomeManager.java"));
        assertTrue(source.contains("@Mixin(BiomeManager.class)"), source);
        assertTrue(source.contains("@Accessor(\"biomeZoomSeed\")"),
                "name the field rather than inferring it from the method name: " + source);
        // Mixin adds the method to the TARGET class, so the repo prefixes accessor methods
        // to keep them from colliding with a vanilla method (AccessorClientPacketListener
        // states the rule).
        assertTrue(source.contains("long lss$getBiomeZoomSeed();"),
                "accessor methods are lss$-prefixed in this repo: " + source);
    }

    @Test
    void theAccessorLivesInXplatSoBothLoadersCompileIt() {
        Path source = SourcePaths.mainSource("dev/vox/lss/mixin/AccessorBiomeManager.java");
        assertTrue(source.toString().replace('\\', '/').contains("/xplat/src/main/java/"),
                "a loader-local accessor cannot be registered by the other loader: " + source);
    }
}
