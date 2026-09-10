package dev.vox.lssfixture.concurrent;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class RejectionTelemetrySelfTest {
    public static void main(String[] args){
        var disabled=new RejectionTelemetry(false);
        disabled.observe(RejectionTelemetry.Bucket.BOTH,()->{throw new AssertionError("disabled supplier ran");},row->{throw new AssertionError("disabled sink ran");});
        assert disabled.status().get("rejection_diagnostics_error").equals(false);
        var rows=new ArrayList<Map<String,?>>();var bounded=new RejectionTelemetry(true);var allocated=new AtomicInteger();
        for(var bucket:RejectionTelemetry.Bucket.values())for(int n=0;n<10000;n++)
            bounded.observe(bucket,()->{allocated.incrementAndGet();return Map.of("event","detail");},rows::add);
        assert allocated.get()==160;assert rows.size()==165;
        assert rows.stream().filter(r->r.get("event").equals("acceptance_rejection_truncated")).count()==5;
        assert RejectionTelemetry.bucket(10,20,30,false,true)==RejectionTelemetry.Bucket.SOURCE_ONLY;
        assert RejectionTelemetry.bucket(10,20,30,true,false)==RejectionTelemetry.Bucket.BLOCK_ONLY;
        assert RejectionTelemetry.bucket(10,20,30,false,false)==RejectionTelemetry.Bucket.BOTH;
        assert RejectionTelemetry.bucket(0,20,30,false,false)==RejectionTelemetry.Bucket.PREAPPLY_OR_UNKNOWN;
        assert RejectionTelemetry.bucket(21,20,30,true,true)==RejectionTelemetry.Bucket.PREAPPLY_OR_UNKNOWN;
        assert RejectionTelemetry.bucket(10,20,19,true,true)==RejectionTelemetry.Bucket.INVALID_CLOCK;
        var facts=new AcceptancePolicy.Facts(0,1,10,100,1);
        for(boolean block:new boolean[]{true,false})for(int source:new int[]{0,1}){
            var expected=AcceptancePolicy.decide(facts,true,true,20,30,source,block);
            var retry=new AcceptancePolicy.RetryOnce();var diagnostic=new RejectionTelemetry(true);
            if(expected==AcceptancePolicy.Decision.RETRY){
                retry.require();
                diagnostic.observe(RejectionTelemetry.bucket(10,20,30,source==0,block),()->{throw new AssertionError("record construction failure");},rows::add);
                assert diagnostic.status().get("rejection_diagnostics_error").equals(true);
            }
            var reports=new AtomicInteger();retry.finish(()->true,reports::incrementAndGet);
            assert reports.get()==(expected==AcceptancePolicy.Decision.RETRY?1:0);
            assert AcceptancePolicy.decide(facts,true,true,20,30,source,block)==expected;
        }
        var failedSink=new RejectionTelemetry(true);var attempts=new AtomicInteger();
        for(int n=0;n<100;n++)failedSink.observe(RejectionTelemetry.Bucket.BOTH,()->Map.of(),row->{attempts.incrementAndGet();throw new AssertionError("queue unavailable");});
        assert attempts.get()==2;assert failedSink.status().get("rejection_diagnostics_error").equals(true);
        var stale=new AcceptancePolicy.RetryOnce();stale.require();
        assert !stale.finish(()->false,()->{throw new AssertionError("stale lease reported");});
        System.out.println("RejectionTelemetry: disabled, fixed bucket budget, truncation, classification, decision/retry neutrality and evidence failures passed");
    }
}
