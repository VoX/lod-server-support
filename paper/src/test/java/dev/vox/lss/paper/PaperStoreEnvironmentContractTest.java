package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper twin of {@code StoreEnvironmentContractTest} (C1, review-fixes round): the
 * production Environment call must pass the registry fingerprint — the 6/7-arg
 * convenience ctors default it to "", so dropping the argument compiles and silently
 * disables the R2-M3 registry guard with every suite green.
 */
class PaperStoreEnvironmentContractTest {

    private static Path serviceSource() {
        var moduleRelative = Path.of(
                "src/main/java/dev/vox/lss/paper/PaperRequestProcessingService.java");
        if (Files.exists(moduleRelative)) return moduleRelative;
        return Path.of("paper").resolve(moduleRelative);
    }

    @Test
    void environmentConstructionPassesTheRegistryFingerprint() throws Exception {
        String source = Files.readString(serviceSource());
        var call = Pattern.compile(
                "new dev\\.vox\\.lss\\.common\\.store\\.SqliteLodStore\\.Environment\\("
                        + "[^;]*RegistryFingerprint\\.of\\(\\s*"
                        + "registryIds\\.states\\(\\),\\s*registryIds\\.biomes\\(\\)\\)\\s*,\\s*"
                        + "[^;]*RegistryFingerprint\\.contentOf\\(\\s*"
                        + "registryIds\\.states\\(\\),\\s*registryIds\\.biomes\\(\\)\\)\\s*\\)",
                Pattern.DOTALL);
        assertTrue(call.matcher(source).find(),
                "the production Environment must derive the ordered fingerprint via"
                        + " RegistryFingerprint.of and the content one via .contentOf"
                        + " (dropping, reordering, or swapping the delegations compiles"
                        + " and disables the registry guard / permutation tolerance)");
        assertTrue(source.contains("var registryIds = storeRegistryIdentity(server);"),
                "both fingerprints must come from ONE registry walk (plan §3.2)");
    }
}
