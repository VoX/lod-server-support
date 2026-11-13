package dev.vox.lss.common.diagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Translation-ready status presentation; contains no Minecraft classes or IO. */
public final class ClientStatusText {
    public record Message(String key, List<Object> arguments) {
        public Message { arguments = List.copyOf(arguments); }
    }
    private ClientStatusText() {}
    private static Message text(String key, Object... arguments) {
        return new Message("lss.status." + key, List.of(arguments));
    }
    private static Message onOff(boolean enabled) { return text(enabled ? "on" : "off"); }
    private static Message enumText(String group, Enum<?> value) {
        return text(group + "." + value.name().toLowerCase(Locale.ROOT));
    }
    public static List<Message> lines(ClientStatusSnapshot s, long nowMillis) {
        if (s == null) return List.of(text("waiting"));
        var lines = new ArrayList<Message>();
        // First, the answer: working, or not working and why (ClientVerdict).
        ClientVerdict verdict = ClientVerdict.of(s);
        lines.add(text("verdict." + verdict.key()));
        if (verdict == ClientVerdict.SETTINGS_BROKEN) {
            // The file's real name (the two builds name it differently, and a client may have
            // adopted the other build's file), then everything that is wrong with it.
            lines.add(text("settings.file", s.settings().fileName()));
            for (String problem : s.settings().problems()) lines.add(text("settings.problem", problem));
        }
        if (s.settings() != null && s.settings().ignoredExperimentalFile() != null)
            lines.add(text("settings.experimental_ignored", s.settings().ignoredExperimentalFile()));
        if(s.dh()!=null && s.dh().reason()!=DhStatusSnapshot.Reason.NATIVE_OFFER && s.dh().reason()!=DhStatusSnapshot.Reason.NATIVE_PREFERRED) {
            var d=s.dh();
            lines.add(text("dh.source",d.profile().name(),enumText("dh.state",d.state())));
            lines.add(text("dh.reason",enumText("dh.reason",d.reason())));
            lines.add(text("dh.activity",enumText("dh.activity",d.activity())));
            lines.add(text("dh.terrain"));
            lines.add(text("dh.tiles",d.requestedTiles(),d.enqueuedTiles(),d.incompleteTiles(),d.refusedTiles()));
            lines.add(text("dh.columns",d.enqueuedColumns(),d.vanillaSkippedColumns(),d.failedColumns()));
            lines.add(text("dh.resources",d.wireBytes(),d.ownedBytes(),d.decodeQueue(),d.inFlight(),d.ingestBacklog()));
            lines.add(text("dh.limits",d.effectiveRange(),d.generationRate(),d.syncRate(),d.bandwidthKB()));
            lines.add(text("dh.receipts",d.storedReceipts(),d.restoredReceipts(),d.pressureDroppedTerrainFrames()));
            lines.add(text("dh.freshness",onOff(d.syncOnLoad()),text(d.syncAvailable()?"available":"disabled"),
                    onOff(d.liveUpdates()),text(d.liveAvailable()?"available":"disabled"),
                    d.receivedUpdates(),d.deferredUpdates(),
                    d.lastReplyAgeMillis()<0?text("dh.no_reply"):text("dh.reply_age",d.lastReplyAgeMillis())));
        } else {
        lines.add(text("connection", enumText("discovery", s.discovery()), s.protocol()));
        lines.add(text("reception", onOff(s.receptionEnabled()), text(s.consumerAvailable() ? "available" : "none")));
        lines.add(text("server", text(s.discovery() == ClientStatusSnapshot.Discovery.PROTOCOL_REJECTED
                ? "discovery.protocol_rejected" : !s.negotiated() ? "unknown" : s.serverEnabled() ? "available" : "disabled")));
        lines.add(text("xaero", enumText("availability", s.xaeroAvailability())));
        lines.add(text("journeymap", enumText("availability", s.journeyMapAvailability())));
        lines.add(text("renderer", text(s.rendererAvailable() ? "available" : "renderer_unavailable")));
        lines.add(text("far_players", s.farPlayersFromServer()));
        List<Message> reasons = s.reasons().stream().map(reason -> enumText("reason", reason)).toList();
        lines.add(text("conditions", reasons.isEmpty() ? text("none") : reasons));
        lines.add(text("progress", s.receivedColumns(), s.receivedBytes(), s.effectiveDistance(), s.serverDistance()));
        lines.add(text("rate", s.rateGatedTicks(), s.rateCap()));
        lines.add(text("queues", s.queuedColumns(), s.ingestBacklog(), s.xaeroPendingRebuilds(), s.journeyMapPending()));
        lines.add(text("remote_unknown"));
        if(s.dh()!=null)lines.add(text("dh.reason",enumText("dh.reason",s.dh().reason())));
        }
        var settings = s.settings();
        if (settings != null) {
            lines.add(text("settings.available", text(settings.available() ? "available" : "disabled")));
            lines.add(settingsValues("settings.saved", settings.saved()));
            lines.add(settingsValues("settings.configured", settings.configured()));
            lines.add(settingsValues("settings.effective", settings.effective()));
            lines.add(text("settings.pending_reload", paths(settings.pendingReload())));
            lines.add(text("settings.pending_reconnect", paths(settings.pendingReconnect())));
            lines.add(text("settings.adoption", settings.adoptedRevision(), settings.publishedRevision(), paths(settings.pendingAdoption())));
        }
        lines.add(text("versions", s.versions().components().toString()));
        lines.add(text("age", Math.max(0, nowMillis - s.capturedAtMillis())));
        return List.copyOf(lines);
    }
    private static Object paths(List<String> paths) { return paths.isEmpty() ? text("none") : String.join(", ", paths); }
    private static Message settingsValues(String key, ClientSettingsStatus.Values v) {
        return text(key, onOff(v.receptionEnabled()), v.distanceChunks(), v.maxColumnsPerSecond(),
                onOff(v.mapIngestionEnabled()), onOff(v.sharingEnabled()));
    }
}
