package dev.vox.lss.common.diagnostics;

import java.util.ArrayList;
import java.util.List;

/** Immutable allowlisted data. No addresses, identities, world handles or exception messages. */
public record ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
        boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
        boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
        int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
        int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
        int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability, ClientDiagnosticSnapshot details, DiagnosticVersions versions, ClientSettingsStatus settings, DhStatusSnapshot dh,
        Availability journeyMapAvailability, int journeyMapPending, int farPlayersFromServer,
        boolean mapOnly) {
    /** 4: settings.mapIngestionEnabled (was xaeroMapEnabled) and the JourneyMap fields.
     *  5: {@link Availability#SERVER_BLOCKED} (the server's map-ingestion request,
     *  map-policy plan A4).
     *  6: {@code farPlayersFromServer} — how many far players the server is currently
     *  sending this client (hide-permission plan §3): 0 means the server sends none
     *  (its mode, a permission or setting, the other players' sharing choice, distance),
     *  anything else means a missing far player is a client drawing question (fog, the
     *  draw range). A count only; never names or positions. */
    public static final int SCHEMA_VERSION = 6;
    /** Callers that do not say whether the map mods are this client's only consumers
     *  ({@code mapOnly}, read by {@link ClientVerdict} alone and not exported). */
    public ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
            boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
            boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
            int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
            int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
            int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability,
            ClientDiagnosticSnapshot details, DiagnosticVersions versions, ClientSettingsStatus settings,
            DhStatusSnapshot dh, Availability journeyMapAvailability, int journeyMapPending,
            int farPlayersFromServer) {
        this(schemaVersion,lifecycle,capturedAtMillis,connected,negotiated,receptionEnabled,serverEnabled,
                consumerAvailable,rendererAvailable,protocol,serverDistance,effectiveDistance,receivedColumns,
                receivedBytes,queuedColumns,ingestBacklog,ingestFailures,rateCap,rateGatedTicks,xaeroPendingRebuilds,
                discovery,xaeroAvailability,details,versions,settings,dh,journeyMapAvailability,journeyMapPending,
                farPlayersFromServer,false);
    }
    /** Callers with no far-player observation (none received). */
    public ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
            boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
            boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
            int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
            int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
            int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability,
            ClientDiagnosticSnapshot details, DiagnosticVersions versions, ClientSettingsStatus settings,
            DhStatusSnapshot dh, Availability journeyMapAvailability, int journeyMapPending) {
        this(schemaVersion,lifecycle,capturedAtMillis,connected,negotiated,receptionEnabled,serverEnabled,
                consumerAvailable,rendererAvailable,protocol,serverDistance,effectiveDistance,receivedColumns,
                receivedBytes,queuedColumns,ingestBacklog,ingestFailures,rateCap,rateGatedTicks,xaeroPendingRebuilds,
                discovery,xaeroAvailability,details,versions,settings,dh,journeyMapAvailability,journeyMapPending,0);
    }
    /** Callers with no JourneyMap observation (JourneyMap UNKNOWN, nothing pending). */
    public ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
            boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
            boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
            int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
            int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
            int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability,
            ClientDiagnosticSnapshot details, DiagnosticVersions versions, ClientSettingsStatus settings,
            DhStatusSnapshot dh) {
        this(schemaVersion,lifecycle,capturedAtMillis,connected,negotiated,receptionEnabled,serverEnabled,
                consumerAvailable,rendererAvailable,protocol,serverDistance,effectiveDistance,receivedColumns,
                receivedBytes,queuedColumns,ingestBacklog,ingestFailures,rateCap,rateGatedTicks,xaeroPendingRebuilds,
                discovery,xaeroAvailability,details,versions,settings,dh,Availability.UNKNOWN,0);
    }
    /** Native snapshot constructor preserves the existing field meanings. */
    public ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
            boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
            boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
            int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
            int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
            int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability,
            ClientDiagnosticSnapshot details, DiagnosticVersions versions, ClientSettingsStatus settings) {
        this(schemaVersion,lifecycle,capturedAtMillis,connected,negotiated,receptionEnabled,serverEnabled,
                consumerAvailable,rendererAvailable,protocol,serverDistance,effectiveDistance,receivedColumns,
                receivedBytes,queuedColumns,ingestBacklog,ingestFailures,rateCap,rateGatedTicks,xaeroPendingRebuilds,
                discovery,xaeroAvailability,details,versions,settings,null);
    }
    /** Compatibility constructor for callers that have no settings observation. */
    public ClientStatusSnapshot(int schemaVersion, long lifecycle, long capturedAtMillis,
            boolean connected, boolean negotiated, boolean receptionEnabled, boolean serverEnabled,
            boolean consumerAvailable, boolean rendererAvailable, int protocol, int serverDistance,
            int effectiveDistance, long receivedColumns, long receivedBytes, int queuedColumns,
            int ingestBacklog, long ingestFailures, int rateCap, long rateGatedTicks,
            int xaeroPendingRebuilds, Discovery discovery, Availability xaeroAvailability,
            ClientDiagnosticSnapshot details, DiagnosticVersions versions) {
        this(schemaVersion, lifecycle, capturedAtMillis, connected, negotiated, receptionEnabled, serverEnabled,
                consumerAvailable, rendererAvailable, protocol, serverDistance, effectiveDistance, receivedColumns,
                receivedBytes, queuedColumns, ingestBacklog, ingestFailures, rateCap, rateGatedTicks,
                xaeroPendingRebuilds, discovery, xaeroAvailability, details, versions, null);
    }
    /** {@code SERVER_BLOCKED}: installed, enabled locally, but the connected server asks
     *  clients not to write LOD terrain into map mods (map-policy plan A4). */
    public enum Availability { UNKNOWN, ABSENT, UNAVAILABLE, AVAILABLE, DISABLED, FAILED, SERVER_BLOCKED }
    /** {@code NO_CHANNEL}: the server never registered the handshake channel, so it does not
     *  run the mod. {@code NO_ANSWER}: the channel exists but every announce went unanswered
     *  (a proxy, a version skew, a saturated link): weaker, and never reported as "no mod".
     *  {@code LOCAL}: the player's own world (singleplayer, before Open to LAN): nothing is
     *  downloaded. {@code DORMANT}: connected but not asking (receiving off, another source
     *  selected, or nothing to show the terrain). */
    public enum Discovery { NOT_CONNECTED, DORMANT, AWAITING_NEGOTIATION, SEND_FAILED, PROTOCOL_REJECTED, NEGOTIATED,
        NO_CHANNEL, NO_ANSWER, LOCAL }
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
        SERVER_DISABLED, PROTOCOL_REJECTED, HANDSHAKE_SEND_FAILED, LOCAL_RATE_CAP, DECODE_QUEUE_PENDING, INGEST_QUEUE_PENDING, XAERO_UNAVAILABLE, XAERO_FAILED, XAERO_REBUILDS,
        JOURNEYMAP_UNAVAILABLE, JOURNEYMAP_FAILED, JOURNEYMAP_PENDING,
        /** A map backend reads {@link Availability#SERVER_BLOCKED} (map-policy plan A5). */
        MAP_INGESTION_SERVER_BLOCKED
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
        if (journeyMapAvailability == Availability.UNAVAILABLE) reasons.add(Reason.JOURNEYMAP_UNAVAILABLE);
        if (journeyMapAvailability == Availability.FAILED) reasons.add(Reason.JOURNEYMAP_FAILED);
        if (journeyMapPending > 0) reasons.add(Reason.JOURNEYMAP_PENDING);
        if (xaeroAvailability == Availability.SERVER_BLOCKED || journeyMapAvailability == Availability.SERVER_BLOCKED)
            reasons.add(Reason.MAP_INGESTION_SERVER_BLOCKED);
        return List.copyOf(reasons);
    }
    public List<String> lines() {
        if(dh!=null && dh.reason()!=DhStatusSnapshot.Reason.NATIVE_OFFER && dh.reason()!=DhStatusSnapshot.Reason.NATIVE_PREFERRED) {
            var lines=new ArrayList<String>();
            lines.add("Verdict: " + ClientVerdict.of(this));
            lines.addAll(dh.lines());
            if(settings!=null) {
                lines.add("Settings: saved="+settings.saved()+"; configured="+settings.configured()+"; effective="+settings.effective());
                lines.add("Pending settings: reload="+settings.pendingReload()+"; reconnect="+settings.pendingReconnect()
                        +"; adoption="+settings.pendingAdoption());
            }
            lines.add("Versions: "+versions.components());
            lines.add("Snapshot age: "+Math.max(0,System.currentTimeMillis()-capturedAtMillis)+" ms");
            return List.copyOf(lines);
        }
        var lines=new ArrayList<>(List.of("Verdict: " + ClientVerdict.of(this),
                "Connection: " + discovery + "; protocol " + protocol,
                "Reception: " + (receptionEnabled ? "ON" : "OFF") + "; consumer: " + (consumerAvailable ? "available" : "none"),
                "Server: " + (discovery == Discovery.PROTOCOL_REJECTED ? "protocol rejected" : !negotiated ? "unknown" : serverEnabled ? "available" : "explicitly disabled"),
                "Xaero integration: " + xaeroAvailability,
                "JourneyMap integration: " + journeyMapAvailability,
                "Far-player renderer: " + (rendererAvailable ? "available" : "unavailable on this loader/line"),
                "Far players sent by this server: " + farPlayersFromServer,
                "Observed conditions (queue presence alone does not establish a bottleneck): " + reasons(),
                "Progress since this world status began: " + receivedColumns + " columns, " + receivedBytes + " bytes; distance " + effectiveDistance + "/" + serverDistance,
                "Rate gate events in this sample: " + rateGatedTicks + "; configured manual cap " + rateCap + " columns/s (0 unlimited; adaptive cap may also bind)",
                "Queues: decode=" + queuedColumns + ", ingest=" + ingestBacklog + ", Xaero rebuild=" + xaeroPendingRebuilds
                        + ", JourneyMap=" + journeyMapPending,
                "Remote limiting cause: unknown; ask an operator for server-local diagnostics.",
                "Versions: " + versions.components(),
                "Snapshot age: " + Math.max(0, System.currentTimeMillis() - capturedAtMillis) + " ms"));
        if(dh!=null)lines.add("DH fallback: "+dh.reason());
        return List.copyOf(lines);
    }
}
