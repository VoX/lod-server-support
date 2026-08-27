package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The corroboration ladder (plan §2.2), driven by the voxy-extra FIXTURE — the observed
 * directory names below are what {@code VoxyClientInstanceMixin.voxyExtra$lodMirrorCheck}
 * actually produces (transcribed at the v1 plan review, §8): a MEMBER connection under
 * an active mirror observes the group's first entry VERBATIM ({@code resolveSibling
 * (listFirst)}); the CANONICAL connection observes Voxy's own {@code :→_} munge of the
 * address; an inactive mirror observes the munged CONNECT address.
 */
class AliasCorroborationTest {

    private static final String CANONICAL = "play.example.com";
    private static final String ALT = "alt.example.com";

    private static AliasCorroboration.Result eval(boolean voxy, boolean xaero,
                                                  String observed, String connect) {
        return AliasCorroboration.evaluate(voxy, xaero, observed, connect, CANONICAL);
    }

    private static void assertApplies(AliasCorroboration.Result r, String token) {
        assertEquals(AliasCorroboration.Outcome.APPLY, r.outcome());
        assertEquals(token, r.token());
        assertNull(r.warn(), "APPLY shapes are silent");
    }

    private static void assertFallsBack(AliasCorroboration.Result r, String token) {
        assertEquals(AliasCorroboration.Outcome.FALL_BACK, r.outcome());
        assertEquals(token, r.token());
        assertNotNull(r.warn(), "every fallback names its reason to the user");
    }

    // ---- the fixture: voxy-extra mirror ACTIVE ----

    @Test
    void aMemberConnectionUnderTheMirrorObservesTheCanonicalVerbatimAndApplies() {
        // voxy-extra: path.resolveSibling(listFirst) — the raw first entry, NO munge.
        assertApplies(eval(true, false, CANONICAL, ALT), "voxy-corroborated");
    }

    @Test
    void theCanonicalConnectionObservesVoxysOwnMungeAndApplies() {
        // Connecting to the canonical itself: the mirror leaves Voxy's derivation —
        // ip.replace(":", "_") — which for a port-free canonical is the canonical.
        assertApplies(eval(true, false,
                AliasCorroboration.voxyMunge(CANONICAL), CANONICAL), "voxy-corroborated");
    }

    @Test
    void theMungeComparisonIsRealNotRedundant() {
        // A canonical carrying ':' is rejected at config validation, but the pure ladder
        // must still compare against the munge correctly (defense in depth).
        var r = AliasCorroboration.evaluate(true, false, "srv_25566", ALT, "srv:25566");
        assertEquals(AliasCorroboration.Outcome.APPLY, r.outcome());
    }

    // ---- the fixture: mirror ABSENT / misconfigured ----

    @Test
    void aMemberConnectionWithoutTheMirrorObservesItsOwnAddressAndFallsBack() {
        assertFallsBack(eval(true, false, AliasCorroboration.voxyMunge(ALT), ALT),
                "voxy-unaliased");
        assertTrue(eval(true, false, ALT, ALT).warn().contains("FIRST entry"),
                "the actionable warn tells the user to align both lists' first entries");
    }

    @Test
    void anUnrecognizedObservedDirFallsBack() {
        assertFallsBack(eval(true, false, "somewhere-else", ALT), "voxy-mismatch");
    }

    @Test
    void matchingIsCaseSensitiveLikeTheMixinsEquals() {
        // voxy-extra matches with String.equals — a case-differing observation is NOT
        // corroboration (the on-disk directory genuinely differs).
        assertFallsBack(eval(true, false, "Play.Example.Com", ALT), "voxy-mismatch");
    }

    // ---- the guards ----

    @Test
    void unprobeableVoxyFailsClosed() {
        assertFallsBack(eval(true, false, null, ALT), "voxy-unprobeable");
    }

    @Test
    void noVoxyConsumerAppliesAsConfigured() {
        assertApplies(eval(false, false, null, ALT), "no-consumer");
    }

    @Test
    void anArmedXaeroBridgeFallsBackEvenWhenVoxyCorroborates() {
        assertFallsBack(eval(true, true, CANONICAL, ALT), "xaero-armed");
        assertFallsBack(eval(false, true, null, ALT), "xaero-armed",
                "the Xaero gate outranks the no-consumer APPLY too");
    }

    private static void assertFallsBack(AliasCorroboration.Result r, String token, String why) {
        assertEquals(AliasCorroboration.Outcome.FALL_BACK, r.outcome(), why);
        assertEquals(token, r.token(), why);
        assertNotNull(r.warn(), "every fallback names its reason to the user (" + why + ")");
    }

    // ---- the munge itself ----

    @Test
    void voxyMungeReplacesEveryColon() {
        assertEquals("a.com_25565", AliasCorroboration.voxyMunge("a.com:25565"));
        assertEquals("plain", AliasCorroboration.voxyMunge("plain"));
    }
}
