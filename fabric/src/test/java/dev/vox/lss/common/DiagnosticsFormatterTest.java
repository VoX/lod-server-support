package dev.vox.lss.common;

import dev.vox.lss.common.processing.AbstractChunkDiskReader;
import dev.vox.lss.common.processing.AbstractPlayerRequestState;
import dev.vox.lss.common.processing.ProcessingDiagnostics;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-string contract for the shared /lsslod output: the exact stats line, the exact
 * diag line list and order, the unit-boundary behavior of the format helpers, and the
 * {@code collectDiagData} wiring — including the null-disk-reader guard that must render
 * "disabled" instead of throwing at the admin. Both platform command handlers emit these
 * strings verbatim, so any change here is a user-visible output change.
 *
 * <p>The formatter uses default-locale {@code String.format}; goldens are pinned under
 * {@link Locale#ROOT} so the expected decimal separators hold on any machine.
 */
class DiagnosticsFormatterTest {

    private static Locale previousLocale;

    @BeforeAll
    static void pinFormatLocale() {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.ROOT);
    }

    @AfterAll
    static void restoreLocale() {
        Locale.setDefault(previousLocale);
    }

    /** Stub state with pairwise-distinct values so a transposed format argument cannot pass. */
    private static final class StubState extends AbstractPlayerRequestState<Object> {
        private final String name;
        private final long sections;
        private final long bytes;
        private final long requests;
        private final int sync;
        private final int gen;
        private final int sendQueue;

        StubState(String name, boolean handshook, long sections, long bytes,
                  int sync, int gen, int sendQueue, long requests) {
            super(UUID.randomUUID(), 8, 8);
            this.name = name;
            this.sections = sections;
            this.bytes = bytes;
            this.sync = sync;
            this.gen = gen;
            this.sendQueue = sendQueue;
            this.requests = requests;
            if (handshook) markHandshakeComplete();
        }

        @Override public String getPlayerName() { return this.name; }
        @Override public long getTotalSectionsSent() { return this.sections; }
        @Override public long getTotalBytesSent() { return this.bytes; }
        @Override public int getHeldSyncSlots() { return this.sync; }
        @Override public int getHeldGenSlots() { return this.gen; }
        @Override public int getSendQueueSize() { return this.sendQueue; }
        @Override public long getTotalRequestsReceived() { return this.requests; }
    }

    // ---- /lsslod stats line (CG-017) ----

    @Test
    void statsLineGoldenFromNonZeroStubbedState() {
        var state = new StubState("Alex", true, 1234, 2048, 3, 2, 7, 4321);

        assertEquals(
                "Alex: handshake=yes, sent=1234 sections (2.0 KB), pending_sync=3, pending_gen=2, "
                        + "send_queue=7, requests=4321",
                DiagnosticsFormatter.formatStatsLine(state));
    }

    @Test
    void statsLineRendersIncompleteHandshakeAsNo() {
        var state = new StubState("Bea", false, 0, 0, 0, 0, 0, 0);

        assertEquals(
                "Bea: handshake=no, sent=0 sections (0 B), pending_sync=0, pending_gen=0, "
                        + "send_queue=0, requests=0",
                DiagnosticsFormatter.formatStatsLine(state));
    }

    // ---- /lsslod diag line list (CG-018) ----

    @Test
    void diagGoldenLineListAndOrder() {
        var d = new DiagnosticsFormatter.DiagData(
                true, 24,
                2048, 1_048_576,
                100, 5000, 10_485_760,
                11, 33, 44, 55, 66,
                22,
                "sent=9, disk=1/2",
                "submitted=5, completed=5",
                "active=1/32", true,
                7, 3,
                2_097_152,
                512,
                List.of(new DiagnosticsFormatter.PlayerDiag("Steve", 3, 4000, 2, 1, 2000, 4096)));

        assertEquals(List.of(
                "=== LSS LOD Diagnostics ===",
                "Config: enabled=true, lodDist=24, bw/player=2.0 KB/s, bw/global=1.0 MB/s",
                "Throughput: sent=5000 (10.0 MB), rate=50 sections/s (102.4 KB/s), uptime=1m 40s",
                "Sources (total): in_mem=11, disk=22, up_to_date=33, gen=44, re_resolved=55, grace_skipped=66",
                "Sources (tick): sent=9, disk=1/2",
                "DiskReader: submitted=5, completed=5",
                "Generation: active=1/32, order_gated=7, inversions=3",
                "Bandwidth: 512 B/s / 1.0 MB/s global (2.0 MB total)",
                "  Steve: sq=3/4000, psync=2, pgen=1, sent=2000 (4.0 KB), rate=20/s"
        ), DiagnosticsFormatter.formatDiagnostics(d));
    }

    @Test
    void diagV16LineRendersBetweenGenerationAndBandwidthOnlyWhenPresent() {
        var d = new DiagnosticsFormatter.DiagData(
                true, 24,
                2048, 1_048_576,
                100, 5000, 10_485_760,
                11, 33, 44, 55, 66,
                22,
                "sent=9, disk=1/2",
                "submitted=5, completed=5",
                "active=1/32", true,
                7, 3,
                2_097_152,
                512,
                List.of());

        var withoutShim = DiagnosticsFormatter.formatDiagnostics(d);
        assertTrue(withoutShim.stream().noneMatch(l -> l.startsWith("V16Compat")),
                "an untouched shim (null line) must add nothing");

        var withShim = DiagnosticsFormatter.formatDiagnostics(d.withV16Line(
                "V16Compat: clients=1, redeclares=9, overflow_bounced=0, grace_discarded=0"));
        assertTrue(withShim.contains(
                "Sources (total): in_mem=11, disk=22, up_to_date=33, gen=44, re_resolved=55, grace_skipped=66"),
                "the withV16Line copy constructor must preserve every counter: " + withShim);
        int genIdx = indexOfPrefix(withShim, "Generation:");
        int v16Idx = indexOfPrefix(withShim, "V16Compat:");
        int bwIdx = indexOfPrefix(withShim, "Bandwidth:");
        assertTrue(genIdx < v16Idx && v16Idx < bwIdx,
                "the v16 line sits between Generation and Bandwidth: " + withShim);
        assertEquals(withoutShim.size() + 1, withShim.size());
    }

    @Test
    void diagXrayLineRendersAfterV16SlotOnlyWhenPresent() {
        var d = new DiagnosticsFormatter.DiagData(
                true, 24,
                2048, 1_048_576,
                100, 5000, 10_485_760,
                11, 33, 44, 55, 66,
                22,
                "sent=9, disk=1/2",
                "submitted=5, completed=5",
                "active=1/32", true,
                7, 3,
                2_097_152,
                512,
                List.of());

        var without = DiagnosticsFormatter.formatDiagnostics(d);
        assertTrue(without.stream().noneMatch(l -> l.startsWith("Xray")),
                "a null xray line must add nothing (old constructions/tests keep their shape)");

        var with = DiagnosticsFormatter.formatDiagnostics(
                d.withXrayLine("Xray: active=antixray-mod, masked_sections=42"));
        assertTrue(with.contains(
                "Sources (total): in_mem=11, disk=22, up_to_date=33, gen=44, re_resolved=55, grace_skipped=66"),
                "the withXrayLine copy constructor must preserve every counter: " + with);
        int genIdx = indexOfPrefix(with, "Generation:");
        int xrayIdx = indexOfPrefix(with, "Xray:");
        int bwIdx = indexOfPrefix(with, "Bandwidth:");
        assertTrue(genIdx < xrayIdx && xrayIdx < bwIdx,
                "the xray line sits between Generation and Bandwidth: " + with);
        assertEquals(without.size() + 1, with.size());
        assertEquals("Xray: active=antixray-mod, masked_sections=42", with.get(xrayIdx));

        // Both optional lines together: v16 first (its historical slot), then xray.
        var both = DiagnosticsFormatter.formatDiagnostics(
                d.withV16Line("V16Compat: clients=1").withXrayLine("Xray: active=config, masked_sections=0"));
        assertTrue(indexOfPrefix(both, "V16Compat:") < indexOfPrefix(both, "Xray:"),
                "line order must stay v16 then xray: " + both);
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) return i;
        }
        throw new AssertionError("no line starts with '" + prefix + "': " + lines);
    }

    @Test
    void diagRendersEnabledFalseAndDisabledGenerationAndZeroUptime() {
        // enabled=false still renders the full ladder; generationEnabled=false (the
        // null-generation-service wiring) renders "Generation: disabled"; uptime=0 must
        // not divide by zero in the rate math.
        var d = new DiagnosticsFormatter.DiagData(
                false, 4,
                1024, 1024,
                0, 0, 0,
                0, 0, 0, 0, 0,
                0,
                "idle",
                "idle",
                null, false,
                0, 0,
                0,
                0,
                List.of());

        assertEquals(List.of(
                "=== LSS LOD Diagnostics ===",
                "Config: enabled=false, lodDist=4, bw/player=1.0 KB/s, bw/global=1.0 KB/s",
                "Throughput: sent=0 (0 B), rate=0 sections/s (0 B/s), uptime=0s",
                "Sources (total): in_mem=0, disk=0, up_to_date=0, gen=0, re_resolved=0, grace_skipped=0",
                "Sources (tick): idle",
                "DiskReader: idle",
                "Generation: disabled",
                "Bandwidth: 0 B/s / 1.0 KB/s global (0 B total)"
        ), DiagnosticsFormatter.formatDiagnostics(d));
    }

    // ---- collectDiagData wiring (SP-068 + CG-018 collect leg) ----

    @Test
    void collectDiagDataWithNullDiskReaderRendersDisabledInsteadOfThrowing() {
        // Regression pin for the NPE: before the guard, a null reader blew up the diag
        // command at diskReader.getDiag(). Null reader => zero completed reads + "disabled".
        var data = DiagnosticsFormatter.collectDiagData(
                true, 8,
                1024, 2048, 100,
                5, "tick", 256,
                new ProcessingDiagnostics(), null,
                new SharedBandwidthLimiter(4096),
                null,
                List.of());

        assertEquals(0, data.diskCompleted());
        assertEquals("disabled", data.diskReaderDiagnostics());
        assertFalse(data.generationEnabled(), "null generation diagnostics means generation disabled");

        var lines = DiagnosticsFormatter.formatDiagnostics(data);
        assertTrue(lines.contains("DiskReader: disabled"), "null reader renders as disabled: " + lines);
        assertTrue(lines.contains("Sources (total): in_mem=0, disk=0, up_to_date=0, gen=0, re_resolved=0, grace_skipped=0"),
                "null reader contributes zero disk reads: " + lines);
    }

    @Test
    void collectDiagDataWiresCountersSumsAndPlayerRows() {
        var reader = new AbstractChunkDiskReader(1) {};
        reader.getDiag().recordSuccess();
        reader.getDiag().recordSuccess();
        reader.getDiag().recordSuccess();

        var pd = new ProcessingDiagnostics();
        pd.incrementInMemory();
        pd.incrementInMemory();
        pd.incrementUpToDate();
        pd.incrementGenDrained();
        pd.incrementReResolved();
        pd.incrementReResolved();
        pd.incrementReResolved();
        pd.incrementReResolved();
        pd.incrementGraceSkipped();
        pd.incrementGraceSkipped();
        pd.incrementGraceSkipped();
        pd.incrementGraceSkipped();
        pd.incrementGraceSkipped();

        var limiter = new SharedBandwidthLimiter(1 << 20);
        limiter.recordSend(777);

        var data = DiagnosticsFormatter.collectDiagData(
                true, 16,
                111, 222, 333,
                10, "tick-string", 444,
                pd, reader,
                limiter,
                "gen-running",
                List.of(new StubState("A", true, 100, 1000, 1, 0, 4, 10),
                        new StubState("B", false, 50, 500, 0, 2, 6, 20)));

        assertEquals(150, data.totalSent(), "totalSent sums every state's sections");
        assertEquals(1500, data.totalBytes(), "totalBytes sums every state's bytes");
        assertEquals(2, data.cumInMem());
        assertEquals(1, data.cumUtd());
        assertEquals(1, data.cumGen());
        assertEquals(4, data.cumReResolved());
        assertEquals(5, data.cumGraceSkipped());
        assertEquals(3, data.diskCompleted(), "wired from getDiag().getSuccessfulReadCount()");
        assertEquals(reader.getDiagnostics() + ", memo_hits=0", data.diskReaderDiagnostics(),
                "the DiskReader line carries the miss-memo hit counter (A5's virtual not-founds)");
        assertEquals("tick-string", data.tickDiagnostics());
        assertEquals(444, data.bwWindowRate());
        assertEquals(777, data.bwTotal(), "wired from the limiter's total, not the state sums");
        assertTrue(data.generationEnabled());
        assertEquals("gen-running", data.generationDiagnostics());

        assertEquals(2, data.players().size());
        var a = data.players().get(0);
        assertEquals("A", a.name());
        assertEquals(4, a.sendQueue());
        assertEquals(333, a.maxSendQueue(), "maxSendQueue comes from sendQueueLimitPerPlayer");
        assertEquals(1, a.pendingSync());
        assertEquals(0, a.pendingGen());
        assertEquals(100, a.sent());
        assertEquals(1000, a.bytes());
    }

    // ---- format helper boundaries (CG-019) ----

    @Test
    void formatBytesUnitBoundaries() {
        assertEquals("0 B", DiagnosticsFormatter.formatBytes(0));
        assertEquals("1023 B", DiagnosticsFormatter.formatBytes(1023));
        assertEquals("1.0 KB", DiagnosticsFormatter.formatBytes(1024));
        // Last value before each unit switch rounds up into the next unit's magnitude
        // while keeping the smaller unit's name — pinned as-is.
        assertEquals("1024.0 KB", DiagnosticsFormatter.formatBytes(1024 * 1024 - 1));
        assertEquals("1.0 MB", DiagnosticsFormatter.formatBytes(1024 * 1024));
        assertEquals("1024.0 MB", DiagnosticsFormatter.formatBytes(1024L * 1024 * 1024 - 1));
        assertEquals("1.00 GB", DiagnosticsFormatter.formatBytes(1024L * 1024 * 1024));
    }

    @Test
    void formatRateThousandBoundary() {
        assertEquals("0", DiagnosticsFormatter.formatRate(0));
        assertEquals("999", DiagnosticsFormatter.formatRate(999));
        // Just below the K threshold HALF_UP-rounds to a K-less "1000" — pinned as-is.
        assertEquals("1000", DiagnosticsFormatter.formatRate(999.5));
        assertEquals("1.0K", DiagnosticsFormatter.formatRate(1000));
        assertEquals("1.5K", DiagnosticsFormatter.formatRate(1500));
    }

    @Test
    void formatUptimeUnitBoundaries() {
        assertEquals("0s", DiagnosticsFormatter.formatUptime(0));
        assertEquals("59s", DiagnosticsFormatter.formatUptime(59));
        assertEquals("1m 0s", DiagnosticsFormatter.formatUptime(60));
        assertEquals("59m 59s", DiagnosticsFormatter.formatUptime(3599));
        assertEquals("1h 0m", DiagnosticsFormatter.formatUptime(3600));
        // Hours never roll into days; leftover seconds are truncated from the h/m form.
        assertEquals("25h 1m", DiagnosticsFormatter.formatUptime(25 * 3600 + 60 + 5));
    }
}
