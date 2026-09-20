package dev.vox.lss.common.farplayers;

import dev.vox.lss.common.LSSConstants;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary supported runtime mode switch, actual broadcaster -> encoded wire -> client tracker. */
public class FarPlayerModeTransitionTest {
    static final UUID VIEWER=new UUID(0,1), TARGET=new UUID(0,2);
    static FarPlayerBroadcastService.PlayerSnapshot snapshot(UUID uuid,String name,double x) {
        return new FarPlayerBroadcastService.PlayerSnapshot(uuid,name,"minecraft:overworld",x,64,0,
                0,0,0,(byte)0,0,0,0,false,false,true,false,0,null,null,null);
    }
    static FarPlayerBroadcastService.Settings settings(String mode) {
        return new FarPlayerBroadcastService.Settings(mode,2048,0,false,List.of(),10);
    }
    @Test
    void runtimeOffMustWithdrawAlreadyPublishedPlayersInsteadOfFreezingThem() {
        var service=new FarPlayerBroadcastService(null);
        var client=new FarPlayerClientTracker();
        service.subscribeViewer(VIEWER);
        service.onPrefs(VIEWER,new FarPlayerWire.Prefs(true,0,0,true,0));
        long[] now={10_000};
        FarPlayerBroadcastService.FrameSender sender=(viewer,channel,body)->{
            if(channel.equals(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER))client.onRoster(FarPlayerWire.decodeRoster(body));
            else if(channel.equals(LSSConstants.CHANNEL_FAR_PLAYER_UPDATES))client.onUpdates(FarPlayerWire.decodeUpdates(body),now[0]);
            return true;
        };
        var world=List.of(snapshot(VIEWER,"Viewer",0),snapshot(TARGET,"Target",500));
        service.tick(now[0],world,settings("on"),sender);
        assertEquals(1,client.trackedCount(),"premise: viewer has a drawable far player");
        // No movement: delta suppression intentionally keeps a stationary proxy without new frames.
        now[0]=20_000; service.tick(now[0],world,settings("on"),sender);
        assertEquals(1,client.trackedCount(),"ordinary stationary silence must not expire a far player");
        now[0]=20_001; service.tick(now[0],world,settings("off"),sender);
        assertEquals(0,client.trackedCount(),
                "server off must explicitly revoke prior roster; silence leaves the target rendered indefinitely");
    }

    @Test void offImmediatelyAfterRosterBypassesThrottleAndOnRestoresStationaryTarget() {
        var service = new FarPlayerBroadcastService(null);
        var client = new FarPlayerClientTracker();
        service.subscribeViewer(VIEWER);
        service.onPrefs(VIEWER, new FarPlayerWire.Prefs(true, 0, 0, true, 0));
        var world = List.of(snapshot(VIEWER, "Viewer", 0), snapshot(TARGET, "Target", 500));
        FarPlayerBroadcastService.FrameSender sender = (v, channel, body) -> {
            if (channel.equals(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER)) client.onRoster(FarPlayerWire.decodeRoster(body));
            else client.onUpdates(FarPlayerWire.decodeUpdates(body), 10_000);
            return true;
        };
        service.tick(10_000, world, settings("on"), sender);
        assertEquals(1, client.trackedCount());
        assertFalse(service.applyMode("off", sender));
        assertEquals(0, client.trackedCount());
        service.tick(10_001, world, settings("on"), sender);
        assertEquals(1, client.trackedCount());
    }

    @Test void withheldWithdrawalRetriesAndRapidOnSupersedesOldClear() {
        var service = new FarPlayerBroadcastService(null);
        var client = new FarPlayerClientTracker();
        service.subscribeViewer(VIEWER);
        service.onPrefs(VIEWER, new FarPlayerWire.Prefs(true, 0, 0, true, 0));
        var world = List.of(snapshot(VIEWER, "Viewer", 0), snapshot(TARGET, "Target", 500));
        boolean[] writable = {true};
        FarPlayerBroadcastService.FrameSender sender = (v, channel, body) -> {
            if (!writable[0]) return false;
            if (channel.equals(LSSConstants.CHANNEL_FAR_PLAYER_ROSTER)) client.onRoster(FarPlayerWire.decodeRoster(body));
            else client.onUpdates(FarPlayerWire.decodeUpdates(body), 10_000);
            return true;
        };
        service.tick(10_000, world, settings("on"), sender);
        writable[0] = false;
        service.applyMode("off", sender);
        assertEquals(1, client.trackedCount(), "declined clear must remain pending");
        writable[0] = true;
        service.applyMode("off", sender);
        assertEquals(0, client.trackedCount(), "retry needs no online snapshots");
        service.tick(10_001, world, settings("on"), sender);
        assertEquals(1, client.trackedCount());
        writable[0] = false;
        service.applyMode("off", sender);
        service.applyMode("on", sender);
        writable[0] = true;
        service.tick(10_002, world, settings("on"), sender);
        assertEquals(1, client.trackedCount(), "new full roster supersedes unsent clear");
        service.applyMode("on", sender);
        assertEquals(1, client.trackedCount(), "obsolete clear cannot be emitted later");
    }
}
