package dev.vox.lss.common.diagnostics;

import com.google.gson.JsonParser;
import dev.vox.lss.common.config.SettingsSchema;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientStatusTextTest {
    @Test void everyRenderedStateAndArgumentHasAllThreeTranslations() throws Exception {
        var languages = new ArrayList<com.google.gson.JsonObject>();
        Path root=Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root=root.getParent();
        for (String locale : List.of("en_us", "zh_cn", "zh_tw"))
            languages.add(JsonParser.parseString(Files.readString(root.resolve("fabric/src/main/resources/assets/lss/lang/"+locale+".json"))).getAsJsonObject());
        check(ClientStatusText.lines(null,0), languages);
        for (var status : dev.vox.lss.common.config.SettingsReload.Status.values())
            check(List.of(new ClientStatusText.Message(ClientReloadText.outcomeKey(status),List.of())),languages);
        check(List.of(ClientReloadText.normalization(new dev.vox.lss.common.config.SettingsSchema.Normalization("lod.distance_chunks",-1,0))),languages);
        var defaults=SettingsSchema.client().defaults();
        var settings=ClientSettingsStatus.capture(true,defaults,defaults,defaults,Set.of("integrations.map_ingestion.enabled"));
        for (var discovery : ClientStatusSnapshot.Discovery.values())
            for (var integration : ClientStatusSnapshot.Availability.values())
                for (boolean active : new boolean[]{false,true}) {
                    var snapshot=new ClientStatusSnapshot(2,1,100,active,active,active,active,active,active,
                            20,512,128,123,456,2,3,0,20,1,4,discovery,integration,null,DiagnosticVersions.unknown(),settings);
                    check(ClientStatusText.lines(snapshot,90),languages);
                }
        for (var reason : ClientStatusSnapshot.Reason.values()) check(List.of(new ClientStatusText.Message(
                "lss.status.reason."+reason.name().toLowerCase(Locale.ROOT),List.of())),languages);
        for(var reason:DhStatusSnapshot.Reason.values())check(List.of(new ClientStatusText.Message("lss.status.dh.reason."+reason.name().toLowerCase(Locale.ROOT),List.of())),languages);
        for(var activity:DhStatusSnapshot.Activity.values())check(List.of(new ClientStatusText.Message("lss.status.dh.activity."+activity.name().toLowerCase(Locale.ROOT),List.of())),languages);
        // Two arguments since 0.21.0: the reason and the brand's command root (never a literal /lss).
        check(List.of(new ClientStatusText.Message("lss.status.dh.notice.stopped",List.of("x","lss"))),languages);
        for(var state:DhStatusSnapshot.State.values()) for(var profile:dev.vox.lss.common.dh.DhProfile.values())
            for(boolean sync:new boolean[]{false,true}) for(boolean live:new boolean[]{false,true}) {
                var dh=new DhStatusSnapshot(profile,state,DhStatusSnapshot.Reason.NONE,DhStatusSnapshot.Activity.IDLE,10,5,2,1,45,3,2,1000,100,1,2,3,32,1,2,500,true,sync,live,sync,0,0,100,4096,7,0);
                var snapshot=new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION,1,100,true,false,true,false,true,false,
                        0,0,0,0,0,0,-1,0,0,0,0,ClientStatusSnapshot.Discovery.DORMANT,
                        ClientStatusSnapshot.Availability.DISABLED,null,DiagnosticVersions.unknown(),settings,dh);
                check(ClientStatusText.lines(snapshot,200),languages);
                assertTrue(ClientStatusText.lines(snapshot,200).stream().noneMatch(m->m.key().equals("lss.status.renderer")
                        || m.key().equals("lss.status.xaero") || m.key().equals("lss.status.connection")));
                String json=DiagnosticExport.clientJson(snapshot);
                assertTrue(json.contains("\"dh\""));assertTrue(json.contains("\"protocol\": 0"));
                // The verdict is the first line everywhere (0.22.0 plan §1); the source follows it.
                assertTrue(snapshot.lines().getFirst().startsWith("Verdict: "), snapshot.lines().getFirst());
                assertTrue(snapshot.lines().get(1).contains("Distant Horizons"), snapshot.lines().get(1));
                assertTrue(ClientStatusText.lines(snapshot,200).getFirst().key().startsWith("lss.status.verdict."));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.startsWith("Activity:")));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.startsWith("Receipts:")));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.startsWith("Versions:")));
                assertTrue(snapshot.lines().stream().anyMatch(line->line.startsWith("Settings:")));
            }
    }
    /** Hide-permission plan §3: one line with the number of far players the server is
     *  currently sending, so a screenshot tells "the server sends none" (permission, mode,
     *  opt-outs, distance) from "the client is not drawing them" (fog, draw range). */
    @Test void theFarPlayerCountLineFollowsTheRendererLineAndStaysOutOfDhMode() {
        var settings=ClientSettingsStatus.capture(true,SettingsSchema.client().defaults(),SettingsSchema.client().defaults(),
                SettingsSchema.client().defaults(),Set.of());
        for (int count : new int[]{0,3}) {
            var snapshot=new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION,1,100,true,true,true,true,true,true,
                    20,512,128,0,0,0,0,0,0,0,0,ClientStatusSnapshot.Discovery.NEGOTIATED,
                    ClientStatusSnapshot.Availability.ABSENT,null,DiagnosticVersions.unknown(),settings,null,
                    ClientStatusSnapshot.Availability.ABSENT,0,count);
            var keys=ClientStatusText.lines(snapshot,200).stream().map(ClientStatusText.Message::key).toList();
            int line=keys.indexOf("lss.status.far_players");
            assertEquals(keys.indexOf("lss.status.renderer")+1,line,"directly after the renderer line: "+keys);
            assertEquals(line,keys.lastIndexOf("lss.status.far_players"),"exactly once");
            assertEquals(List.<Object>of(count),ClientStatusText.lines(snapshot,200).get(line).arguments(),
                    "zero is shown too: it is the informative case");
            assertTrue(snapshot.lines().contains("Far players sent by this server: "+count),snapshot.lines().toString());
            var json=JsonParser.parseString(DiagnosticExport.clientJson(snapshot)).getAsJsonObject();
            assertEquals(count,json.get("farPlayersFromServer").getAsInt(),"a count only, in the export");
            assertEquals(7,json.get("schemaVersion").getAsInt());
            // The export writes the whole record: the 0.23.0 fields are in it.
            assertEquals(0,json.get("serverOffReason").getAsInt());
            assertEquals("UNKNOWN",json.get("renderer").getAsString());
        }
        assertEquals(7,ClientStatusSnapshot.SCHEMA_VERSION,"a new exported field is a schema bump");
        // Callers with no far-player observation read 0, never a stale or negative count.
        var unobserved=new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION,1,100,true,true,true,true,true,true,
                20,512,128,0,0,0,0,0,0,0,0,ClientStatusSnapshot.Discovery.NEGOTIATED,
                ClientStatusSnapshot.Availability.ABSENT,null,DiagnosticVersions.unknown(),settings,null,
                ClientStatusSnapshot.Availability.ABSENT,0);
        assertEquals(0,unobserved.farPlayersFromServer());
        // DH mode has no far-player service: the line is not rendered at all.
        var dh=new DhStatusSnapshot(dev.vox.lss.common.dh.DhProfile.OFFICIAL_16,DhStatusSnapshot.State.READY,
                DhStatusSnapshot.Reason.NONE,DhStatusSnapshot.Activity.IDLE,10,5,2,1,45,3,2,1000,100,1,2,3,32,1,2,500,
                true,true,true,true,0,0,100,4096,7,0);
        var dhSnapshot=new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION,1,100,true,false,true,false,true,false,
                0,0,0,0,0,0,-1,0,0,0,0,ClientStatusSnapshot.Discovery.DORMANT,
                ClientStatusSnapshot.Availability.DISABLED,null,DiagnosticVersions.unknown(),settings,dh,
                ClientStatusSnapshot.Availability.DISABLED,0,5);
        assertTrue(ClientStatusText.lines(dhSnapshot,200).stream().noneMatch(m->m.key().equals("lss.status.far_players")));
        assertTrue(dhSnapshot.lines().stream().noneMatch(l->l.startsWith("Far players sent")));
    }
    /** The wording fix of the same plan: "the server does not acknowledge delivery" read
     *  like a failure. All three locales say it was sent, and nothing else. */
    @Test void aBacklogNobodyReportsIsNotShownAsTheNumberMinusOne() {
        // -1 is "nothing reports a backlog" (no LOD renderer, or the world is switched off).
        // The visual pass of 0.22.0 found "ingest=-1" in the status details.
        for (int backlog : new int[]{-1, 0, 7}) {
            var snapshot=new ClientStatusSnapshot(ClientStatusSnapshot.SCHEMA_VERSION,1,100,true,true,true,true,true,true,
                    20,512,128,0,0,0,backlog,0,0,0,0,ClientStatusSnapshot.Discovery.NEGOTIATED,
                    ClientStatusSnapshot.Availability.ABSENT,null,DiagnosticVersions.unknown(),null);
            var queues=ClientStatusText.lines(snapshot,200).stream()
                    .filter(m->m.key().equals("lss.status.queues")).findFirst().orElseThrow();
            Object shown=queues.arguments().get(1);
            if (backlog<0) assertEquals(new ClientStatusText.Message("lss.status.unknown",List.of()),shown);
            else assertEquals(backlog,shown);
        }
    }

    @Test void theSharingPreferenceMessageNoLongerReadsLikeAFailure() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("fabric/src/main/resources/assets/lss/lang"))) root=root.getParent();
        var expected=Map.of("en_us","Sharing preference sent to the server.",
                "zh_cn","共享偏好已发送到服务器。","zh_tw","分享偏好已傳送至伺服器。");
        for (var entry : expected.entrySet()) {
            var language=JsonParser.parseString(Files.readString(root.resolve(
                    "fabric/src/main/resources/assets/lss/lang/"+entry.getKey()+".json"))).getAsJsonObject();
            assertEquals(entry.getValue(),language.get("lss.settings.privacy_sent").getAsString(),entry.getKey());
        }
    }
    private void check(List<?> values,List<com.google.gson.JsonObject> languages) {
        for(Object value:values) {
            if(value instanceof ClientStatusText.Message message) {
                for(var language:languages) {
                    assertTrue(language.has(message.key()), message.key());
                    String text=language.get(message.key()).getAsString();
                    assertEquals(message.arguments().size(), text.split("%s",-1).length-1, message.key()+": argument count");
                }
                check(message.arguments(),languages);
            } else if (value instanceof List<?> nested) check(nested,languages);
        }
    }
}
