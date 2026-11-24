package dev.vox.lss.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vox.lss.api.LSSApi;
import dev.vox.lss.api.VoxelColumnConsumer;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * "Is a map the only reader?" (post-0.17 review): map ingestion is on in new settings files, so
 * a client with only a map mod downloads LODs. Two decisions must tell that apart from Voxy (or
 * another LSSApi reader): the LAN host self-handshake (map ingestion is multiplayer-only — a
 * map-only host must not generate a LOD disc around itself) and the column cache's map-only mark
 * (a map-only download must not answer up_to_date for a later Voxy install).
 */
class ModCompatMapConsumersTest {
    private final VoxelColumnConsumer other = (level, dimension, x, z, data) -> {};
    private XaeroSession bridge;
    private boolean enabled = true;
    private boolean sessionActive = true;
    private boolean serverAllows = true;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @AfterEach
    void tearDown() {
        LSSApi.removeColumnConsumer(this.other);
        if (this.bridge != null) LSSApi.removeColumnConsumer(this.bridge.consumer);
        XaeroSession.resetFacadeForTest();
    }

    private void installXaero() throws Exception {
        this.bridge = new XaeroSession(XaeroBindings.resolve(Class::forName), null,
                () -> this.enabled, () -> this.sessionActive, LSSApi::registerColumnConsumer,
                LSSApi::removeColumnConsumer, () -> true, (d, x, z) -> {}, () -> this.serverAllows);
        XaeroSession.instance = this.bridge;
        this.bridge.maybeRegister();
    }

    @Test
    void noConsumersIsNeitherMapOnlyNorAnotherReader() {
        assertFalse(LSSApi.hasVoxelConsumers(), "premise: no consumer leaked from another suite");
        assertFalse(ModCompat.isMapOnlyIngestion());
        assertFalse(ModCompat.hasNonMapConsumers());
    }

    @Test
    void aMapAloneIsMapOnly() throws Exception {
        installXaero();
        assertTrue(ModCompat.isMapOnlyIngestion());
        assertFalse(ModCompat.hasNonMapConsumers(), "a map-only LAN host stays silent");
    }

    @Test
    void anotherReaderBesideTheMapIsNotMapOnly() throws Exception {
        installXaero();
        LSSApi.registerColumnConsumer(this.other);
        assertFalse(ModCompat.isMapOnlyIngestion());
        assertTrue(ModCompat.hasNonMapConsumers());
    }

    @Test
    void aReaderWithoutAnyMapIsNotMapOnly() {
        LSSApi.registerColumnConsumer(this.other);
        assertFalse(ModCompat.isMapOnlyIngestion());
        assertTrue(ModCompat.hasNonMapConsumers());
    }

    /** The audience of a map fill (issue #380): the map backends' own consumers, nobody else. */
    @Test
    void onlyAMapBackendsConsumerIsAMapConsumer() throws Exception {
        assertFalse(ModCompat.isMapConsumer(null));
        assertFalse(ModCompat.isMapConsumer(this.other), "no map backend exists yet");
        installXaero();
        LSSApi.registerColumnConsumer(this.other);
        assertTrue(ModCompat.isMapConsumer(this.bridge.consumer));
        assertFalse(ModCompat.isMapConsumer(this.other), "Voxy, or another mod's reader, is not a map");

        var got = new java.util.ArrayList<VoxelColumnConsumer>();
        for (var consumer : new VoxelColumnConsumer[]{this.bridge.consumer, this.other}) {
            if (ModCompat.isMapConsumer(consumer)) got.add(consumer);
        }
        assertTrue(got.size() == 1 && got.get(0) == this.bridge.consumer);
    }

    /** The predicate behind the map's debt: is a column that arrives now written to a map?
     *  Every way a map bridge can be idle reads as "no", so the column is owed. */
    @Test
    void aMapIsBeingWrittenOnlyWhileItsBridgeTakesColumns() throws Exception {
        assertFalse(ModCompat.isMapWriting(), "no map mod");
        installXaero();
        assertTrue(ModCompat.isMapWriting());

        this.serverAllows = false; // the server asked for no map ingestion
        assertFalse(ModCompat.isMapWriting());
        this.serverAllows = true;
        this.sessionActive = false; // this server has no LSS session
        assertFalse(ModCompat.isMapWriting());
        this.sessionActive = true;
        this.enabled = false; // Map Ingestion switched off
        assertFalse(ModCompat.isMapWriting());
        this.enabled = true;
        this.bridge.settingsWritesOff = true; // Xaero's own two map-writing switches
        assertFalse(ModCompat.isMapWriting());
        this.bridge.settingsWritesOff = false;
        this.bridge.dead = true; // the bridge gave up on Xaero's internals
        assertFalse(ModCompat.isMapWriting());
        this.bridge.dead = false;
        assertTrue(ModCompat.isMapWriting(), "premise: back to writing");

        this.bridge.registered = false; // never registered this session
        assertFalse(ModCompat.isMapWriting());
        this.bridge.registered = true;
    }

    @Test
    void theLanHostHandshakeAndTheCacheMarkUseThesePredicates() throws Exception {
        String glue = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ClientNetGlue.java"));
        int trigger = glue.indexOf("public static void triggerHostHandshake()");
        String body = glue.substring(trigger, glue.indexOf("\n    }\n", trigger));
        assertTrue(body.contains("hostHandshakeHasConsumer()"), body);
        assertFalse(body.contains("LSSApi.hasVoxelConsumers()"), "a map consumer alone must not trigger it");
        assertTrue(glue.contains("return dev.vox.lss.compat.ModCompat.hasNonMapConsumers();"));
        String cache = Files.readString(dev.vox.lss.testutil.SourcePaths.mainSource(
                "dev/vox/lss/networking/client/ColumnCacheStore.java"));
        assertTrue(cache.contains("dev.vox.lss.compat.ModCompat::isMapOnlyIngestion"));
    }
}
