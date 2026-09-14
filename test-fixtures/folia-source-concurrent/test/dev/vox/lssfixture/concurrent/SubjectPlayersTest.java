package dev.vox.lssfixture.concurrent;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Exercises the adapter's actual UUID resolver and immutable owner-identity guard. */
public final class SubjectPlayersTest {
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        String subject="RigSubjectC";
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000003");
        var names=new HashMap<String,Object>();
        var nativePlayers=new ConcurrentHashMap<UUID,Object>();
        var resolver=new SubjectPlayers<Object>(nativePlayers::get);
        Object original=new Object(),successor=new Object();
        var observation=new OwnerObservation<Object>();
        require(resolver.current(subject)==null,"unjoined subject cannot resolve");
        nativePlayers.put(id,original);resolver.join(subject,id);
        require(observation.refresh(original,100,"3",true,true),"original owner earns first join");
        require(names.get(subject)==null,"missing-name premise");
        require(resolver.current(subject)==original,"missing name must not drop current UUID join");
        require(OwnerObservation.available(observation.snapshot(),resolver.current(subject),101),"actual owner guard accepts UUID current player");
        names.put(subject,successor);
        require(names.get(subject)!=original,"stale-name premise");
        require(resolver.current(subject)==original,"stale name cannot redirect current UUID owner");
        nativePlayers.remove(id);
        require(resolver.current(subject)==null,"retired native UUID has no authority despite retained subject key");
        require(!OwnerObservation.available(observation.snapshot(),resolver.current(subject),102),"retired owner rejected");
        nativePlayers.put(id,successor);resolver.join(subject,id);
        require(resolver.current(subject)==successor,"same UUID reconnect resolves successor wrapper");
        require(!OwnerObservation.available(observation.snapshot(),resolver.current(subject),103),"old captured wrapper cannot authorize successor");
        var replacement=new OwnerObservation<Object>();replacement.refresh(successor,104,"4",true,true);
        require(OwnerObservation.available(replacement.snapshot(),resolver.current(subject),105),"new owner accepted");
        require(!OwnerObservation.available(replacement.snapshot(),resolver.current(subject),250_000_105L),"TTL unchanged");
        boolean rejected=false;try{resolver.join("OtherPlayer",id);}catch(IllegalArgumentException expected){rejected=true;}
        require(rejected,"registry remains fixed-subject bounded");
        System.out.println("SubjectPlayersTest PASS");
    }
}
