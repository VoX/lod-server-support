package dev.vox.lss.compat;

import me.lucko.fabric.api.permissions.v0.Permissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Fabric permission rung (plan §2.1): by-shape resolution against the
 * real-package-name stub, and the fail-open containment — absent, unresolvable,
 * and throwing providers all answer the caller's default.
 */
class FabricPermissionsBridgeTest {

    @BeforeEach
    @AfterEach
    void reset() {
        FabricPermissionsBridge.resetForTest();
        Permissions.BEHAVIOR[0] = null;
    }

    @Test
    void resolvesTheEntityOverloadByShapeAndDelegates() {
        Permissions.BEHAVIOR[0] = (node, def) -> node.equals("lss.use");
        assertTrue(FabricPermissionsBridge.present());
        assertEquals("fabric-permissions-api", FabricPermissionsBridge.providerToken());
        assertTrue(FabricPermissionsBridge.check(null, "lss.use", false),
                "the stub's Entity overload answered — the decoys were skipped");
        assertFalse(FabricPermissionsBridge.check(null, "vss.use", true),
                "a provider's explicit answer overrides the default");
    }

    @Test
    void anAbsentApiAnswersTheDefaultQuietly() {
        FabricPermissionsBridge.classResolver = name -> {
            throw new ClassNotFoundException(name);
        };
        assertFalse(FabricPermissionsBridge.present());
        assertEquals("none", FabricPermissionsBridge.providerToken());
        assertTrue(FabricPermissionsBridge.check(null, "lss.use", true),
                "no provider = the declared default — serve everyone");
        assertFalse(FabricPermissionsBridge.check(null, "lss.use", false),
                "…whatever the default is; the bridge never invents an answer");
    }

    @Test
    void aDriftedApiSurfaceAnswersTheDefault() {
        FabricPermissionsBridge.classResolver = name -> String.class; // no check() at all
        assertFalse(FabricPermissionsBridge.present());
        assertTrue(FabricPermissionsBridge.check(null, "lss.use", true));
    }

    @Test
    void aThrowingProviderIsContainedToTheDefault() {
        Permissions.BEHAVIOR[0] = (node, def) -> {
            throw new IllegalStateException("permission backend mid-boot");
        };
        assertTrue(FabricPermissionsBridge.present());
        assertTrue(FabricPermissionsBridge.check(null, "lss.use", true),
                "a throwing provider answers the default — fail-open, never up the stack");
        // And it recovers per call once the provider heals.
        Permissions.BEHAVIOR[0] = (node, def) -> false;
        assertFalse(FabricPermissionsBridge.check(null, "lss.use", true));
    }

    @Test
    void resolutionLatchesPerJvmUntilTheTestReset() {
        FabricPermissionsBridge.classResolver = name -> {
            throw new ClassNotFoundException(name);
        };
        assertFalse(FabricPermissionsBridge.present());
        FabricPermissionsBridge.classResolver = Class::forName;
        assertFalse(FabricPermissionsBridge.present(),
                "the -1 latch holds (one resolve per JVM) until the seam reset");
        FabricPermissionsBridge.resetForTest();
        assertTrue(FabricPermissionsBridge.present());
    }
}
