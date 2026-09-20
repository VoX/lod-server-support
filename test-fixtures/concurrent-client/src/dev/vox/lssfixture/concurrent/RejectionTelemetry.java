package dev.vox.lssfixture.concurrent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Opt-in only: five fixed buckets, no target-ID state, bounded across all sessions. */
public final class RejectionTelemetry {
    public enum Bucket { SOURCE_ONLY, BLOCK_ONLY, BOTH, PREAPPLY_OR_UNKNOWN, INVALID_CLOCK }
    public static final int PER_BUCKET_LIMIT=32;
    private final boolean enabled;
    private final int[] attempted=new int[Bucket.values().length];
    private final boolean[] truncated=new boolean[Bucket.values().length];
    private boolean failed;
    public RejectionTelemetry(boolean enabled){this.enabled=enabled;}
    public boolean enabled(){return enabled;}
    public static Bucket bucket(long applied,long received,long resolved,boolean sourceMatches,boolean blockMatches){
        if(resolved<received)return Bucket.INVALID_CLOCK;
        if(applied==0||received<applied)return Bucket.PREAPPLY_OR_UNKNOWN;
        if(!sourceMatches&&!blockMatches)return Bucket.BOTH;
        return sourceMatches?Bucket.BLOCK_ONLY:Bucket.SOURCE_ONLY;
    }
    public synchronized void observe(Bucket bucket,Supplier<Map<String,?>> row,Consumer<Map<String,?>> sink){
        if(!enabled||failed)return;
        try {
            int index=bucket.ordinal();
            if(truncated[index])return;
            if(attempted[index]==PER_BUCKET_LIMIT){
                truncated[index]=true;
                sink.accept(Map.of("event","acceptance_rejection_truncated","bucket",bucket.name(),"limit",PER_BUCKET_LIMIT));
                return;
            }
            attempted[index]++;
            Map<String,Object> detail=new HashMap<>(row.get());detail.put("bucket",bucket.name());sink.accept(detail);
        }catch(Throwable error){
            failed=true;
            try{sink.accept(Map.of("event","acceptance_rejection_error","type",error.getClass().getName()));}
            catch(Throwable reportingFailure){/* failed status remains explicit; reporting cannot escape. */}
        }
    }
    public synchronized Map<String,Object> status(){
        Map<String,Object> counts=new HashMap<>(),cuts=new HashMap<>();
        for(Bucket bucket:Bucket.values()){counts.put(bucket.name(),attempted[bucket.ordinal()]);cuts.put(bucket.name(),truncated[bucket.ordinal()]);}
        return Map.of("rejection_diagnostics_enabled",enabled,"rejection_diagnostics_attempted",counts,
                "rejection_diagnostics_per_bucket_limit",PER_BUCKET_LIMIT,"rejection_diagnostics_truncated",cuts,
                "rejection_diagnostics_error",failed);
    }
}
