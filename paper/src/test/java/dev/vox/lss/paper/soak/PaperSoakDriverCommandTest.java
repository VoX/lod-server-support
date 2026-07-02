package dev.vox.lss.paper.soak;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the driver's Folia command mapping: Folia unregisters /save-all, so timeline save-all
 * steps become acknowledged no-ops there (staging compensates with aggressive autosave and
 * the end-of-scenario halt's full save) — and nothing else may be swallowed.
 */
class PaperSoakDriverCommandTest {

    @Test
    void saveAllMapsToNoOpOnFoliaOnly() {
        assertTrue(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-all", true));
        assertTrue(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-all flush", true));
        assertTrue(PaperSoakScenarioDriver.mapsToFoliaNoOp("/save-all flush", true));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-all", false));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("setblock 0 64 0 stone", true));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("gamerule doDaylightCycle false", true));
        assertFalse(PaperSoakScenarioDriver.mapsToFoliaNoOp("save-allx", true),
                "prefix match must be word-bounded");
    }
}
