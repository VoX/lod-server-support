package dev.vox.lssfixture.concurrent;
import java.util.*;
/** Sparse diagnostic only, called on the existing workload owner. No scheduling or acceptance changes. */
final class PendingDiagnostics {
    private static final int TARGET_LIMIT=64, SAMPLE_LIMIT=124;
    private static final class State {long last=Long.MIN_VALUE,firstAck;int samples;boolean truncated;String previous;}
    private final Map<String,State> states=new HashMap<>();
    Map<String,Object> observe(String id,String subject,String connection,String ackConnection,boolean acknowledged,
            boolean ownershipAvailable,long offered,long now,Map<String,Object> owner) {
        State state=states.get(id);
        if(state==null){if(states.size()>=TARGET_LIMIT)return null;state=new State();states.put(id,state);}
        boolean firstAck=acknowledged&&state.firstAck==0;
        if(firstAck)state.firstAck=now;
        boolean terminal=now-offered>120_000_000_000L;
        String key=acknowledged+":"+ownershipAvailable+":"+owner.get("reason")+":"+ackConnection;
        boolean changed=!Objects.equals(state.previous,key);
        if(state.samples>=SAMPLE_LIMIT&&!firstAck&&!terminal){
            if(state.truncated)return null;state.truncated=true;
        } else if(state.last!=Long.MIN_VALUE&&now-state.last<1_000_000_000L&&!firstAck&&!terminal&&!changed)return null;
        state.last=now;state.previous=key;state.samples++;
        Map<String,Object> row=new LinkedHashMap<>();row.put("event","pending_edit_diagnostic");
        row.put("id",id);row.put("subject",subject);row.put("connection_id",connection);
        row.put("ack_connection_id",ackConnection);row.put("ack_seen",acknowledged);
        row.put("first_ack_observed_ns",state.firstAck==0?null:state.firstAck);
        row.put("ownership_available",ownershipAvailable);row.put("owner",owner);
        row.put("offered_ns",offered);row.put("time_ns",now);row.put("terminal",terminal);
        row.put("sample_limit_reached",state.truncated);return row;
    }
}
