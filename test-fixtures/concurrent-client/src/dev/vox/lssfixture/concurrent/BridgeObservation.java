package dev.vox.lssfixture.concurrent;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only existing bridge counter observation, identical on baseline and candidate. */
final class BridgeObservation {
    private final Field instance,written;
    private Object previous;
    private long generation;
    BridgeObservation() throws ReflectiveOperationException {
        Class<?> owner;
        try { owner=Class.forName("dev.vox.lss.compat.XaeroSession"); }
        catch(ClassNotFoundException baseline) { owner=Class.forName("dev.vox.lss.compat.XaeroMapCompat"); }
        instance=owner.getDeclaredField("instance");written=owner.getDeclaredField("written");
        if(!instance.trySetAccessible()||!written.trySetAccessible())throw new IllegalAccessException("bridge observation unavailable");
    }
    Map<String,Object> sample(long now) throws ReflectiveOperationException {
        Object active=instance.get(null);
        if(active!=previous){previous=active;generation++;}
        return Map.of("event","xaero_bridge","time_ns",now,"instance_present",active!=null,"bridge_generation",generation,
                "written",active==null?-1:((AtomicLong)written.get(active)).get());
    }
}
