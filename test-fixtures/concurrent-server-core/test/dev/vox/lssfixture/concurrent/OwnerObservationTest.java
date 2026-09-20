package dev.vox.lssfixture.concurrent;

/** Behavioral controls for native owner observations; no Minecraft launch. */
public final class OwnerObservationTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] ignored){
        Object player=new Object(),replacement=new Object();long now=1_000_000_000L;
        OwnerObservation<Object> owner=new OwnerObservation<>();
        check(!owner.refresh(player,now,"3",true,false)); // Placement before initial join.
        check(!owner.refresh(player,now+1,"3",false,true)); // Anchor cannot substitute for ownership.
        check(owner.refresh(player,now+2,"3",true,true));
        check(!owner.refresh(player,now+3,"3",true,true)); // Once per connection.
        check(!owner.refresh(player,now+4,"3",true,false)); // Drift cannot freeze the snapshot.
        check(owner.snapshot().time()==now+4);
        check(OwnerObservation.available(owner.snapshot(),player,now+4));
        check(!owner.refresh(player,now+5,"3",false,false)); // False overwrites old true.
        check(!OwnerObservation.available(owner.snapshot(),player,now+5));
        owner.refresh(player,now+6,"3",true,false);
        check(OwnerObservation.available(owner.snapshot(),player,now+6+250_000_000L));
        check(!OwnerObservation.available(owner.snapshot(),player,now+7+250_000_000L));
        check(!OwnerObservation.available(owner.snapshot(),replacement,now+6));
        check(!OwnerObservation.available(null,player,now+6));
        OwnerObservation<Object> rejoin=new OwnerObservation<>();
        check(!rejoin.refresh(replacement,now+7,"3",true,false));
        check(rejoin.refresh(replacement,now+8,"3",true,true));
        // Replacement publication cannot be removed by the old owner's retirement callback.
        var owners=new java.util.concurrent.ConcurrentHashMap<String,OwnerObservation<Object>>();
        owners.put("C",owner);owners.put("C",rejoin);
        check(!owners.remove("C",owner));check(owners.get("C")==rejoin);
        owner.refresh(player,now+9,"old",false,true);
        check(OwnerObservation.available(owners.get("C").snapshot(),replacement,now+9));
        var first=owner.transition(true,8192,0);check(first!=null);
        owner.refresh(player,now+10,"old",false,true);
        check(owner.transition(true,8192,1)==null); // Position/time alone never emit.
        int emitted=1,truncated=0;
        for(int i=0;i<1000;i++){
            owner.refresh(player,now+11+i,"old",i%2==0,true);
            var row=owner.transition(true,8192,i);
            if(row!=null){if(row.get("event").equals("owner_transition_truncated"))truncated++;else emitted++;}
        }
        check(emitted==32&&truncated==1);
        owner.refresh(player,now+2000,"3",true,false);
        check(OwnerObservation.available(owner.snapshot(),player,now+2000)); // Diagnostics cap never gates ownership.
    }
}
