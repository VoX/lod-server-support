package dev.vox.lss.networking.server;

import dev.vox.lss.common.ContentHash64;
import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.compat.AntiXrayCompat;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Serializes loaded chunk columns into MC-native wire format.
 * Uses {@link LevelChunkSection#write(FriendlyByteBuf)} for block states + biomes,
 * plus raw DataLayer nibble bytes for light data.
 *
 * <p>Thread contract: the serve paths call this on the server thread; the dirty-detection
 * hook (issue #69 retarget) may additionally call it from whatever thread legally runs
 * {@code SerializableChunkData.copyOf} — C2ME/Moonrise save workers — where reading the
 * section and light data is safe because copyOf itself performs the same class of reads
 * there (under Moonrise the light layers come from Starlight's SWMR nibble arrays, which
 * are multi-reader by design).
 */
public final class SectionSerializer {
    private SectionSerializer() {}

    /**
     * Serialize all non-air sections of a loaded chunk column into MC-native wire format.
     * Returns a {@link LoadedColumnData} with pre-serialized bytes.
     */
    private record SectionInfo(int index, int sectionY, SectionPos sectionPos,
                               DataLayer blLayer, boolean hasBlockLight,
                               DataLayer slLayer, boolean hasSkyLight) {}

    public static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        // One AntiXray-shim scope per COLUMN (not per section): section.write crashes under
        // the AntiXray mod's mixins outside this binding — see AntiXrayCompat.
        return AntiXrayCompat.callSerializing(() -> {
            byte[] nativeBody = nativeColumnInner(level, chunk, cx, cz);
            if (nativeBody == null) {
                return new LoadedColumnData(cx, cz, null, 0);
            }
            // C1 produce-path v20 hook (progress-doc decision 2026-08-07): the native
            // emit is unchanged; the v20 body is its canonical translation. Live and
            // generation serves (which reuse this method) see the same translated bytes.
            byte[] serialized = translate(nativeBody, level);
            return new LoadedColumnData(cx, cz, serialized, serialized.length);
        });
    }

    /** The served (v20) form of a native body: the one translation {@link #serializeColumn}
     *  applies. Public for the parity gametest, which checks it against a live chunk. */
    public static byte[] translate(byte[] nativeBody, ServerLevel level) {
        return NbtSectionSerializer.toV20(nativeBody, level.registryAccess());
    }

    /**
     * The column's LOD-visible content in the MC-native layout, before the v20
     * translation; null when there is nothing to serve. This is what the save-time change
     * detector hashes ({@link DirtyContentFilter}): the translation is a function of
     * these bytes (it rewrites palette values and nothing else), so two columns have
     * equal native bodies exactly when they have equal served bytes, and the detector
     * does not pay for a translation on the server thread at every chunk load and save.
     * {@link #serializeColumn} is this plus the translation, by construction.
     */
    public static byte[] nativeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        return AntiXrayCompat.callSerializing(() -> nativeColumnInner(level, chunk, cx, cz));
    }

    /**
     * The content hash of {@link #nativeColumn}'s bytes ({@link ContentHash64}), or
     * {@code emptyHash} when the column has nothing to serve. Same bytes, but written into
     * this thread's scratch buffer and hashed there: nothing is allocated or copied out.
     * This is the save-time change detector's path, which runs for every chunk load and
     * save on the server thread.
     */
    public static long nativeColumnHash(ServerLevel level, LevelChunk chunk, int cx, int cz, long emptyHash) {
        return AntiXrayCompat.callSerializing(() -> {
            var scratch = Scratch.acquire();
            try {
                // A hash is not a serve: sections masked here are not counted as served.
                if (!writeNativeBody(level, chunk, cx, cz, scratch.sink, false)) {
                    return emptyHash;
                }
                ByteBuf heap = scratch.heap;
                return ContentHash64.hash(heap.array(), heap.arrayOffset() + heap.readerIndex(),
                        heap.readableBytes());
            } finally {
                scratch.release();
            }
        });
    }

    private static byte[] nativeColumnInner(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        var scratch = Scratch.acquire();
        try {
            if (!writeNativeBody(level, chunk, cx, cz, scratch.sink, true)) {
                return null;
            }
            byte[] nativeBody = new byte[scratch.heap.readableBytes()];
            scratch.heap.readBytes(nativeBody);
            return nativeBody;
        } finally {
            scratch.release();
        }
    }

    /**
     * A heap buffer and its {@link SectionSink}, one per thread, reused from call to call:
     * a column is about 35 KB, and growing a fresh buffer to that size for every chunk load
     * and save was a quarter of what was left of this class in the server thread's profile.
     * The threads are the server thread and, under C2ME or Moonrise, the save workers.
     *
     * <p>{@code busy} guards re-entry: if writing a section ever leads back into this
     * class on the same thread (a foreign mixin that loads a chunk from inside
     * {@code section.write}, whose load event seeds the change detector), the inner call
     * gets its own buffer instead of writing over the outer one.
     */
    private static final class Scratch {
        private static final int INITIAL_BYTES = 64 * 1024;
        /** A buffer that grew past this for one huge column is not kept. */
        private static final int KEEP_BYTES = 1024 * 1024;
        private static final ThreadLocal<Scratch> PER_THREAD = ThreadLocal.withInitial(() -> new Scratch(true));

        ByteBuf heap;
        SectionSink sink;
        private final boolean pooled;
        private boolean busy;

        private Scratch(boolean pooled) {
            this.pooled = pooled;
            reset();
        }

        private void reset() {
            this.heap = Unpooled.buffer(INITIAL_BYTES);
            this.sink = new SectionSink(this.heap);
        }

        static Scratch acquire() {
            Scratch scratch = PER_THREAD.get();
            if (scratch.busy) {
                return new Scratch(false);
            }
            scratch.busy = true;
            scratch.heap.clear();
            return scratch;
        }

        void release() {
            if (!this.pooled) {
                return;
            }
            if (this.heap.capacity() > KEEP_BYTES) {
                reset();
            }
            this.busy = false;
        }
    }

    /**
     * The buffer sections are written into: a plain {@link FriendlyByteBuf} over a heap
     * buffer, except that a long array goes in as one block. A section's packed block
     * data is some 300 longs, and writing them one {@code writeLong} at a time (a
     * capacity check and a virtual call each, about 4,400 times a column) was the largest
     * single cost of the change detector after the hash was replaced. The bytes are the
     * same: big-endian longs at the writer index.
     */
    static final class SectionSink extends FriendlyByteBuf {
        private static final java.lang.invoke.VarHandle LONG_BE = java.lang.invoke.MethodHandles
                .byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.BIG_ENDIAN);
        private final ByteBuf heap;

        SectionSink(ByteBuf heap) {
            super(heap);
            this.heap = heap;
        }

        // No @Override: this method exists on FriendlyByteBuf from Minecraft 1.21.5 on,
        // where PalettedContainer writes its storage with it. On older lines it is simply
        // an unused method and writeLongArray below is the one that is called.
        public FriendlyByteBuf writeFixedSizeLongArray(long[] longs) {
            putLongs(longs);
            return this;
        }

        @Override
        public FriendlyByteBuf writeLongArray(long[] longs) {
            writeVarInt(longs.length);
            putLongs(longs);
            return this;
        }

        private void putLongs(long[] longs) {
            if (!this.heap.hasArray()) {
                for (long value : longs) this.heap.writeLong(value);
                return;
            }
            int bytes = longs.length << 3;
            this.heap.ensureWritable(bytes);
            int at = this.heap.writerIndex();
            byte[] array = this.heap.array(); // after ensureWritable: growing replaces it
            int base = this.heap.arrayOffset() + at;
            for (int i = 0; i < longs.length; i++) {
                LONG_BE.set(array, base + (i << 3), longs[i]);
            }
            this.heap.writerIndex(at + bytes);
        }
    }

    /** Writes the native body into {@code buf}; false (nothing written that counts) when
     *  the column has no content to serve. */
    private static boolean writeNativeBody(ServerLevel level, LevelChunk chunk, int cx, int cz,
                                           FriendlyByteBuf buf, boolean countMasked) {
        int minSectionY = level.getMinSectionY();
        var sections = chunk.getSections();
        var lightEngine = level.getLightEngine();
        var blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

        // First pass: collect non-air sections and cache light results. Air-only sections
        // WITH stored non-zero sky light are served too (2026-07-27, black-boundary-faces
        // fix): vanilla's light grid extends one section past terrain, and those boundary
        // air layers are exactly what lights the top/side faces of the terrain below/beside
        // them at a chunk border. A consumer sampling a never-served neighbor section gets
        // light 0 — leaves rendered black from one side. Fully-dark air (caves, deep
        // enclosures) still skips: its layers are null/zero, so the serve set stays
        // bounded to vanilla's own stored-light coverage (~1-2 extra sections per column).
        var includedSections = new java.util.ArrayList<SectionInfo>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null) continue;
            int sectionY = minSectionY + i;
            var sectionPos = SectionPos.of(cx, sectionY, cz);
            var blLayer = blockLightListener.getDataLayerData(sectionPos);
            boolean hasBlockLight = blLayer != null && hasNonZeroData(blLayer);
            var slLayer = skyLightListener.getDataLayerData(sectionPos);
            boolean hasSkyLight = slLayer != null && hasNonZeroData(slLayer);

            if (section.hasOnlyAir() && !hasBlockLight && !hasSkyLight) continue;

            includedSections.add(new SectionInfo(i, sectionY, sectionPos,
                    blLayer, hasBlockLight, slLayer, hasSkyLight));
        }

        // Band rule, same as the NBT path (disk/live byte parity): SKY-lit air serves only
        // within one section of the content band — vanilla's own stored-light coverage —
        // so a void/cleared column's ambient sky can never turn a zero-section CLEAR into
        // a data column. BLOCK-lit air keeps its long-standing unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var info : includedSections) {
            if (!sections[info.index()].hasOnlyAir()) {
                minContent = Math.min(minContent, info.sectionY());
                maxContent = Math.max(maxContent, info.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        includedSections.removeIf(info -> sections[info.index()].hasOnlyAir()
                && !info.hasBlockLight()
                && (noContent || info.sectionY() < lo || info.sectionY() > hi));

        if (includedSections.isEmpty()) {
            return false;
        }

        // Second pass: serialize using cached results
        var maskEntry = XrayMaskManager.entryForActive(level);
        var maskFactory = maskEntry != null ? NbtSectionSerializer.factoryFor(level.registryAccess()) : null;
        buf.writeVarInt(includedSections.size());

        for (var info : includedSections) {
            var section = sections[info.index];
            if (maskEntry != null) {
                // Masking INSIDE the choke point: probe, generation, and the
                // DirtyContentFilter hash all see identical masked content by construction.
                int[] replacedCells = new int[1];
                var masked = XrayMaskFilter.mask(section, info.sectionY,
                        maskEntry.mask(), maskEntry.kind(), maskFactory, replacedCells);
                if (masked != section) {
                    section = masked;
                    // Count only when cells were actually hidden — a stale-palette
                    // rebuild (mined-out ore still listed) swaps for the prune only.
                    if (countMasked && replacedCells[0] > 0) {
                        var manager = XrayMaskManager.current();
                        if (manager != null) manager.countMaskedSection();
                    }
                }
            }

            buf.writeByte(info.sectionY);
            section.write(buf);

            // Block light (cached from pass 1)
            buf.writeBoolean(info.hasBlockLight);
            if (info.hasBlockLight) {
                buf.writeBytes(info.blLayer.getData());
            }

            // Sky light (cached from pass 1)
            buf.writeBoolean(info.hasSkyLight);
            if (info.hasSkyLight) {
                buf.writeBytes(info.slLayer.getData());
            }
        }

        return true;
    }

    private static final java.lang.invoke.VarHandle LONGS = java.lang.invoke.MethodHandles
            .byteArrayViewVarHandle(long[].class, java.nio.ByteOrder.LITTLE_ENDIAN);

    private static boolean hasNonZeroData(DataLayer layer) {
        return hasNonZero(layer.getData());
    }

    /** Eight bytes at a time: this runs twice per section for every chunk load and save
     *  (an all-dark layer is read to its end), and it showed in the server thread's profile. */
    static boolean hasNonZero(byte[] data) {
        int i = 0;
        for (int end = data.length - 7; i < end; i += 8) {
            if ((long) LONGS.get(data, i) != 0L) return true;
        }
        for (; i < data.length; i++) {
            if (data[i] != 0) return true;
        }
        return false;
    }
}
