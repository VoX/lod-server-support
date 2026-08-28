package dev.vox.lss.paper;

import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Paper twin of the fabric {@code ToolchainContractTest} (V-1/T3c — hoisted from
 * the 1.21.11 line): pins the compiled class-file target against line.env's declared
 * Java version and anchors gradle.properties' {@code minecraft_version} to the MC
 * artifact paperweight actually resolved. See the fabric twin's javadoc for the armor
 * chain this closes.
 */
class ToolchainContractTest {

    private static Properties lineEnv;
    private static Properties gradleProps;

    @BeforeAll
    static void load() throws Exception {
        // Line-aware (single-branch consolidation): -Dlss.line names the active line.
        String line = System.getProperty("lss.line", "26.2");
        lineEnv = loadProps(locate("lines/" + line + "/line.env"));
        gradleProps = loadProps(locate("gradle.properties"));
        gradleProps.putAll(loadProps(locate("lines/" + line + "/line.properties")));
        SharedConstants.tryDetectVersion();
    }

    private static Properties loadProps(Path path) throws Exception {
        var props = new Properties();
        for (String line : Files.readAllLines(path)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int eq = s.indexOf('=');
            if (eq > 0) props.setProperty(s.substring(0, eq), s.substring(eq + 1));
        }
        return props;
    }

    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative);
    }

    @Test
    void compiledClassFileTargetsTheLineJavaVersion() throws Exception {
        int expectedMajor = 44 + Integer.parseInt(lineEnv.getProperty("LINE_JAVA_VERSION"));
        try (InputStream in = PaperConfig.class.getResourceAsStream("/dev/vox/lss/paper/PaperConfig.class")) {
            assertNotNull(in, "class bytes not found for PaperConfig");
            byte[] header = in.readNBytes(8);
            int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
            assertEquals(expectedMajor, major,
                    "paper must compile at --release " + lineEnv.getProperty("LINE_JAVA_VERSION")
                            + " (line.env LINE_JAVA_VERSION; Java N = class major - 44)");
        }
    }

    @Test
    void resolvedMinecraftArtifactMatchesTheDeclaredLine() {
        assertEquals(gradleProps.getProperty("minecraft_version"),
                SharedConstants.getCurrentVersion().name(),
                "gradle.properties minecraft_version must equal the MC artifact paperweight "
                        + "resolved — the line.env↔gradle.properties↔artifact chain's last link");
    }
}
