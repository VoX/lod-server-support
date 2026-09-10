package dev.vox.lssfixture.concurrent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Per-player fixture state. One native owner writes; global readers see immutable snapshots. */
public final class OwnerObservation<T> {
    public record Snapshot<T>(T player,long time,String region,boolean ownsAll) {}
    private volatile Snapshot<T> snapshot;
    private boolean joined;
    private record Transition(boolean anchor,boolean joined,boolean ownsAll,String region) {}
    private Transition previous;
    private int transitions;
    private boolean truncated;
    public static final int MAX_TRANSITIONS=32;

    /** Anchor gates this connection's initial join only, never subsequent native observations. */
    public boolean refresh(T player,long now,String region,boolean ownsAll,boolean anchor) {
        snapshot=new Snapshot<>(player,now,region,ownsAll);
        if(!joined && anchor && ownsAll){joined=true;return true;}
        return false;
    }
    public Snapshot<T> snapshot(){return snapshot;}
    public static boolean available(Snapshot<?> value,Object currentPlayer,long now){
        return value!=null && value.ownsAll() && value.player()==currentPlayer
                && now-value.time()<=250_000_000L;
    }
    /** Optional bounded transitions: timestamps and continuous positions are not change keys. */
    public Map<String,Object> transition(boolean anchor,int blockX,int blockZ) {
        Snapshot<T> value=snapshot;
        Transition next=new Transition(anchor,joined,value.ownsAll(),value.region());
        if(Objects.equals(previous,next))return null;
        previous=next;
        if(transitions>=MAX_TRANSITIONS){
            if(truncated)return null;
            truncated=true;
            return Map.of("event","owner_transition_truncated","time_ns",value.time(),"limit",MAX_TRANSITIONS);
        }
        transitions++;
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("event","owner_transition");row.put("time_ns",value.time());
        row.put("player_block_x",blockX);row.put("player_block_z",blockZ);
        row.put("anchor_matches",anchor);row.put("joined",joined);
        row.put("owns_all",value.ownsAll());row.put("region_identity",value.region());
        return row;
    }
}
