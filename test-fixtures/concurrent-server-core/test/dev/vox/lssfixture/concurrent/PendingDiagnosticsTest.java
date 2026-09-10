package dev.vox.lssfixture.concurrent;
import java.util.Map;
/** Plain Java controls staged for the next authorized fixture build; not executed yet. */
public final class PendingDiagnosticsTest {
    static void check(boolean condition){if(!condition)throw new AssertionError();}
    public static void main(String[] ignored){
        PendingDiagnostics d=new PendingDiagnostics();long start=1_000_000_000L;
        var missing=d.observe("one","RigSubjectC","c",null,false,false,start,start,Map.of("reason","missing_snapshot"));
        check(Boolean.FALSE.equals(missing.get("ack_seen")));check(missing.get("first_ack_observed_ns")==null);
        check(d.observe("one","RigSubjectC","c",null,false,false,start,start+1,Map.of("reason","missing_snapshot"))==null);
        var ack=d.observe("one","RigSubjectC","c","c",true,false,start,start+2,Map.of("reason","stale_snapshot"));
        check(ack.get("first_ack_observed_ns").equals(start+2));check(Boolean.FALSE.equals(ack.get("ownership_available")));
        var owner=d.observe("one","RigSubjectC","c","c",true,true,start,start+3,Map.of("reason","owner_available"));
        check(owner.get("first_ack_observed_ns").equals(start+2));check(Boolean.TRUE.equals(owner.get("ownership_available")));
        var timeout=d.observe("two","RigSubjectD","d",null,false,false,start,start+120_000_000_001L,Map.of("reason","target_set_not_owned"));
        check(Boolean.TRUE.equals(timeout.get("terminal")));check(timeout.get("first_ack_observed_ns")==null);
        // A transition storm is bounded; first actual ACK remains observable after truncation.
        PendingDiagnostics bounded=new PendingDiagnostics();int emitted=0;
        for(int i=0;i<1000;i++)if(bounded.observe("b","RigSubjectB","b",null,false,false,start,start+i,Map.of("reason","r"+(i%2)))!=null)emitted++;
        check(emitted==125);
        var late=bounded.observe("b","RigSubjectB","b","b",true,false,start,start+1001,Map.of("reason","stale_snapshot"));
        check(late!=null&&late.get("first_ack_observed_ns").equals(start+1001));
        var terminal=bounded.observe("b","RigSubjectB","b","b",true,false,start,start+120_000_000_001L,Map.of("reason","stale_snapshot"));
        check(terminal!=null&&Boolean.TRUE.equals(terminal.get("terminal")));
        check(terminal.get("first_ack_observed_ns").equals(start+1001));
        PendingDiagnostics absentAfterCap=new PendingDiagnostics();
        for(int i=0;i<1000;i++)absentAfterCap.observe("c","RigSubjectC","c",null,false,false,start,start+i,Map.of("reason","r"+(i%2)));
        var atDeadline=absentAfterCap.observe("c","RigSubjectC","c",null,false,false,start,start+120_000_000_000L,Map.of("reason","stale_snapshot"));
        check(atDeadline==null); // Original timeout predicate is strictly greater than120s.
        var afterDeadline=absentAfterCap.observe("c","RigSubjectC","c",null,false,false,start,start+120_000_000_001L,Map.of("reason","stale_snapshot"));
        check(afterDeadline!=null&&Boolean.TRUE.equals(afterDeadline.get("terminal")));
        check(afterDeadline.get("first_ack_observed_ns")==null);
        // Diagnostic exception does not replace the original decision or stop the caller.
        boolean accepted=true,ownershipAvailable=false;int[] failures={0};
        DiagnosticGuard.observe(()->{throw new IllegalStateException("native observation failed");},error->{
            check(error instanceof IllegalStateException);failures[0]++;
        });
        check(accepted&&!ownershipAvailable&&failures[0]==1);
        DiagnosticGuard.observe(()->{},error->{throw new AssertionError("unexpected failure");});
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();var original=System.err;
        try {
            System.setErr(new java.io.PrintStream(bytes));
            DiagnosticGuard.observe(()->{throw new IllegalStateException();},error->{throw new IllegalArgumentException();});
        } finally {System.setErr(original);}
        check(bytes.toString().contains("LSS_RIG_SOURCE_DIAGNOSTIC_FAILURE: reporting"));
    }
}
