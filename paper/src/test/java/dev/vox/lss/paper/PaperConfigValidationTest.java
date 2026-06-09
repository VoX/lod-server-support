package dev.vox.lss.paper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins PaperConfig.validate(): the shared ServerConfigBase clamps must run through the Paper
 * subclass (i.e. the super.validate() call survives) and the Paper-only updateEvents null guard
 * must replace null with an empty list. The Fabric ConfigValidationTest exercises the same
 * clamps only via LSSServerConfig, so without this test the Paper override is unpinned.
 */
class PaperConfigValidationTest {

    @Test
    void validateClampsInheritedFieldsAndGuardsUpdateEvents() {
        PaperConfig c = new PaperConfig();
        c.lodDistanceChunks = 999;
        c.syncOnLoadConcurrencyLimitPerPlayer = 0;
        c.updateEvents = null;
        c.validate();
        assertEquals(512, c.lodDistanceChunks);                  // LSSConstants.MAX_LOD_DISTANCE via super.validate()
        assertEquals(1, c.syncOnLoadConcurrencyLimitPerPlayer);  // LSSConstants.MIN_CONCURRENCY_LIMIT via super.validate()
        assertEquals(List.of(), c.updateEvents);                 // Paper-only null guard
    }
}
