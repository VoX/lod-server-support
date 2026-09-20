package dev.vox.lssfixture.concurrent;
import java.lang.ref.*;
import java.util.*;
/** Weak identity keys: equal coordinates/stamps never merge distinct native payloads. */
public final class IdentityCaptures<V> {
    private static final class Key extends WeakReference<Object> {
        private final int hash;
        Key(Object value,ReferenceQueue<Object> queue){super(value,queue);hash=System.identityHashCode(value);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other || other instanceof Key key && get()!=null && get()==key.get();}
    }
    private final int limit;
    private final ReferenceQueue<Object> retired=new ReferenceQueue<>();
    private final Map<Key,V> values=new HashMap<>();
    public IdentityCaptures(int limit){if(limit<1)throw new IllegalArgumentException();this.limit=limit;}
    private void reap(){Reference<?> key;while((key=retired.poll())!=null)values.remove(key);}
    public synchronized void put(Object payload,V value){
        reap();Key key=new Key(Objects.requireNonNull(payload),retired);
        if(values.containsKey(key))throw new IllegalStateException("same native payload captured twice");
        if(values.size()>=limit)throw new IllegalStateException("wire capture bound exceeded");
        values.put(key,Objects.requireNonNull(value));
    }
    public synchronized V take(Object payload){reap();return values.remove(new Key(payload,null));}
    public synchronized void clear(){values.clear();while(retired.poll()!=null){}}
}
