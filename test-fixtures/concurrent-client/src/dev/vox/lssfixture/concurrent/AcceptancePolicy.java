package dev.vox.lssfixture.concurrent;

/** Pure fixture acceptance policy. Cached facts never replace the full-journal final checker. */
public final class AcceptancePolicy {
    public enum Decision { IGNORE, RETRY, COMMIT }
    public record Facts(int source,long offered,long applied,long end,long authoritySince,boolean loadedUpdateFallback) {
        public Facts(int source,long offered,long applied,long end,long authoritySince) {
            this(source,offered,applied,end,authoritySince,false);
        }
        public boolean acceptsSource(int actual) {
            return actual==source || loadedUpdateFallback && source==0 && (actual==1 || actual==3);
        }
    }
    /** Aggregate mismatches in one callback; report an active current lease at most once. */
    public static final class RetryOnce {
        private boolean needed,finished;
        public void require(){needed=true;}
        public boolean finish(java.util.function.BooleanSupplier activeCurrent,Runnable report){
            if(finished)return false;finished=true;
            if(!needed||!activeCurrent.getAsBoolean())return false;
            report.run();return true;
        }
    }
    private AcceptancePolicy() {}
    public static Decision decide(Facts target,boolean active,boolean currentAuthority,
                                  long received,long resolved,int source,boolean matchingBlock) {
        if(!active||!currentAuthority)return Decision.IGNORE;
        // An expired obligation stays in the journal; it cannot be satisfied by later same-block data.
        if(target.end()>0&&resolved>=target.end())return Decision.IGNORE;
        if(received<target.offered()||received<target.authoritySince())return Decision.IGNORE;
        // Missing asynchronous owner evidence or pre-application content is not success.
        if(target.applied()==0||received<target.applied()||resolved<received)return Decision.RETRY;
        return target.acceptsSource(source)&&matchingBlock?Decision.COMMIT:Decision.RETRY;
    }
}
