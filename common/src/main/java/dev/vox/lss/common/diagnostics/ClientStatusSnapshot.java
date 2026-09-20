package dev.vox.lss.common.diagnostics;

import java.util.ArrayList;
import java.util.List;

/** Immutable allowlisted data. No addresses, identities, world handles or exception messages. */
public record ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
        boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
        boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
        int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
        int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
        int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability, ClientDiagnosticSnapshot details, DiagnosticVersions versions) {
    public enum Availability { UNKNOWN, ABSENT, UNAVAILABLE, AVAILABLE, DISABLED, FAILED }
    public enum Discovery { NOT_CONNECTED, DORMANT, AWAITING_NEGOTIATION, SEND_FAILED, PROTOCOL_REJECTED, NEGOTIATED }
    public ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
            boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
            boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
            int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
            int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks, int xaeroPendingRebuilds) {
        this(schemaVersion, lifecycle, capturedAtMillis, connected, negotiated, receptionEnabled, serverEnabled,
                consumerAvailable, rendererAvailable, protocol, serverDistance, effectiveDistance, receivedColumns,
                receivedBytes, queuedColumns, ingestBacklog, ingestFailures, rateCap, rateGatedTicks, xaeroPendingRebuilds,
                !connected ? Discovery.NOT_CONNECTED : negotiated ? Discovery.NEGOTIATED : Discovery.AWAITING_NEGOTIATION,
                Availability.UNKNOWN, null, DiagnosticVersions.unknown());
    }
    public enum Reason {
        NOT_CONNECTED, AWAITING_NEGOTIATION, RECEPTION_OFF, NO_CONSUMER,
        SERVER_DISABLED, PROTOCOL_REJECTED, HANDSHAKE_SEND_FAILED, LOCAL_RATE_CAP, DECODE_QUEUE_PENDING, INGEST_QUEUE_PENDING, XAERO_UNAVAILABLE, XAERO_FAILED, XAERO_REBUILDS
    }
    public List<Reason> reasons() {
        var reasons = new ArrayList<Reason>();
        if (!connected) reasons.add(Reason.NOT_CONNECTED);
        if (!receptionEnabled) reasons.add(Reason.RECEPTION_OFF);
        if (!consumerAvailable) reasons.add(Reason.NO_CONSUMER);
        if (connected && receptionEnabled && consumerAvailable && !negotiated && discovery == Discovery.AWAITING_NEGOTIATION)
            reasons.add(Reason.AWAITING_NEGOTIATION);
        if (discovery == Discovery.PROTOCOL_REJECTED) reasons.add(Reason.PROTOCOL_REJECTED);
        if (discovery == Discovery.SEND_FAILED) reasons.add(Reason.HANDSHAKE_SEND_FAILED);
        if (connected && negotiated && !serverEnabled && discovery == Discovery.NEGOTIATED) reasons.add(Reason.SERVER_DISABLED);
        if (rateGatedTicks > 0) reasons.add(Reason.LOCAL_RATE_CAP);
        if (queuedColumns > 0) reasons.add(Reason.DECODE_QUEUE_PENDING);
        if (ingestBacklog > 0) reasons.add(Reason.INGEST_QUEUE_PENDING);
        if (xaeroAvailability == Availability.UNAVAILABLE) reasons.add(Reason.XAERO_UNAVAILABLE);
        if (xaeroAvailability == Availability.FAILED) reasons.add(Reason.XAERO_FAILED);
        if (xaeroPendingRebuilds > 0) reasons.add(Reason.XAERO_REBUILDS);
        return List.copyOf(reasons);
    }
    public List<String> lines() {
        return List.of("Connection: " + discovery + "; protocol " + protocol,
                "Reception: " + (receptionEnabled ? "ON" : "OFF") + "; consumer: " + (consumerAvailable ? "available" : "none"),
                "Server: " + (discovery == Discovery.PROTOCOL_REJECTED ? "protocol rejected" : !negotiated ? "unknown" : serverEnabled ? "available" : "explicitly disabled"),
                "Xaero integration: " + xaeroAvailability,
                "Far-player renderer: " + (rendererAvailable ? "available" : "unavailable on this loader/line"),
                "Observed conditions (queue presence alone does not establish a bottleneck): " + reasons(),
                "Progress since this world status began: " + receivedColumns + " columns, " + receivedBytes + " bytes; distance " + effectiveDistance + "/" + serverDistance,
                "Rate gate events in this sample: " + rateGatedTicks + "; configured manual cap " + rateCap + " columns/s (0 unlimited; adaptive cap may also bind)",
                "Queues: decode=" + queuedColumns + ", ingest=" + ingestBacklog + ", Xaero rebuild=" + xaeroPendingRebuilds,
                "Remote limiting cause: unknown; ask an operator for server-local diagnostics.",
                "Versions: " + versions.components(),
                "Snapshot age: " + Math.max(0, System.currentTimeMillis() - capturedAtMillis) + " ms");
    }
}
