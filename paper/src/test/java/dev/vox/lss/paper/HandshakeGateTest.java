package dev.vox.lss.paper;

import dev.vox.lss.common.HandshakeGate;
import dev.vox.lss.common.HandshakeGate.Outcome;
import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirror of the Fabric {@code dev.vox.lss.networking.server.HandshakeGateTest}: pins the
 * shared handshake decision ladder from the Paper classpath, where it is consumed by
 * {@link LSSPaperPlugin}, so the reply/registration policy cannot drift per-platform.
 *
 * <p>The critical invariant: a version-skewed client must receive NO reply. The
 * mismatched client's SessionConfig codec has a different field layout on the same
 * channel id, so any frame the server sends decodes as a DecoderException and kicks
 * the player — replying here would kick every outdated client on join.
 */
class HandshakeGateTest {

    private static final int V = LSSConstants.PROTOCOL_VERSION;
    private static final int VOXEL_CAPS = LSSConstants.CAPABILITY_VOXEL_COLUMNS;

    @Test
    void happyPathRepliesAndRegisters() {
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS, true, true);
        assertEquals(Outcome.REGISTER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertTrue(d.effectiveEnabled());
        assertTrue(d.registerPlayer());
    }

    @Test
    void newerClientVersionSendsNothing() {
        var d = HandshakeGate.evaluate(V + 1, VOXEL_CAPS, true, true);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void olderClientVersionSendsNothing() {
        var d = HandshakeGate.evaluate(V - 1, VOXEL_CAPS, true, true);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
        assertFalse(d.registerPlayer());
    }

    @Test
    void versionCheckOutranksCapabilityAndEnabledChecks() {
        // If the caps or enabled checks ever ran first, this would classify as
        // NO_CONSUMER/DISABLED and reply — kicking the skewed client.
        var d = HandshakeGate.evaluate(V + 1, 0, false, false);
        assertEquals(Outcome.VERSION_MISMATCH, d.outcome());
        assertFalse(d.sendSessionConfig());
    }

    @Test
    void zeroCapabilitiesRepliesButNeverRegisters() {
        var d = HandshakeGate.evaluate(V, 0, true, true);
        assertEquals(Outcome.NO_CONSUMER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertTrue(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void zeroCapabilitiesOnDisabledServerStillClassifiesNoConsumer() {
        // Capability check outranks the enabled check: a consumer-less client is
        // logged as NO_CONSUMER whether or not LSS is enabled.
        var d = HandshakeGate.evaluate(V, 0, false, true);
        assertEquals(Outcome.NO_CONSUMER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void disabledConfigAdvertisesDisabledWithoutRegistering() {
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS, false, true);
        assertEquals(Outcome.DISABLED, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void absentServiceAdvertisesDisabledWithoutRegistering() {
        // registerPlayer()==false is what keeps call sites from dereferencing the
        // null service even though the config says enabled.
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS, true, false);
        assertEquals(Outcome.DISABLED, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.effectiveEnabled());
        assertFalse(d.registerPlayer());
    }

    @Test
    void futureCapabilityBitsAlongsideVoxelColumnsStillRegister() {
        var d = HandshakeGate.evaluate(V, VOXEL_CAPS | 0x7E, true, true);
        assertEquals(Outcome.REGISTER, d.outcome());
        assertTrue(d.registerPlayer());
    }

    @Test
    void foreignCapabilityBitsWithoutVoxelColumnsDoNotRegister() {
        var d = HandshakeGate.evaluate(V, 0x7E & ~VOXEL_CAPS, true, true);
        assertEquals(Outcome.NO_CONSUMER, d.outcome());
        assertTrue(d.sendSessionConfig());
        assertFalse(d.registerPlayer());
    }

    @Test
    void negativeProtocolVersionSendsNothing() {
        // Guards a relational rewrite of the version gate (e.g. `version > PROTOCOL_VERSION`
        // as an only-newer check): hostile or corrupt negative versions must classify
        // exactly like any other skew — no reply, no registration.
        for (int version : new int[]{-1, Integer.MIN_VALUE}) {
            var d = HandshakeGate.evaluate(version, VOXEL_CAPS, true, true);
            assertEquals(Outcome.VERSION_MISMATCH, d.outcome(), "version " + version);
            assertFalse(d.sendSessionConfig());
            assertFalse(d.registerPlayer());
        }
    }

    @Test
    void fullBitmaskCapabilitiesStillRegister() {
        // 0xFFFFFFFF is -1 as an int: a sign-sensitive capability check (`caps > 0 && ...`)
        // would refuse a client advertising every bit. Only bit 0 may matter.
        var d = HandshakeGate.evaluate(V, 0xFFFFFFFF, true, true);
        assertEquals(Outcome.REGISTER, d.outcome());
        assertTrue(d.registerPlayer());
    }
}
