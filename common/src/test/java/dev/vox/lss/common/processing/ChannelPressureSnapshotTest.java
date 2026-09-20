package dev.vox.lss.common.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The probe-snapshot degrade VALUE pin (mega plan R-2 / YIELD §1.1, owned by the tracer
 * PR): a bare-lambda probe — every legacy shape, {@code NO_SIGNAL}, and every test rig —
 * snapshots to {@code {pendingOutboundBytes(), UNKNOWN_MARK, UNKNOWN}}. The transport
 * yield's behavioral pin ("legacy probe → never yields") lands with the yield PR; this
 * test pins the VALUES that make that behavior structural.
 */
class ChannelPressureSnapshotTest {

    @Test
    void bareLambdaProbeSnapshotsToPendingPlusUnknowns() {
        ChannelPressureProbe probe = () -> 42_000L;
        var snap = probe.snapshot();
        assertEquals(42_000L, snap.pendingBytes(),
                "the default snapshot must carry the probe's own pending reading");
        assertEquals(ChannelPressureProbe.Snapshot.UNKNOWN_MARK, snap.highWaterMark(),
                "a lambda probe has no mark to report — UNKNOWN_MARK, never a guess");
        assertEquals(ChannelPressureProbe.Writability.UNKNOWN, snap.writable(),
                "writability UNKNOWN is the fail-safe direction: consumers must not yield on it");
    }

    @Test
    void noSignalProbeSnapshotsToNoSignalPlusUnknowns() {
        var snap = ChannelPressureProbe.NO_SIGNAL.snapshot();
        assertEquals(-1L, snap.pendingBytes());
        assertEquals(ChannelPressureProbe.Snapshot.UNKNOWN_MARK, snap.highWaterMark());
        assertEquals(ChannelPressureProbe.Writability.UNKNOWN, snap.writable());
    }
}
