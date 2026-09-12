package dev.vox.lssfixture.concurrent;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional one-world startup configuration witness, never a persistence barrier. */
public final class NativeSaveSetup {
    public record Observation(int interval,int maxChunks,String dimension,String worldUuid,
            String worldName,String ownerKind,String ownerIdentity,long observedNs) {}
    private final boolean enabled;
    private boolean claimed;
    private volatile boolean complete;
    public NativeSaveSetup(String option) {
        enabled=option!=null;
        if(enabled && !option.equals("200"))throw new IllegalArgumentException("native autosave setup requires exact200");
    }
    public boolean ready(){return !enabled || complete;}
    /** At most one read+queue publication, even when several seed owners race.
     * Native reads run only after the adapter's actual owner check succeeds.
     * A failed observation is terminal (not silently retried by another region).
     */
    public synchronized void observe(boolean actualOwner,Supplier<Observation> read,Consumer<Map<String,Object>> publish) {
        if(!enabled || claimed)return;
        claimed=true;
        if(!actualOwner)throw new IllegalStateException("native save setup owner absent");
        Observation o=read.get();
        if(o==null || o.interval()!=200 || o.maxChunks()!=24 || !"minecraft:overworld".equals(o.dimension())
                || o.worldUuid()==null || !o.worldUuid().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                || o.worldName()==null || o.worldName().isBlank() || o.ownerIdentity()==null || o.ownerIdentity().isBlank()
                || !("owning-region".equals(o.ownerKind()) || "server-thread".equals(o.ownerKind())) || o.observedNs()<=0)
            throw new IllegalStateException("effective native save setup does not match200/cap24");
        publish.accept(Map.of("interval_ticks",o.interval(),"max_chunks_per_tick",o.maxChunks(),
                "dimension",o.dimension(),"world_uuid",o.worldUuid(),"world_name",o.worldName(),
                "owner_kind",o.ownerKind(),"owner_identity",o.ownerIdentity(),"owns_region",true,"observed_ns",o.observedNs()));
        complete=true; // Release only after the bounded evidence queue publication.
    }
}
