package dev.vox.lssfixture.concurrent;
import java.util.HashSet;
public final class MeasuredScheduleTest {
    private static void require(boolean condition){if(!condition)throw new AssertionError();}
    private static void fails(Runnable action){try{action.run();}catch(IllegalStateException expected){return;}throw new AssertionError("failure required");}
    public static void main(String[] args){
        var cells=new HashSet<String>();
        for(int cell=0;cell<256;cell++){
            int x=MeasuredSchedule.chunkX(cell),z=MeasuredSchedule.chunkZ(cell);
            require(x*x+z*z<=32*32);require(x>=12);require(cells.add(x+":"+z));
        }
        var schedule=new MeasuredSchedule();int count=0;long[] previous=new long[256];
        java.util.Arrays.fill(previous,-1);
        for(long now=0;now<=MeasuredSchedule.CLOSE_NS;now+=50_000_000L){
            int round=schedule.poll(now);if(round<0)continue;
            require(round==count++);int cell=round%256;
            if(previous[cell]>=0)require(now-previous[cell]>=31_000_000_000L);
            previous[cell]=now;
        }
        require(count==5760);schedule.requireComplete();
        fails(()->new MeasuredSchedule().poll(1_000_000_001L));
        fails(()->new MeasuredSchedule().poll(MeasuredSchedule.CLOSE_NS));
        System.out.println("Measured schedule: domain, 5760 rounds, revisit, stalled and truncated producer controls passed");
    }
}
