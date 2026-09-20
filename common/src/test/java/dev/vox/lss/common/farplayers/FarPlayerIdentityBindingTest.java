package dev.vox.lss.common.farplayers;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Robustness regressions: stock broadcaster uses monotonic indices; a buggy/hostile peer need not. */
public class FarPlayerIdentityBindingTest {
    static FarPlayerWire.UpdateEntry update() {
        return new FarPlayerWire.UpdateEntry(0,1600,1024,0,(byte)0,(byte)0,(byte)0,(byte)0,
                (short)0,(short)0,(short)0,null,null,null,null);
    }
    static void roster(FarPlayerClientTracker t,int id,boolean full) {
        var r=new FarPlayerWire.Roster(1,full,
                List.of(new FarPlayerWire.RosterEntry(0,new UUID(1,id),"P"+id)),new int[0]);
        t.onRoster(FarPlayerWire.decodeRoster(FarPlayerWire.encodeRoster(r)));
        var u=new FarPlayerWire.Updates(1,"minecraft:overworld",10,List.of(update()));
        t.onUpdates(FarPlayerWire.decodeUpdates(FarPlayerWire.encodeUpdates(u)),id*500L);
    }
    @Test
    void rebindingOneIndexMustRetireItsPreviousTrackedIdentity() {
        var t=new FarPlayerClientTracker(); roster(t,1,true); roster(t,2,false);
        assertAll(
            ()->assertEquals(1,t.trackedCount(),"one current roster binding must not retain two drawable identities"),
            ()->assertNull(t.snapshot().get(new UUID(1,1)),"rebound identity's old ghost must be removed")
        );
    }
    @Test
    void repeatedlyRebindingOneIndexMustNotBypassTheIdentityCap() {
        var t=new FarPlayerClientTracker();
        for(int id=1;id<=FarPlayerClientTracker.MAX_TRACKED_IDENTITIES+1;id++)roster(t,id,id==1);
        assertTrue(t.trackedCount()<=FarPlayerClientTracker.MAX_TRACKED_IDENTITIES,
                "one index bypassed the 4096 cap: tracked="+t.trackedCount()+", capResets="+t.identityCapResets());
    }
}
