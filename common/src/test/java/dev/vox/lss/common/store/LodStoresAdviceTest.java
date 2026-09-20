package dev.vox.lss.common.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the store-off startup recommendation (v0.9.1, user request): shown exactly when
 * an ENABLED, non-Folia server runs without the store — under the 2026-08-08 split default
 * (fresh installs generate "on"; an absent key means "off") the line reaches explicit
 * opt-outs AND upgraded installs whose file lacks the key, where it is how the feature
 * reaches the admin at all. The message must name the one key that turns the whole
 * feature on, the disk implication, and the bound.
 */
class LodStoresAdviceTest {

    @Test
    void enabledNonFoliaServerWithoutTheStoreGetsTheRecommendation() {
        String advice = LodStores.offRecommendationOrNull(true, false);
        assertTrue(advice != null && advice.contains("\"lodStore\": \"on\""),
                "the one-key enable must be quoted verbatim (canonical spelling since the"
                        + " 2026-08-08 rework): " + advice);
        assertTrue(advice.contains("lss-server-config.json"),
                "the admin must be told WHERE: " + advice);
        assertTrue(advice.contains("doubles the size of your world directory"),
                "the disk tradeoff must be stated: " + advice);
    }

    @Test
    void disabledServerGetsNoRecommendation() {
        // enabled=false means no store would open even if configured full — recommending
        // a feature into a disabled service is noise.
        assertNull(LodStores.offRecommendationOrNull(false, false));
    }

    @Test
    void foliaGetsNoRecommendation() {
        // The store is unvalidated on Folia and PaperConfig.validate() WARNS on an
        // explicit full there — the recommendation must not contradict the warning.
        assertNull(LodStores.offRecommendationOrNull(true, true));
        assertNull(LodStores.offRecommendationOrNull(false, true));
    }
}
