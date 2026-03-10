package dev.vox.lss.common.processing;

/**
 * Interface for read result accessors used by {@link OffThreadProcessor}.
 * Implemented by both Fabric's ChunkDiskReader.ReadResult and Paper's
 * PaperChunkDiskReader.SimpleReadResult, eliminating trivial delegate
 * methods in the platform-specific processors.
 */
public interface ReadResultAccess {
    int chunkX();
    int chunkZ();
    int requestId();
    long columnTimestamp();
    boolean notFound();
    long submissionOrder();

    /**
     * Returns true if this result was produced because the disk reader thread pool
     * rejected the task (saturated). The position should be retried later, not
     * treated as "not found".
     */
    default boolean saturated() { return false; }

    /**
     * Returns the serialized section bytes from this read result, or null if not available
     * (e.g. for notFound results). Used for cross-player disk read deduplication.
     */
    default byte[] sectionBytes() { return null; }

    /**
     * Returns the estimated wire size of the compressed sections, or 0.
     */
    default int estimatedBytes() { return 0; }
}
