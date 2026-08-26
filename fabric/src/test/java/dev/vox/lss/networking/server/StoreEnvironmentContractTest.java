package dev.vox.lss.networking.server;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 (review-fixes round): nothing in CI constructs the store through the real service
 * wiring, so the Environment assembly was guarded only by manual burn-in — and the
 * 6/7-arg Environment convenience ctors default {@code registryFingerprint} to "",
 * meaning DROPPING the argument compiles and silently disables the R2-M3 registry
 * guard. Source-regex (the LanHookContractTest precedent — the service classloads MC
 * types fabric-loader-junit cannot construct): the production Environment call must
 * pass the full shape with the fingerprint, and the mask snapshot must carry the B11
 * transient rung.
 */
class StoreEnvironmentContractTest {

    /** Survives both the Gradle CWD (module dir) and an IDE repo-root CWD. */
    private static Path serviceSource() {
        return dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/server/RequestProcessingService.java");
    }

    @Test
    void environmentConstructionPassesTheRegistryFingerprint() throws Exception {
        String source = Files.readString(serviceSource());
        var call = Pattern.compile(
                "new dev\\.vox\\.lss\\.common\\.store\\.SqliteLodStore\\.Environment\\("
                        + "[^;]*storeRegistryFingerprint\\(server\\),\\s*"
                        + "storeRegistryContentFingerprint\\(server\\)\\)",
                Pattern.DOTALL);
        assertTrue(call.matcher(source).find(),
                "the production Environment must be built WITH storeRegistryFingerprint"
                        + " AND storeRegistryContentFingerprint (the convenience ctors"
                        + " default them to \"\" — dropping either argument compiles and"
                        + " silently disables the registry guard / the v0.13.1"
                        + " permutation tolerance, which treats an empty content"
                        + " fingerprint as unprovable and rebuilds every boot)");
    }

    @Test
    void maskSnapshotCarriesTheTransientProbeRung() throws Exception {
        String source = Files.readString(serviceSource());
        assertTrue(source.contains("isTerminalForActive"),
                "the store's mask-fingerprint snapshot must consult probe terminality"
                        + " (review B11 — fingerprinting the transient fallback could"
                        + " keep engine-masked rows under a config label)");
        assertTrue(source.contains("\"transient:\""),
                "the non-terminal snapshot must be the per-boot nonce sentinel");
    }
}
