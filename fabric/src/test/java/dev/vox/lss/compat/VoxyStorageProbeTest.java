package dev.vox.lss.compat;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.commonImpl.VoxyCommon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alias-corroboration storage probe (plan §2.2): its OWN two-handle domain
 * against the real-package-name stubs — every failure shape reads as null (fail
 * closed at the corroboration), and its resolution is independent of the reset
 * domain in BOTH directions.
 */
class VoxyStorageProbeTest {

    @BeforeEach
    void setUp() {
        VoxyCompat.resetSeams();
        VoxyCompat.resetStorageProbeForTest();
        VoxyCompat.resetResetDomainForTest();
        VoxyCommon.reset();
    }

    @AfterEach
    void tearDown() {
        VoxyCompat.resetSeams();
        VoxyCompat.resetStorageProbeForTest();
        VoxyCompat.resetResetDomainForTest();
        VoxyCommon.reset();
    }

    private static VoxyClientInstance liveInstance(Path root) {
        var client = new VoxyClientInstance();
        client.storageBasePath = root;
        VoxyCommon.instance = client;
        return client;
    }

    @Test
    void observesTheLiveRootsDirectoryName() {
        liveInstance(Path.of("game", ".voxy", "saves", "play.example.com"));
        assertEquals("play.example.com", VoxyCompat.observeStorageDirName());
    }

    @Test
    void noLiveInstanceIsUnobservable() {
        VoxyCommon.instance = null;
        assertNull(VoxyCompat.observeStorageDirName());
        // And it recovers per call once an instance exists — no dead-latch.
        liveInstance(Path.of("saves", "alt.example.com"));
        assertEquals("alt.example.com", VoxyCompat.observeStorageDirName());
    }

    @Test
    void aNullStorageRootIsUnobservable() {
        liveInstance(null);
        assertNull(VoxyCompat.observeStorageDirName());
    }

    @Test
    void aNonClientInstanceIsContainedToUnobservable() {
        // Production getInstance() always yields the client subclass; a drifted Voxy
        // that does not must degrade to null (ClassCastException contained), never throw.
        VoxyCommon.instance = new me.cortex.voxy.commonImpl.VoxyInstance();
        assertNull(VoxyCompat.observeStorageDirName());
    }

    @Test
    void anUnresolvableProbeNeverCostsTheIngestBridge() {
        // probe-dead-with-live-ingest (plan §4.2): only the CLIENT class is missing —
        // the probe domain fails, the ingest bridge's own domain is untouched.
        VoxyCompat.classResolver = name -> {
            if (name.equals("me.cortex.voxy.client.VoxyClientInstance")) {
                throw new ClassNotFoundException(name);
            }
            return Class.forName(name);
        };
        assertNull(VoxyCompat.observeStorageDirName());
        VoxyCompat.reportSink = (dimension, chunkX, chunkZ) -> {};
        VoxyCompat.consumerRegistrar = consumer -> {};
        assertTrue(VoxyCompat.init(), "the ingest bridge resolves independently");
    }

    @Test
    void aDeadResetDomainLeavesTheProbeAlive() {
        // reset-domain-dead-probe-alive (plan §4.2): both renderer-holder rungs missing
        // kills initResetDomain; the two-handle probe still observes.
        VoxyCompat.classResolver = name -> {
            if (name.startsWith("me.cortex.voxy.client.core.I")) {
                throw new ClassNotFoundException(name);
            }
            return Class.forName(name);
        };
        assertFalse(VoxyCompat.initResetDomain());
        liveInstance(Path.of("saves", "still-observable"));
        assertEquals("still-observable", VoxyCompat.observeStorageDirName());
    }

    @Test
    void aFailedResolutionLatchesForTheSession() {
        VoxyCompat.classResolver = name -> {
            throw new ClassNotFoundException(name);
        };
        assertNull(VoxyCompat.observeStorageDirName());
        // Even with the resolver healed, the -1 latch holds until the seam reset —
        // one warn per session, not one per manager build.
        VoxyCompat.resetSeams();
        assertNull(VoxyCompat.observeStorageDirName());
        VoxyCompat.resetStorageProbeForTest();
        liveInstance(Path.of("saves", "after-reset"));
        assertEquals("after-reset", VoxyCompat.observeStorageDirName());
    }
}
