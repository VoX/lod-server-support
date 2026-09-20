package dev.vox.lss.common.processing;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.vox.lss.common.PositionUtil;

class LoadedProbeGuardTest {
    private static final String DIM="minecraft:overworld";
    @Test void capturesAreBoundToExactKeyRegistrationAndProcessor() {
        var guard=new LoadedProbeGuard();var registration=new RequestRegistration();long p=PositionUtil.packPosition(3,4);
        var capture=guard.capture(DIM,p,registration);
        var data=capture.bind(new LoadedColumnData(3,4,new byte[]{1},1));
        assertTrue(guard.current(data,DIM,p,registration));
        assertFalse(guard.current(new LoadedColumnData(3,4,new byte[]{1},1),DIM,p,registration));
        assertFalse(new LoadedProbeGuard().current(data,DIM,p,registration));
        assertFalse(guard.current(data,DIM,p,new RequestRegistration()));
        assertFalse(guard.current(data,"minecraft:the_end",p,registration));
        assertFalse(guard.current(data,DIM,p+1,registration));
        assertThrows(IllegalArgumentException.class,()->capture.bind(data));
        assertThrows(IllegalArgumentException.class,()->capture.bind(new LoadedColumnData(4,4,null,0)));
        registration.retire();assertFalse(guard.current(data,DIM,p,registration));
    }
    @Test void unrelatedStripeSurvivesAndCollisionNeverSubstitutesContent() {
        var guard=new LoadedProbeGuard();var a=new RequestRegistration();var b=new RequestRegistration();long p=PositionUtil.packPosition(3,4);
        long collision=p+1;while(LoadedProbeGuard.stripe(DIM,collision)!=LoadedProbeGuard.stripe(DIM,p))collision++;
        long other=p+1;while(LoadedProbeGuard.stripe(DIM,other)==LoadedProbeGuard.stripe(DIM,p))other++;
        var data=guard.capture(DIM,p,a).bind(new LoadedColumnData(3,4,new byte[]{1},1));
        var second=guard.capture(DIM,p,b).bind(new LoadedColumnData(3,4,new byte[]{1},1));
        guard.invalidate(DIM,new long[]{other});assertTrue(guard.current(data,DIM,p,a));assertTrue(guard.current(second,DIM,p,b));
        assertFalse(guard.current(data,DIM,collision,a));
        guard.invalidate(DIM,new long[]{collision});assertFalse(guard.current(data,DIM,p,a));assertFalse(guard.current(second,DIM,p,b));
    }
    @Test void reentrantInvalidationAndRepeatedRotationsNeverRefreshOldCapture() {
        var guard=new LoadedProbeGuard();var registration=new RequestRegistration();long p=PositionUtil.packPosition(3,4);
        var before=guard.capture(DIM,p,registration);
        guard.invalidate(DIM,new long[]{p}); // serializer queues invalidation before returning bytes
        var old=before.bind(new LoadedColumnData(3,4,null,0));assertFalse(guard.current(old,DIM,p,registration));
        for(int i=0;i<10000;i++)guard.invalidate(DIM,new long[]{p});assertFalse(guard.current(old,DIM,p,registration));
        var fresh=guard.capture(DIM,p,registration).bind(new LoadedColumnData(3,4,null,0));
        guard.invalidate(DIM,new long[]{});assertTrue(guard.current(fresh,DIM,p,registration));
    }
}
