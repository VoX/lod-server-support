package dev.vox.lssfixture.concurrent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Fixed rig subjects resolve through the native concurrent UUID registry, never the name index. */
final class SubjectPlayers<P> {
    private final ConcurrentHashMap<String,UUID> ids=new ConcurrentHashMap<>();
    private final Function<UUID,P> lookup;
    SubjectPlayers(Function<UUID,P> lookup){this.lookup=lookup;}
    void join(String subject,UUID id){
        if(!subject.matches("RigSubject[ABCD]"))throw new IllegalArgumentException("unknown rig subject");
        ids.put(subject,id);
    }
    P current(String subject){
        UUID id=ids.get(subject);return id==null?null:lookup.apply(id);
    }
}
