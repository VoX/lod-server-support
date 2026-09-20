package dev.vox.lssfixture.concurrent;

/** Fixed offered load; independent of request-manager progress and wall-clock tick speed. */
public final class MeasuredSchedule {
    public static final int CELLS=256, ROUNDS=5760;
    public static final long INTERVAL_NS=125_000_000L, CLOSE_NS=720_000_000_000L;
    private int next;
    public static int chunkX(int cell){checkCell(cell);return 12+cell/16;}
    public static int chunkZ(int cell){checkCell(cell);return -7+cell%16;}
    private static void checkCell(int cell){if(cell<0 || cell>=CELLS)throw new IllegalArgumentException("cell outside measured domain");}
    /** One round at most per owner tick. A stalled producer cannot catch up in an unbounded burst. */
    public int poll(long elapsed) {
        if(elapsed<0)throw new IllegalArgumentException("negative workload time");
        if(elapsed>=CLOSE_NS){requireComplete();return -1;}
        long due=next*INTERVAL_NS;
        if(elapsed<due)return -1;
        if(elapsed-due>1_000_000_000L)throw new IllegalStateException("measured offers fell over one second behind schedule");
        return next++;
    }
    public void requireComplete(){if(next!=ROUNDS)throw new IllegalStateException("measured schedule did not offer every preregistered target");}
}
