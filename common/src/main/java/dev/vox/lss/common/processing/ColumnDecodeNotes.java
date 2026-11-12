package dev.vox.lss.common.processing;

/**
 * What a platform serializer reports to the disk reader about ONE column, beside the
 * bytes: that it refused to serialize it ({@link #undecodable()}, undecodable-chunks plan,
 * 2026-11-12), or that it serialized a column saved without sky light
 * ({@link #lightless()}, lightless-chunks plan, same day: a diagnostics fact only).
 *
 * <p>The serializer twins only detect: when every block the column's cells use failed to
 * decode (a removed mod's blocks, a chunk format this build cannot read) they return
 * {@code null} and, if the caller passed one of these, mark it. {@code null} alone is what
 * keeps a caller that ignores the report safe (it skips the chunk, exactly like an
 * unparseable one, and can never store air); the mark is what lets the shared reader tell
 * this case from a plain miss, so it is never escalated to generation: loading such a
 * chunk through Minecraft would turn the unknown blocks into air and may save that.
 *
 * <p>One instance per read, confined to the thread that runs the read (the reader creates
 * it, the serializer writes it on that same thread, the reader reads it after the call
 * returns), so the fields need no synchronization.
 */
public final class ColumnDecodeNotes {
    private boolean undecodable;
    private String firstDecodeError;
    private boolean lightless;

    /**
     * The column decoded to nothing because block palette entries its cells use failed to
     * decode. {@code firstDecodeError} is the first such entry's decode message.
     */
    public void markUndecodable(String firstDecodeError) {
        this.undecodable = true;
        this.firstDecodeError = firstDecodeError;
    }

    public boolean undecodable() {
        return this.undecodable;
    }

    /** The first failed entry's decode message; {@code null} unless {@link #undecodable()}. */
    public String firstDecodeError() {
        return this.firstDecodeError;
    }

    /**
     * The column was serialized (it has blocks), but no section entry of the chunk carries
     * a sky-light layer with a non-zero nibble: the chunk is saved without sky light, as
     * world upgrades and conversions leave it. The bytes are exactly what they would be
     * without this mark; the reader only counts it, and only for a dimension that has
     * sky light.
     */
    public void markLightless() {
        this.lightless = true;
    }

    public boolean lightless() {
        return this.lightless;
    }
}
