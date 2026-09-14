package dev.vox.lssfixture.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual held-entry state controls; no fabricated native acceptance proof. */
public final class PendingAcceptanceSelfTest {
    private static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        AtomicInteger evaluated=new AtomicInteger(),released=new AtomicInteger(),failed=new AtomicInteger();
        long originalDeadline=80+120_000_000_000L;
        PendingAcceptance waiting=new PendingAcceptance(()->true,()->evaluated.incrementAndGet()>1,()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(!waiting.poll(2_000_000_000L,false));require(released.get()==0&&failed.get()==0); // No invented1s deadline.
        require(waiting.poll(3_000_000_000L,false));waiting.cancel();waiting.reject();
        require(evaluated.get()==2&&released.get()==1&&failed.get()==0);

        released.set(0);failed.set(0);evaluated.set(0);
        PendingAcceptance timeout=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return false;},()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(timeout.poll(originalDeadline+1,false));timeout.reject();timeout.cancel();
        require(evaluated.get()==1&&released.get()==1&&failed.get()==1);

        released.set(0);failed.set(0);evaluated.set(0);
        PendingAcceptance stall=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return true;},()->Long.MAX_VALUE,released::incrementAndGet,failed::incrementAndGet);
        require(!stall.poll(20_000_000_000L,true));require(evaluated.get()==0&&released.get()==0&&failed.get()==0);
        require(stall.poll(20_000_000_001L,false));require(released.get()==1&&failed.get()==0);

        released.set(0);failed.set(0);
        AtomicBoolean active=new AtomicBoolean(true);
        PendingAcceptance retired=new PendingAcceptance(active::get,()->{throw new AssertionError("must not evaluate retired work");},()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        active.set(false);require(retired.poll(originalDeadline+1,false));retired.cancel();
        require(released.get()==1&&failed.get()==0); // In-flight retirement outranks timeout without queue cancellation.

        released.set(0);failed.set(0);
        AtomicBoolean originalCommittedElsewhere=new AtomicBoolean(false);
        PendingAcceptance superseded=new PendingAcceptance(()->!originalCommittedElsewhere.get(),()->false,()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(!superseded.poll(100,false));originalCommittedElsewhere.set(true);
        require(superseded.poll(originalDeadline+1,false));require(released.get()==1&&failed.get()==0);

        released.set(0);failed.set(0);
        PendingAcceptance error=new PendingAcceptance(()->true,()->{throw new IllegalStateException("original interval ended");},()->originalDeadline,released::incrementAndGet,failed::incrementAndGet);
        require(error.poll(101,false));error.cancel();require(released.get()==1&&failed.get()==1);

        released.set(0);failed.set(0);
        PendingAcceptance overflow=new PendingAcceptance(()->true,()->true,()->Long.MAX_VALUE,released::incrementAndGet,failed::incrementAndGet);
        overflow.reject();overflow.reject();require(released.get()==1&&failed.get()==1);

        // Earliest original obligation is independently committed; only the later unknown remains.
        released.set(0);failed.set(0);
        final long[] outstandingDeadline={100};
        PendingAcceptance later=new PendingAcceptance(()->true,()->false,()->outstandingDeadline[0],released::incrementAndGet,failed::incrementAndGet);
        outstandingDeadline[0]=300;
        require(!later.poll(200,false));require(failed.get()==0&&released.get()==0);
        require(later.poll(301,false));require(failed.get()==1&&released.get()==1);

        // Unknown resolved elsewhere cannot skip evaluating a remaining known mismatch.
        released.set(0);failed.set(0);evaluated.set(0);
        PendingAcceptance mixed=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return true;},()->Long.MAX_VALUE,released::incrementAndGet,failed::incrementAndGet);
        require(mixed.poll(originalDeadline+1,false));require(evaluated.get()==1&&released.get()==1&&failed.get()==0);

        AtomicInteger reports=new AtomicInteger();
        final AcceptancePolicy.Facts[] facts={new AcceptancePolicy.Facts(0,80,0,200,0,true)};
        PendingAcceptance delayed=new PendingAcceptance(()->true,()->{
            var decision=AcceptancePolicy.decide(facts[0],true,true,120,130,1,true);
            if(decision==AcceptancePolicy.Decision.AWAIT_APPLICATION)return false;
            if(decision==AcceptancePolicy.Decision.FAILURE)throw new AssertionError("invalid clock");
            require(decision==AcceptancePolicy.Decision.COMMIT);return true;
        },()->originalDeadline,()->{},()->{throw new AssertionError();});
        require(!delayed.poll(101,false));facts[0]=new AcceptancePolicy.Facts(0,80,100,200,0,true);
        require(delayed.poll(102,false));require(reports.get()==0);
        require(AcceptancePolicy.decide(new AcceptancePolicy.Facts(0,80,125,200,0,true),true,true,120,130,1,true)==AcceptancePolicy.Decision.OBSERVE);
        require(AcceptancePolicy.decide(facts[0],true,true,120,200,1,true)==AcceptancePolicy.Decision.IGNORE);
        admissionControls();
        sparseFaultControls();
        System.out.println("PendingAcceptance: delayed original fact, actual preapply, original deadline, stall, retirement/supersession priority, error and rejection once passed");
    }

    private static void sparseFaultControls(){
        var arm=new OracleJournal.SparseArm("r","RigSubjectD","r-RigSubjectD-1","r-RigSubjectD-0-edit-0",170,170+120_000_000_000L,20_000_000_000L);
        var fault=new PendingAcceptance.SparseFault();fault.arm(arm);fault.arm(arm);
        require(!fault.stalled(171),"arm alone must not invent actual stall");
        require(!fault.eligible("old",arm.target(),171,172,173,1),"old connection rejected");
        require(!fault.eligible(arm.connection(),"other",171,172,173,1),"wrong target rejected");
        require(!fault.eligible(arm.connection(),arm.target(),0,172,173,1),"unknown application rejected");
        require(!fault.eligible(arm.connection(),arm.target(),173,172,174,1),"pre-application body rejected");
        require(!fault.eligible(arm.connection(),arm.target(),171,172,169,1),"early callback rejected");
        require(!fault.eligible(arm.connection(),arm.target(),171,172,arm.deadline(),1),"deadline exclusive");
        require(!fault.eligible(arm.connection(),arm.target(),171,172,173,0),"missing wire rejected");
        long start=110_000_000_000L; // Receipt can arrive long after arming; duration remains exactly20s.
        var trigger=fault.begin(arm.connection(),arm.target(),171,172,start,9,7);
        require(trigger!=null&&(long)trigger.get("end_ns")==start+20_000_000_000L,"first real receipt fixes original interval");
        fault.arm(null);fault.arm(arm); // A cached/older journal snapshot cannot overwrite the active timer.
        require(fault.stalled(start+19_999_999_999L)&&!fault.stalled(start+20_000_000_000L),"exact20s boundary");
        require(fault.begin(arm.connection(),arm.target(),171,172,start+1,10,8)==null,"duplicate callback cannot renew");
        require(fault.trigger().equals(trigger),"trigger identity immutable");
        AtomicInteger evaluated=new AtomicInteger(),released=new AtomicInteger();
        PendingAcceptance entry=new PendingAcceptance(()->true,()->{evaluated.incrementAndGet();return true;},()->Long.MAX_VALUE,released::incrementAndGet,()->{throw new AssertionError();});
        require(!entry.poll(start+19_999_999_999L,fault.stalled(start+19_999_999_999L))&&evaluated.get()==0,"real held-entry poll suppresses observation");
        require(entry.poll(start+20_000_000_000L,fault.stalled(start+20_000_000_000L))&&released.get()==1&&evaluated.get()==1,"actual release follows interval");
        entry.cancel();require(released.get()==1,"release once");
        var missing=new PendingAcceptance.SparseFault();missing.arm(arm);require(missing.expired(arm.deadline()),"missing receipt explicitly expires");
        missing.retire();require(missing.begin(arm.connection(),arm.target(),171,172,start,9,7)==null,"retired session cannot trigger");
        fault.retire();require(!fault.stalled(start+1),"retirement ends retained obligation, never renews");
        try{fault.arm(new OracleJournal.SparseArm("r","RigSubjectD",arm.connection(),arm.target(),171,171+120_000_000_000L,20_000_000_000L));throw new AssertionError("rearm accepted");}catch(IllegalStateException expected){}
        System.out.println("SparseFault: actual20s held-entry boundary, immutable receipt identity, stale snapshot, old-session/target/preapply/late/missing-wire rejection, no renewal and bounded expiry passed");
    }

    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Receipt {
        final List<String> order=new ArrayList<>();
        final int coordinate,source;
        Receipt(){this(-1,-1);}
        Receipt(int coordinate,int source){this.coordinate=coordinate;this.source=source;}
        boolean active=true,failed,completed,released;
        int reports,releases;
        void report(){order.add("report");if(active&&!failed){failed=true;reports++;}}
        void release(){order.add("release");if(!released){released=true;releases++;}}
        boolean accepted(){return active&&completed&&released&&!failed;}
    }
    private static void admissionControls(){
        Semaphore slots=new Semaphore(128);PendingAcceptance.Admission admission=new PendingAcceptance.Admission(slots);
        List<PendingAcceptance> held=new ArrayList<>();List<Receipt> receipts=new ArrayList<>();
        AtomicInteger commits=new AtomicInteger();Set<Integer> committedTargets=new HashSet<>();
        // Two distinct receipts per coordinate (disk1 then live0), never coalesced.
        Set<Receipt> distinctReceipts=new HashSet<>();
        for(int i=0;i<160;i++){
            int coordinate=i/2;
            Receipt receipt=new Receipt(coordinate,i%2==0?1:0);receipts.add(receipt);distinctReceipts.add(receipt);
            var result=admission.acquireOrRefuse(receipt::report,receipt::release);
            receipt.completed=true;
            if(i<128){
                require(result==PendingAcceptance.Admission.Result.ADMITTED,"available slot must admit");
                held.add(new PendingAcceptance(()->receipt.active,()->{if(committedTargets.add(coordinate))commits.incrementAndGet();return true;},
                        ()->120_000_000_000L,()->{receipt.release();slots.release();},()->{throw new AssertionError("held work failed");}));
            }else{
                require(result==PendingAcceptance.Admission.Result.REFUSED,"full slot refusal is expected, not fixture failure");
                require(receipt.order.equals(List.of("report","release")),"must reject before acceptance lease releases");
                require(receipt.reports==1&&receipt.releases==1&&!receipt.accepted(),"refused body cannot silently settle accepted");
            }
        }
        for(int i=0;i<80;i++)require(receipts.get(i*2).coordinate==i&&receipts.get(i*2+1).coordinate==i
                &&receipts.get(i*2).source==1&&receipts.get(i*2+1).source==0,"two original receipt identities per coordinate");
        require(distinctReceipts.size()==160&&held.size()==128&&slots.availablePermits()==0,"retention remains128 including worker-owned entries");
        require(admission.refusals()==32&&admission.reportErrors()==0&&admission.releaseErrors()==0,"bounded refusal counters exact");
        for(var entry:held)require(!entry.poll(19_999_999_999L,true),"forced20s stall must suppress evaluation");
        require(commits.get()==0,"no target credit during stall or for refusals");
        for(var entry:held){require(entry.poll(20_000_000_000L,false),"valid held body settles after stall");entry.cancel();entry.reject();}
        require(slots.availablePermits()==128&&commits.get()==64,"all128 permits released once");
        for(int i=0;i<128;i++)require(receipts.get(i).accepted()&&receipts.get(i).releases==1,"admitted receipt settles once");
        // A later valid retry can be admitted; this does not simulate the product's retry scheduling.
        for(int i=0;i<32;i++){
            Receipt retry=new Receipt();int coordinate=64+i/2;
            require(admission.acquireOrRefuse(retry::report,retry::release)==PendingAcceptance.Admission.Result.ADMITTED,"capacity returns to later deliveries");
            retry.completed=true;
            PendingAcceptance entry=new PendingAcceptance(()->true,()->{if(committedTargets.add(coordinate))commits.incrementAndGet();return true;},
                    ()->120_000_000_000L,()->{retry.release();slots.release();},()->{throw new AssertionError();});
            require(entry.poll(21_000_000_000L,false)&&retry.accepted()&&retry.reports==0,"only actual later evaluation credits retry");
        }
        require(commits.get()==80&&committedTargets.size()==80&&slots.availablePermits()==128,"80 paired coordinate obligations retained without treating160 bodies as160 targets");

        PendingAcceptance.Admission full=new PendingAcceptance.Admission(new Semaphore(0));Receipt retired=new Receipt();retired.active=false;retired.completed=true;
        require(full.acquireOrRefuse(retired::report,retired::release)==PendingAcceptance.Admission.Result.REFUSED,"retirement itself is not failure");
        require(retired.reports==0&&retired.releases==1&&!retired.accepted(),"receipt suppresses retired report without leaking lease");
        List<String> order=new ArrayList<>();
        require(full.acquireOrRefuse(()->{order.add("report");throw new AssertionError("report failure");},()->order.add("release"))==PendingAcceptance.Admission.Result.REFUSAL_FAILED,"report exception remains a fixture error");
        require(order.equals(List.of("report","release"))&&full.reportErrors()==1,"report throw must still release");
        require(full.acquireOrRefuse(()->{},()->{throw new AssertionError("release failure");})==PendingAcceptance.Admission.Result.REFUSAL_FAILED,"release failure remains fatal");
        require(full.releaseErrors()==1,"release error counted once");
        require(full.acquireOrRefuse(()->{throw new AssertionError();},()->{throw new AssertionError();})==PendingAcceptance.Admission.Result.REFUSAL_FAILED,"both errors contained");
        require(full.reportErrors()==2&&full.releaseErrors()==2&&full.refusals()==4,"both error counters retained independently");
        // Ordinary admitted work never calls report/release from admission itself.
        require(new PendingAcceptance.Admission(new Semaphore(1)).acquireOrRefuse(()->{throw new AssertionError();},()->{throw new AssertionError();})==PendingAcceptance.Admission.Result.ADMITTED,"admission is observationally neutral");
        System.out.println("PendingAcceptance.Admission:80 pairs/160 distinct receipts/128+32 saturation,20s stall, report-before-release, no false acceptance, returned-capacity retries, retirement and exception controls passed");
    }

}
