package dev.vox.lssfixture.concurrent;

/** Pure fixture acceptance policy. Cached facts never replace the full-journal final checker. */
public final class AcceptancePolicy {
    public enum Decision { IGNORE, AWAIT_APPLICATION, OBSERVE, FAILURE, COMMIT }
    public record Facts(int source,long offered,long applied,long end,long authoritySince,boolean loadedUpdateFallback) {
        public Facts(int source,long offered,long applied,long end,long authoritySince) {
            this(source,offered,applied,end,authoritySince,false);
        }
        public boolean acceptsSource(int actual) {
            return actual==source || loadedUpdateFallback && source==0 && (actual==1 || actual==3);
        }
    }
    private AcceptancePolicy() {}
    public static Decision decide(Facts target,boolean active,boolean currentAuthority,
                                  long received,long resolved,int source,boolean matchingBlock) {
        if(!active||!currentAuthority)return Decision.IGNORE;
        if(resolved<received)return Decision.FAILURE;
        // An expired obligation stays in the journal; it cannot be satisfied by later same-block data.
        if(target.end()>0&&resolved>=target.end())return Decision.IGNORE;
        if(received<target.offered()||received<target.authoritySince())return Decision.IGNORE;
        // Missing evidence may be awaited; a valid nonmatching body is observed without storage rejection.
        if(target.applied()==0)return Decision.AWAIT_APPLICATION;
        if(received<target.applied())return Decision.OBSERVE;
        return target.acceptsSource(source)&&matchingBlock?Decision.COMMIT:Decision.OBSERVE;
    }
}
