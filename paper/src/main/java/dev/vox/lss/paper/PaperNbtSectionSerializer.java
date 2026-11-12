package dev.vox.lss.paper;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.ColumnDecodeNotes;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads chunk NBT from disk and serializes sections into MC-native wire format.
 * Used by {@link PaperChunkDiskReader} for async disk reads.
 *
 * <p>Headless serve path (2026-07-29 profile — mirrors the Fabric twin exactly): the
 * UNMASKED path never constructs a {@link LevelChunkSection}. The two wire count headers
 * come from {@link #countNonEmptyAndFluid}'s palette histogram instead of the ctor's
 * per-cell recount (on Paper the ctor is even costlier — Moonrise's recalc also builds
 * per-state coordinate lists the wire never needs), and the containers write themselves.
 * Palette-entry block-state decode goes through {@link PaperMemoizedNbtCodec}. The MASKED
 * path still constructs real sections: mask semantics rely on the counting ctor for the
 * masked headers (see PaperXrayMaskFilter).
 *
 * <p>Round-2 transcode (docs/planning/nbt-transcode-design.md, {@code useNbtTranscode} —
 * see the Fabric twin for the full invariant): the default disk path skips the container
 * codec entirely — {@link #transcodeSection} reads the palette + long array straight out
 * of the NBT and {@link TranscodedBody} emits the wire bytes directly, byte-identical by
 * the unpack-verbatim invariant (Configuration.Simple: disk and memory agree on width and
 * palette order). Shapes outside it fall back PER SECTION to the object path: Global
 * configs (>256-entry block / >8-entry biome palettes), malformed or missing block data,
 * empty palettes, non-long-array data tags, and mask-needing sections (the pre-gate
 * mirrors {@code PaperXrayMaskFilter.needsMasking} exactly).
 */
final class PaperNbtSectionSerializer {
    private PaperNbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];
    private static final long[] EMPTY_LONGS = new long[0];
    private static final byte[] ZERO_NIBBLES = new byte[2048];

    /** Test seam: the region-file NBT read — the only NMS call in the Paper disk-read path.
     *  Production wires {@link ChunkMap#read}; tests inject empty / failing / timing-out
     *  futures to pin the submit-envelope triage. */
    @FunctionalInterface
    interface ChunkNbtRead {
        CompletableFuture<Optional<CompoundTag>> read(int cx, int cz);
    }

    /**
     * Read chunk NBT from disk, verify FULL status, and serialize sections
     * into MC-native wire format. {@code maskEntry} (nullable) is the dimension's x-ray
     * mask, captured by the caller at submit time.
     * Returns the serialized byte array, or null if the chunk is missing/not FULL/empty —
     * or carries a truly-unparseable in-range section (R2-1: the whole column resolves as
     * an authoritative miss rather than serving with a silent hole).
     */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            PaperXrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY,
                                            boolean useNbtTranscode) throws Exception {
        return readAndSerializeSections(read, registryAccess, cx, cz, maskEntry, minSectionY,
                maxSectionY, useNbtTranscode, null);
    }

    /** The read with the reader's {@link ColumnDecodeNotes} (nullable): null bytes with
     *  the notes marked means the column is UNDECODABLE (see {@link
     *  #serializeChunkNbt(CompoundTag, RegistryAccess, PaperXrayMaskManager.MaskEntry,
     *  int, int, boolean, ColumnDecodeNotes)}), never a plain miss. */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            PaperXrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY,
                                            boolean useNbtTranscode,
                                            ColumnDecodeNotes notes) throws Exception {
        var future = read.read(cx, cz);
        var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) return null;
        return serializeChunkNbt(optionalTag.get(), registryAccess, maskEntry, minSectionY,
                maxSectionY, useNbtTranscode, notes);
    }

    /** Unmasked flavor — the shape the pre-masking tests and corpus pin. */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess) {
        return serializeChunkNbt(chunkNbt, registryAccess, null);
    }

    /** Range-free flavor (tests + corpus — the committed goldens serialize out-of-world
     *  Y values and must keep doing so; only the production path gates). */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** Production-default flavor: transcode ON (the {@code useNbtTranscode} default). */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY, maxSectionY, true);
    }

    // Unparseable-section warns are throttled — see the Fabric twin's rationale.
    private static final dev.vox.lss.common.LogThrottle PARSE_WARN_THROTTLE =
            new dev.vox.lss.common.LogThrottle(60_000);

    /** A parsed section, headless — see the Fabric twin: either a wire-ready
     *  {@code transcoded} body OR the object path's containers (exactly one is set). */
    record ParsedSection(int sectionY,
                         TranscodedBody transcoded,
                         PalettedContainer<BlockState> states,
                         PalettedContainerRO<Holder<Biome>> biomes,
                         int nonEmptyCount, int fluidCount,
                         byte[] blockLight, byte[] skyLight,
                         boolean litByBlock, boolean litBySky) {}

    /**
     * A wire-ready transcoded section body — see the Fabric twin: palette GLOBAL ids in
     * disk-list order plus the disk long array by reference; {@code write} emits exactly
     * {@code PalettedContainer$Data.write}'s shape (bits byte; single = one varint id,
     * linear/hashmap = varint count + ids in list order, duplicates included; raw
     * big-endian longs — 1.21.1 line: prefixed by the VarInt long count, this MC's
     * writeLongArray shape; see the Fabric twin).
     */
    record TranscodedBody(int blockBits, int[] blockIds, long[] blockData,
                          int biomeBits, int[] biomeIds, long[] biomeData) {

        /** Both containers' wire size (the section's two count shorts are the caller's). */
        int serializedSize() {
            return containerSize(this.blockBits, this.blockIds, this.blockData)
                    + containerSize(this.biomeBits, this.biomeIds, this.biomeData);
        }

        private static int containerSize(int bits, int[] ids, long[] data) {
            // 1.21.1 line: + the VarInt long-count prefix (0-length included — vanilla's
            // single-value container still writes its empty array through writeLongArray).
            int size = 1 + VarInt.getByteSize(data.length) + data.length * 8;
            if (bits == 0) {
                return size + VarInt.getByteSize(ids[0]);
            }
            size += VarInt.getByteSize(ids.length);
            for (int id : ids) {
                size += VarInt.getByteSize(id);
            }
            return size;
        }

        void write(FriendlyByteBuf buf) {
            writeContainer(buf, this.blockBits, this.blockIds, this.blockData);
            writeContainer(buf, this.biomeBits, this.biomeIds, this.biomeData);
        }

        private static void writeContainer(FriendlyByteBuf buf, int bits, int[] ids, long[] data) {
            buf.writeByte(bits);
            if (bits == 0) {
                buf.writeVarInt(ids[0]);
            } else {
                buf.writeVarInt(ids.length);
                for (int id : ids) {
                    buf.writeVarInt(id);
                }
            }
            // 1.21.1 line: vanilla's writeLongArray prefix (varint count, then words —
            // written for the empty single-value array too, exactly like vanilla).
            buf.writeVarInt(data.length);
            for (long l : data) {
                buf.writeLong(l);
            }
        }
    }

    /** Sizing-exactness telemetry — see the Fabric twin. Tests pin 0. */
    static final AtomicLong SIZE_MISMATCH_FALLBACKS = new AtomicLong();
    private static final AtomicBoolean SIZE_MISMATCH_WARNED = new AtomicBoolean();

    /** Direct-emit routing telemetry (C6 follow-up) — see the Fabric twin: byte-equal
     *  outputs make silent re-routing invisible to goldens; tests pin the routing. */
    static final AtomicLong DIRECT_V20_EMITS = new AtomicLong();

    /**
     * Serialize a chunk's NBT (as read from a region file) into MC-native wire format.
     * Returns {@code null} if the chunk is not FULL or has no sections, an empty array if every
     * section is empty, or the serialized section bytes. Package-visible for testing.
     *
     * <p>R2-1/R2-5 semantics (mirrors the Fabric twin): a renamed-block section parses via
     * {@code resultOrPartial} with air substitution (throttled warn); a truly-unparseable
     * IN-RANGE section returns {@code null} for the WHOLE column — an authoritative miss the
     * caller escalates to generation, never a partial serve and never a throw. Entries
     * outside {@code [minSectionY, maxSectionY]} are dropped BEFORE parse and can neither
     * serve nor condemn the column (vanilla saves light-only entries at blockRange±1; the
     * range-free overload above stays unbounded for the corpus goldens).
     */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY, boolean useNbtTranscode) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY, maxSectionY,
                useNbtTranscode, null);
    }

    /**
     * The serializer with the caller's {@link ColumnDecodeNotes} (nullable). An
     * UNDECODABLE column returns {@code null}, like a missing or unparseable chunk, so a
     * caller that passes no notes (the store backfill, the range-free test flavors) just
     * skips the chunk and can never store air; a caller that passes notes (the disk
     * reader) also learns WHY, and must not escalate the miss to generation. Undecodable
     * means: at least one block palette entry that a cell actually uses was a hard decode
     * error, and either no in-range section has a non-air cell left, or a section is
     * truly unparseable (the column would otherwise be a plain, generation-escalating
     * miss). Before this rule the first shape serialized as all-air (or as lit air
     * sections): stamped, stored, and sent as a clear over the client's cached terrain.
     * A column with a decoded block and no unparseable section keeps the vanilla
     * leniency exactly (unknown entries substitute air in place).
     */
    // LevelChunkSection.write(buf) is @Deprecated on Paper (an anti-xray overload was added),
    // but the 1-arg form is the canonical vanilla serialization and is byte-identical to the
    // Fabric path. The wire format must match Fabric exactly, so the MASKED branch keeps this
    // call (do not migrate); the headless branches write the identical shape by construction.
    @SuppressWarnings("deprecation")
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    PaperXrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY, boolean useNbtTranscode,
                                    ColumnDecodeNotes notes) {
        // 1.21.1 line: old-family CompoundTag getters (defaulting, not Optional-returning).
        var statusStr = chunkNbt.getString("Status");
        if (statusStr.isEmpty() || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var scoped = scopedFor(registryAccess);
        var factory = scoped.biomeRegistry();
        // Block-state container codec is LSS-built: vanilla's exact codecRW arguments
        // (fuzz + goldens pin equivalence) with only the ELEMENT codec swapped for the
        // palette-entry memo. Biomes keep the vanilla-shaped codec, memoized per registry
        // in the scoped slot — 1.21.1 line: no PalettedContainerFactory here (see the
        // Fabric twin's scopedFor).
        var blockStateCodec = BlockCodecHolder.CODEC;
        var biomeCodec = scoped.biomeCodec();

        var sectionsList = chunkNbt.getList("sections", Tag.TAG_COMPOUND);
        if (sectionsList.isEmpty()) return null;

        // First pass: parse sections and check if any are non-empty
        var parsed = new java.util.ArrayList<ParsedSection>(sectionsList.size());

        int[] unparseable = {0};
        // The first hard-error palette entry that a CELL uses, across the in-range
        // sections (null = none): the undecodable-column gate below reads it.
        String[] usedHardError = {null};
        boolean[] fallback = {false};
        for (var sectionElement : sectionsList) {
            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.contains("Y", Tag.TAG_ANY_NUMERIC)
                    ? sectionTag.getInt("Y") : Integer.MIN_VALUE;
            if (sectionY == Integer.MIN_VALUE) continue;
            // Range gate BEFORE parse: an out-of-range garbage entry must not count as
            // unparseable and condemn a column it would have been dropped from anyway.
            if (sectionY < minSectionY || sectionY > maxSectionY) continue;

            byte[] blockLightData = sectionTag.getByteArray("BlockLight");
            byte[] skyLightData = sectionTag.getByteArray("SkyLight");
            ParsedSection result;
            if (useNbtTranscode) {
                fallback[0] = false;
                result = transcodeSection(sectionTag, sectionY, scoped.biomeResolver(),
                        maskEntry, blockLightData, skyLightData, fallback, usedHardError);
                if (fallback[0]) {
                    result = parseSection(sectionTag, sectionY, blockStateCodec, biomeCodec,
                            factory, blockLightData, skyLightData, unparseable, usedHardError);
                }
            } else {
                result = parseSection(sectionTag, sectionY, blockStateCodec, biomeCodec,
                        factory, blockLightData, skyLightData, unparseable, usedHardError);
            }
            if (result != null) {
                parsed.add(result);
            }
        }
        if (usedHardError[0] != null && (unparseable[0] > 0 || !hasNonAirCell(parsed))) {
            // Undecodable column: cells point at palette entries that failed to decode,
            // and either
            //  - nothing else in the column decoded to a block: everything LSS could say
            //    about it would be substituted air, so it says nothing (light does not
            //    matter — a lit column of substituted air is the same lie); or
            //  - a section is truly unparseable, so the rung below would condemn the
            //    column as a plain miss. That miss may escalate to generation, and a
            //    chunk whose cells use blocks this server does not know must never be
            //    loaded by LSS (vanilla turns them into air on load and may save the
            //    chunk) — whatever else in it decoded.
            // Null either way, and the notes (when the caller passed them) carry the
            // reason. Checked BEFORE the unparseable rung for that second case.
            if (notes != null) notes.markUndecodable(usedHardError[0]);
            return null;
        }
        if (unparseable[0] > 0) {
            // Authoritative miss (null) — see the Fabric twin: a truly-unparseable section
            // makes the whole column unservable; the miss escalates to a generation ticket
            // that loads the chunk through the real DataFixer pipeline.
            return null;
        }

        // Boundary-light band (2026-07-27, black-boundary-faces fix — see the Fabric twin):
        // SKY-lit air serves only within one section of the content band; a column with NO
        // content sections stays a zero-section CLEAR. BLOCK-lit air keeps its
        // long-standing unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var p : parsed) {
            if (p.nonEmptyCount() != 0) {
                minContent = Math.min(minContent, p.sectionY());
                maxContent = Math.max(maxContent, p.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        // Diagnostics only — the bytes below do not depend on it: a column with blocks
        // whose chunk carries no sky light anywhere. Asked before the band filter, while
        // every in-range sky-lit section is still in the list.
        if (notes != null && !noContent && savedWithoutSkyLight(parsed, sectionsList)) {
            notes.markLightless();
        }
        parsed.removeIf(p -> p.nonEmptyCount() == 0
                && !p.litByBlock()
                && (noContent || p.sectionY() < lo || p.sectionY() > hi));

        if (parsed.isEmpty()) return new byte[0];

        // Masked path — see the Fabric twin: real sections, the same choke point the live
        // path masks in; mask headers can only be recomputed by the counting ctor, never
        // adjusted. Transcoded sections are SKIPPED here by construction: the transcode
        // pre-gate routed every section the filter would touch through the object path,
        // so a transcoded entry is one mask() provably returns unchanged. Counter
        // attribution at COMPLETION time is diag-only cosmetics.
        LevelChunkSection[] maskedSections = null;
        if (maskEntry != null) {
            maskedSections = new LevelChunkSection[parsed.size()];
            int[] replacedCells = new int[1];
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                if (p.transcoded() != null) continue;
                // Construction (incl. the RW-narrow + factory fallback) lives in the
                // S2 seam — see PaperSectionConstruction.fromContainers.
                var section = PaperSectionConstruction.fromContainers(
                        p.states(), p.biomes(), factory);
                var masked = PaperXrayMaskFilter.mask(section, p.sectionY(),
                        maskEntry.mask(), maskEntry.kind(), factory, replacedCells);
                maskedSections[i] = masked;
                // Count only when cells were actually hidden — see the Fabric twin.
                if (masked != section && replacedCells[0] > 0) {
                    var manager = PaperXrayMaskManager.current();
                    if (manager != null) manager.countMaskedSection();
                }
            }
        }

        // Direct v20 emit (the C6-triggered follow-up, 2026-08-08) — see the Fabric
        // twin: all-transcoded columns build the v20 column straight from the
        // descriptors (global ids + verbatim longs), skipping the native emit +
        // whole-column translator re-parse; byte-identical by the indexed rule.
        boolean allTranscoded = true;
        for (var p : parsed) {
            if (p.transcoded() == null) {
                allTranscoded = false;
                break;
            }
        }
        if (allTranscoded) {
            return emitV20Direct(parsed, registryAccess);
        }

        // Second pass: serialize to wire format, into an EXACTLY-sized buffer — see the
        // Fabric twin (zero netty growth; the backing array IS the payload on the exact
        // path; a mismatch falls back to the copy, never to wrong bytes).
        int size = VarInt.getByteSize(parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            var p = parsed.get(i);
            size += 1 // sectionY byte
                    + (p.transcoded() != null
                            ? dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS * 2 + p.transcoded().serializedSize()
                            : maskedSections != null
                                    ? maskedSections[i].getSerializedSize()
                                    : dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS * 2 + p.states().getSerializedSize() + p.biomes().getSerializedSize())
                    + 1 + (p.litByBlock() ? 2048 : 0)
                    + 1 + (p.litBySky() ? 2048 : 0);
        }

        var buf = new FriendlyByteBuf(Unpooled.buffer(size));
        try {
            buf.writeVarInt(parsed.size());
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                buf.writeByte(p.sectionY());
                if (p.transcoded() != null) {
                    // Headless transcoded write — LevelChunkSection.write's shape with the
                    // container bytes emitted straight from the disk descriptors.
                    writeNativeCountHeader(buf, p.nonEmptyCount(), p.fluidCount());
                    p.transcoded().write(buf);
                } else if (maskedSections != null) {
                    maskedSections[i].write(buf);
                } else {
                    // Headless section write — exactly LevelChunkSection.write's shape:
                    // the two count shorts, then the two containers.
                    writeNativeCountHeader(buf, p.nonEmptyCount(), p.fluidCount());
                    p.states().write(buf);
                    p.biomes().write(buf);
                }

                // All-zero layers are skipped to match the live serializer exactly (mirrors
                // Fabric's NbtSectionSerializer): "absent" means all-zero on the wire, and
                // vanilla saves the light engine's allocated-but-zeroed arrays, which would
                // otherwise make disk serves byte-diverge from live serves of identical content.
                buf.writeBoolean(p.litByBlock());
                if (p.litByBlock()) {
                    buf.writeBytes(p.blockLight());
                }

                buf.writeBoolean(p.litBySky());
                if (p.litBySky()) {
                    buf.writeBytes(p.skyLight());
                }
            }

            if (buf.writerIndex() == size && buf.arrayOffset() == 0 && buf.array().length == size) {
                return toV20(buf.array(), registryAccess);
            }
            SIZE_MISMATCH_FALLBACKS.incrementAndGet();
            if (SIZE_MISMATCH_WARNED.compareAndSet(false, true)) {
                LSSLogger.warn("Exact column pre-size mismatched written bytes (expected "
                        + size + ", wrote " + buf.writerIndex()
                        + ") — falling back to a copy; bytes are unaffected");
            }
            byte[] result = new byte[buf.readableBytes()];
            buf.readBytes(result);
            return toV20(result, registryAccess);
        } finally {
            buf.release();
        }
    }

    /**
     * The transcode descriptor pass — see the Fabric twin: palette global ids via the
     * memo, counts via a raw bit-storage histogram, light presence — no containers, no
     * codecs. Returns a transcoded {@link ParsedSection}; or null with {@code fallback[0]}
     * SET for shapes the transcoder does not own (the caller re-parses through the object
     * path); or null with {@code fallback[0]} clear when the section is dropped by the
     * same air/no-light gate the object path applies.
     */
    private static ParsedSection transcodeSection(CompoundTag sectionTag, int sectionY,
            BiomeIdResolver biomeResolver, PaperXrayMaskManager.MaskEntry maskEntry,
            byte[] blockLightData, byte[] skyLightData, boolean[] fallback,
            String[] usedHardError) {

        // ---- blocks: palette ids + the two count headers off the raw long array ----
        int blockBits = 0;
        int[] blockIds;
        long[] blockData = EMPTY_LONGS;
        int nonEmpty = 0, fluid = 0;
        int hardErrors = 0;
        String firstHardError = null;

        if (!sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
            // Vanilla's light-only cap entries (heightmap+1) carry SkyLight but no
            // block_states: an all-air single container, counts 0.
            blockIds = BlockCodecHolder.AIR_SINGLE;
        } else {
            var bs = sectionTag.getCompound("block_states");
            // 1.21.1 line: untyped list access via the raw tag (getList is typed here);
            // missing/mistyped palette falls back exactly like the Optional-empty did.
            var palette = bs.get("palette") instanceof net.minecraft.nbt.ListTag lt ? lt : null;
            if (palette == null) {
                fallback[0] = true;   // missing/mistyped palette — object path (condemns)
                return null;
            }
            int n = palette.size();
            if (n == 0 || n > 256) {
                fallback[0] = true;   // empty palette / Global config — object path
                return null;
            }
            blockIds = new int[n];
            long[] metas = new long[n];
            for (int i = 0; i < n; i++) {
                var entry = BlockCodecHolder.ELEMENT.resolve(palette.get(i));
                metas[i] = entry.meta();
                blockIds[i] = entry.globalId();
                if (entry.hardError() != null) {
                    // Vanilla leniency, mirrored: the entry substitutes the codec default
                    // (air) IN PLACE — indices never shift — and the section warns below.
                    hardErrors++;
                    if (firstHardError == null) firstHardError = entry.hardError();
                }
            }
            if (n == 1) {
                // ZeroBitStorage: bits 0, zero longs on the wire, any disk data IGNORED.
                if ((metas[0] & 1L) == 0) {
                    nonEmpty = 4096;
                    if ((metas[0] & 2L) != 0) fluid = 4096;
                }
            } else {
                blockBits = n <= 16 ? 4 : 32 - Integer.numberOfLeadingZeros(n - 1);
                long[] data = bs.contains("data", Tag.TAG_LONG_ARRAY)
                        ? bs.getLongArray("data") : null;
                int vpl = 64 / blockBits;
                if (data == null || data.length != (4096 + vpl - 1) / vpl) {
                    // Absent (vanilla condemns), mistyped (a list-of-longs data tag can
                    // legally parse through NbtOps' generic stream), or mis-sized
                    // (SimpleBitStorage's exact-length rule) — object path decides.
                    fallback[0] = true;
                    return null;
                }
                blockData = data;
                // The count-header histogram, straight off the raw longs — the same
                // LSB-first walk as SimpleBitStorage.getAll. An out-of-range palette
                // index throws AIOOBE exactly like the object path's histogram, and is
                // triaged upstream as a read error.
                int[] hist = new int[n];
                long mask = (1L << blockBits) - 1;
                int count = 0;
                histogram:
                for (long cell : data) {
                    for (int j = 0; j < vpl; j++) {
                        hist[(int) (cell & mask)]++;
                        cell >>>= blockBits;
                        if (++count == 4096) break histogram;
                    }
                }
                for (int i = 0; i < n; i++) {
                    int c = hist[i];
                    if (c == 0 || (metas[i] & 1L) != 0) continue;
                    nonEmpty += c;
                    if ((metas[i] & 2L) != 0) fluid += c;
                }
            }
        }

        // ---- mask pre-gate: exactly needsMasking() on the descriptor. A section the
        // filter would touch (or even palette-scan into a stale-entry rebuild) goes to
        // the object path; a section it provably leaves alone transcodes — mask() would
        // return the section itself, so the bytes agree either way. ----
        if (maskEntry != null) {
            var mask = maskEntry.mask();
            if (mask != null && !mask.isEmpty()
                    && (sectionY << 4) < mask.maxBlockHeight() && nonEmpty > 0) {
                for (int id : blockIds) {
                    if (mask.containsId(id)) {
                        fallback[0] = true;
                        return null;
                    }
                }
            }
        }

        // ---- biomes: any imperfection resolves to the DEFAULT single-entry container —
        // the object path's strict result() collapses unknown names, bad palettes, and
        // missing/mis-sized data the same way. Only two shapes leave the transcoder: a
        // >8-entry palette (Global config — vanilla repacks) and a present-but-non-long-
        // array data tag (NbtOps' generic stream could legally parse it), plus the
        // valueless empty palette; all three take the object path. ----
        int biomeBits = 0;
        int[] biomeIds = null;
        long[] biomeData = EMPTY_LONGS;
        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            var bt = sectionTag.getCompound("biomes");
            // 1.21.1 line: untyped list access (mistyped ENTRIES must resolve id -1 ->
            // default container, matching the 26.x untyped-Optional semantics).
            var palette = bt.get("palette") instanceof net.minecraft.nbt.ListTag lt ? lt : null;
            int n = palette == null ? -1 : palette.size();
            if (n == 0 || n > 8) {
                fallback[0] = true;
                return null;
            }
            if (n > 0) {
                int[] ids = new int[n];
                boolean clean = true;
                for (int i = 0; i < n && clean; i++) {
                    int id = palette.get(i) instanceof StringTag st
                            ? biomeResolver.idFor(st.getAsString()) : -1;
                    if (id < 0) clean = false;
                    else ids[i] = id;
                }
                if (clean) {
                    if (n == 1) {
                        biomeIds = ids;   // ZeroBitStorage: data ignored
                    } else {
                        int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
                        if (bt.contains("data") && !bt.contains("data", Tag.TAG_LONG_ARRAY)) {
                            fallback[0] = true;   // present but not a long array
                            return null;
                        }
                        long[] data = bt.contains("data", Tag.TAG_LONG_ARRAY)
                                ? bt.getLongArray("data") : null;
                        int vpl = 64 / bits;
                        if (data != null && data.length == (64 + vpl - 1) / vpl) {
                            biomeBits = bits;
                            biomeIds = ids;
                            biomeData = data;
                        }
                        // absent or mis-sized data: fall through to the default container
                    }
                }
            }
        }
        if (biomeIds == null) {
            biomeIds = new int[]{biomeResolver.defaultId()};
            biomeBits = 0;
            biomeData = EMPTY_LONGS;
        }

        if (hardErrors > 0) {
            // Which failed entries do cells actually use? Recorded HERE, after every
            // fallback return above: a section that falls back is decided by the object
            // path alone, through the same helper, so the two routes cannot disagree.
            if (usedHardError[0] == null) usedHardError[0] = firstUsedHardError(sectionTag);
            // The object path's resultOrPartial warn, mirrored for the air-substitution
            // case the transcoder owns (fallback shapes warn from the object path).
            long released = PARSE_WARN_THROTTLE.recordAndTryAcquire(System.nanoTime() / 1_000_000);
            if (released > 0) {
                String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                String more = hardErrors > 1 ? " (and " + (hardErrors - 1) + " more entries)" : "";
                LSSLogger.warn("Section block_states parse error (Y=" + sectionY + "): "
                        + firstHardError + more + suffix);
            }
        }

        boolean litByBlock = blockLightData.length == 2048 && hasNonZeroNibble(blockLightData);
        boolean litBySky = skyLightData.length == 2048 && hasNonZeroNibble(skyLightData);

        if (nonEmpty == 0 && !litByBlock && !litBySky) {
            return null;
        }

        return new ParsedSection(sectionY,
                new TranscodedBody(blockBits, blockIds, blockData, biomeBits, biomeIds, biomeData),
                null, null, nonEmpty, fluid,
                blockLightData, skyLightData, litByBlock, litBySky);
    }

    /**
     * Parse a section NBT tag into a headless {@link ParsedSection} (the OBJECT path —
     * the transcoder's permanent fallback rung and the {@code useNbtTranscode=false}
     * rollback). Returns null if the section has no block states or only air (and no
     * block light).
     */
    private static ParsedSection parseSection(
            CompoundTag sectionTag, int sectionY,
            Codec<PalettedContainer<BlockState>> blockStateCodec,
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            Registry<Biome> factory, // 1.21.1 line: the seam parameter is the biome registry
            byte[] blockLightData, byte[] skyLightData, int[] unparseable,
            String[] usedHardError) {

        boolean hasBlockStates = sectionTag.contains("block_states", Tag.TAG_COMPOUND);
        PalettedContainer<BlockState> blockStates;
        boolean knownAir = false;
        if (!hasBlockStates) {
            // Vanilla's light-only cap entries (heightmap+1) carry SkyLight but no
            // block_states — exactly the boundary layers the fix serves.
            blockStates = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                    Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            knownAir = true;
        } else {
            var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE,
                    sectionTag.getCompound("block_states"));
            // Vanilla-lenient (resultOrPartial) — see the Fabric twin: a recoverable
            // palette error (pre-DFU block rename) substitutes air and KEEPS the section;
            // only a no-partial parse counts unparseable.
            boolean[] partial = {false};
            blockStates = blockStatesResult.resultOrPartial(err -> {
                partial[0] = true;
                long released = PARSE_WARN_THROTTLE.recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (released > 0) {
                    String suffix = released > 1 ? " (+" + (released - 1) + " more suppressed)" : "";
                    LSSLogger.warn("Section block_states parse error (Y=" + sectionY + "): "
                            + err + suffix);
                }
            }).orElse(null);
            if (blockStates == null) {
                unparseable[0]++;
                return null;
            }
            // A partial: some entry substituted air. Record whether cells use a failed
            // entry BEFORE the empty-section return below (an all-failed section is
            // exactly the one that return drops).
            if (partial[0] && usedHardError[0] == null) {
                usedHardError[0] = firstUsedHardError(sectionTag);
            }
        }

        PalettedContainerRO<Holder<Biome>> biomes = null;
        if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes"));
            biomes = biomesResult.result().orElse(null);
        }
        if (biomes == null) {
            biomes = new PalettedContainer<>(factory.asHolderIdMap(),
                    factory.getHolderOrThrow(Biomes.PLAINS),
                    PalettedContainer.Strategy.SECTION_BIOMES);
        }

        int counts = knownAir ? 0 : countNonEmptyAndFluid(blockStates);
        int nonEmpty = counts >>> 16, fluid = counts & 0xFFFF;

        boolean litByBlock = blockLightData.length == 2048 && hasNonZeroNibble(blockLightData);
        boolean litBySky = skyLightData.length == 2048 && hasNonZeroNibble(skyLightData);

        if (nonEmpty == 0 && !litByBlock && !litBySky) {
            return null;
        }

        return new ParsedSection(sectionY, null, blockStates, biomes, nonEmpty, fluid,
                blockLightData, skyLightData, litByBlock, litBySky);
    }

    /**
     * True when no section entry of the chunk carries a sky-light layer with a non-zero
     * nibble: the chunk is saved without sky light (lightless-chunks plan, fold item 7 —
     * a diagnostics fact for the reader's notes; it never changes a byte). Every entry
     * counts, whatever its Y: vanilla saves a fully lit light-only entry above the block
     * range, and an entry the range gate or the content band drops from the wire still
     * proves the chunk was lit. A layer is one the serializer itself would accept (2048
     * bytes). Cheap by construction: a served section that is sky-lit answers from the
     * flag its parse already computed, so a lit chunk reads no array here at all;
     * otherwise the entries are scanned top first with the vectorized zero compare,
     * which stops at the first non-zero byte. Nothing is allocated, copied or written.
     */
    private static boolean savedWithoutSkyLight(java.util.List<ParsedSection> parsed,
                                                ListTag sections) {
        for (var p : parsed) {
            if (p.litBySky()) return false;
        }
        for (int i = sections.size() - 1; i >= 0; i--) {
            if (sections.get(i) instanceof CompoundTag section
                    && section.get("SkyLight") instanceof ByteArrayTag sky) {
                byte[] layer = sky.getAsByteArray();
                if (layer.length == 2048 && hasNonZeroNibble(layer)) return false;
            }
        }
        return true;
    }

    /** True when any parsed section still has a non-air cell (the column has content). */
    private static boolean hasNonAirCell(java.util.List<ParsedSection> parsed) {
        for (var p : parsed) {
            if (p.nonEmptyCount() != 0) return true;
        }
        return false;
    }

    /**
     * The decode message of the first hard-error block palette entry that at least one
     * CELL of this section uses, or null when there is none. Counting cells, not palette
     * entries: a leftover entry no cell points at changes nothing on screen, and must
     * not turn a truly empty column into an undecodable one. Shared by both routes (the
     * transcoder and the object path), reading the raw NBT and the palette memo only, so
     * they agree by construction and the walk is the same text on every Minecraft line.
     * Runs only for a section that already had a hard error (the memo lookups are hits).
     */
    private static String firstUsedHardError(CompoundTag sectionTag) {
        if (!(sectionTag.get("block_states") instanceof CompoundTag bs)
                || !(bs.get("palette") instanceof ListTag palette)) {
            return null;
        }
        int n = palette.size();
        String[] failed = null;
        for (int i = 0; i < n; i++) {
            String error = BlockCodecHolder.ELEMENT.resolve(palette.get(i)).hardError();
            if (error != null) {
                if (failed == null) failed = new String[n];
                failed[i] = error;
            }
        }
        if (failed == null) return null;
        if (n == 1) return failed[0]; // a single-entry palette: every cell uses it
        // The disk index width for n entries (4 bits up to 16 entries, then
        // ceil(log2 n)), read through the ops accessor the container codec itself uses
        // (a long array, or a list of longs).
        int bits = n <= 16 ? 4 : 32 - Integer.numberOfLeadingZeros(n - 1);
        int vpl = 64 / bits;
        var dataTag = bs.get("data");
        long[] data = dataTag == null ? null : NbtOps.INSTANCE.getLongStream(dataTag)
                .result().map(java.util.stream.LongStream::toArray).orElse(null);
        if (data == null || data.length != (4096 + vpl - 1) / vpl) {
            // Belt only: both callers get here for a section whose data the codec (or the
            // transcoder's own length check) accepted. If the cells cannot be read, a
            // failed entry counts as used: the safe side is "do not serve air".
            for (String error : failed) {
                if (error != null) return error;
            }
            return null;
        }
        long mask = (1L << bits) - 1;
        int count = 0;
        for (long cell : data) {
            for (int j = 0; j < vpl; j++) {
                int index = (int) (cell & mask);
                if (index < n && failed[index] != null) return failed[index];
                cell >>>= bits;
                if (++count == 4096) return null;
            }
        }
        return null;
    }

    /**
     * The two wire count headers, packed {@code (nonEmpty << 16) | fluid} — see the Fabric
     * twin (BlockCounter semantics minus the ticking counts; histogram instead of the
     * per-cell recount — but see the 1.21.1-line note in the body: vanilla count()
     * carries the histogram here). The container is thread-confined (freshly parsed
     * on this reader thread).
     */
    static int countNonEmptyAndFluid(PalettedContainer<BlockState> states) {
        // 1.21.1 line: PalettedContainer.Data is package-private here (the public-Data
        // Moonrise patch is a later line), so the raw palette/storage histogram is not
        // reachable — count through vanilla's own count() for EVERY tier (it runs the
        // same per-palette-id histogram inside the class; the old fast path was a
        // same-complexity de-virtualization, so this is a small constant-factor
        // concession, not an algorithmic one).
        int[] acc = new int[2];
        states.count((state, c) -> {
            if (!state.isAir()) {
                acc[0] += c;
                if (!state.getFluidState().isEmpty()) acc[1] += c;
            }
        });
        return (acc[0] << 16) | acc[1];
    }

    private static boolean hasNonZeroNibble(byte[] light) {
        // Intrinsified vectorized mismatch — see the Fabric twin. Callers guarantee 2048.
        return !java.util.Arrays.equals(light, ZERO_NIBBLES);
    }

    // Static (unlike factoryMemo): the block registry is bootstrap-frozen, so the memoized
    // element codec and its cache live for the JVM. Arguments mirror vanilla's
    // ChunkSerializer BLOCK_STATE_CODEC codecRW call exactly (fuzz + goldens pin it);
    // 1.21.1 line: codecRW takes the IdMap + Strategy constant (no top-level Strategy class);
    // the element memo doubles as the transcoder's palette-id resolver.
    private static final class BlockCodecHolder {
        static final PaperMemoizedNbtCodec<BlockState> ELEMENT = new PaperMemoizedNbtCodec<>(
                BlockState.CODEC, 1 << 16, Blocks.AIR.defaultBlockState(),
                state -> PaperMemoizedNbtCodec.packMeta(Block.BLOCK_STATE_REGISTRY.getId(state),
                        state.isAir(), !state.getFluidState().isEmpty()));
        static final Codec<PalettedContainer<BlockState>> CODEC = PalettedContainer.codecRW(
                Block.BLOCK_STATE_REGISTRY,
                ELEMENT,
                PalettedContainer.Strategy.SECTION_STATES,
                Blocks.AIR.defaultBlockState());
        static final int[] AIR_SINGLE =
                {Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())};
    }

    /**
     * Per-RegistryAccess biome-palette resolver for the transcoder — see the Fabric twin:
     * disk names to the ids the biome strategy's global map writes, plus the
     * factory-default (plains) id for the strict-biome collapse. Known names memoize
     * (bounded by the registry); unknown or unparseable names return -1 uncached.
     */
    record BiomeIdResolver(Registry<Biome> registry, IdMap<Holder<Biome>> idMap,
                           int defaultId, ConcurrentHashMap<String, Integer> byName) {
        int idFor(String name) {
            Integer hit = this.byName.get(name);
            if (hit != null) return hit;
            var rl = ResourceLocation.tryParse(name);
            if (rl == null) return -1;
            // 1.21.1 line: the Optional-returning holder lookup is getHolder here.
            var holder = this.registry.getHolder(rl).orElse(null);
            if (holder == null) return -1;
            int id = this.idMap.getId(holder);
            this.byName.put(name, id);
            return id;
        }
    }

    /** The registry-scoped pair the single-slot memo holds: the container factory and
     *  the transcoder's biome resolver share one lifetime (both die with their key). */
    private record RegistryScoped(Registry<Biome> biomeRegistry,
                                  Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                                  BiomeIdResolver biomeResolver,
                                  java.util.function.IntFunction<String> biomeIdentityFor,
                                  java.util.function.ToIntFunction<String> biomeIdFor,
                                  int biomeIdCount) {}

    /** The C2 egress inverse: bare biome identity → this registry's holder id, -1 when
     *  unknown (the translator turns -1 into its pinned loud failure). Built by
     *  inverting the SAME id→identity table the emit uses, so the pair is bijective
     *  by construction on one registry. */
    static java.util.function.ToIntFunction<String> biomeIdLookup(RegistryAccess registryAccess) {
        return scopedFor(registryAccess).biomeIdFor();
    }

    /** The biome id-space size for the translator's DIRECT width (the same idMap the
     *  native containers encode against). */
    static int biomeIdCount(RegistryAccess registryAccess) {
        return scopedFor(registryAccess).biomeIdCount();
    }

    /** The v20 biome identity lookup for this registry access (C1): wire biome ids are
     *  the factory strategy's {@code globalMap} HOLDER ids — the identity table must be
     *  built from that same map, never from bare {@code Registry.getId}. Shared by the
     *  live path ({@code PaperSectionSerializer}) and the transcode path via the memo. */
    static java.util.function.IntFunction<String> biomeIdentityLookup(RegistryAccess registryAccess) {
        return scopedFor(registryAccess).biomeIdentityFor();
    }

    private static java.util.function.IntFunction<String> buildBiomeIdentities(IdMap<Holder<Biome>> idMap) {
        var table = new String[idMap.size()];
        for (int id = 0; id < table.length; id++) {
            var holder = idMap.byId(id);
            var key = holder == null ? null : holder.unwrapKey().orElse(null);
            if (key == null) {
                // C0's fail-loud-at-build posture (review C1-7): a keyless holder would
                // otherwise surface as a per-serve translation failure much later.
                throw new IllegalStateException("biome id " + id + " has no registry key");
            }
            String identity = key.location().toString();
            dev.vox.lss.common.wire.IdentityCodec.validate(identity);
            table[id] = identity;
        }
        return id -> id >= 0 && id < table.length ? table[id] : null;
    }

    /** The C1 produce-path v20 hook (progress-doc decision 2026-08-07): every producer
     *  emits its NATIVE body exactly as before and translates at the return boundary —
     *  ONE corpus-proven encoder, byte-determinism across paths by construction. Since
     *  the C6-gated follow-up, all-transcoded disk columns bypass this via
     *  {@link #emitV20Direct}; this route remains for live/mixed/masked columns. */
    static byte[] toV20(byte[] nativeBody, RegistryAccess registryAccess) {
        return dev.vox.lss.common.wire.NativeToV20Translator.translate(nativeBody,
                PaperIdentityTables::blockIdentityFor,
                biomeIdentityLookup(registryAccess));
    }

    /** The direct transcode-path v20 emit (C6 follow-up) — Fabric's
     *  {@code NbtSectionSerializer.emitV20Direct} twin: the descriptors already carry
     *  global ids in wire order + verbatim disk longs, so the dictionary walk runs on
     *  them directly and the native intermediate disappears. Callers guarantee every
     *  section is transcoded (the assembly's allTranscoded gate). */
    private static byte[] emitV20Direct(java.util.List<ParsedSection> parsed,
                                        RegistryAccess registryAccess) {
        DIRECT_V20_EMITS.incrementAndGet();
        var dict = new dev.vox.lss.common.wire.IdentityDictionary();
        java.util.function.IntFunction<String> blockIdentity = PaperIdentityTables::blockIdentityFor;
        var biomeIdentity = biomeIdentityLookup(registryAccess);
        var sections = new java.util.ArrayList<dev.vox.lss.common.wire.WireSectionCursor.WireSection>(parsed.size());
        for (var p : parsed) {
            var t = p.transcoded();
            sections.add(new dev.vox.lss.common.wire.WireSectionCursor.WireSection(
                    // (byte) cast — see the Fabric twin: the native route's writeByte
                    // truncates out-of-range sectionY; the direct route must match.
                    (byte) p.sectionY(),
                    // Derived (V-2 review MAJOR-2) — the PAPER-family twin of the
                    // fabric direct-route rule: (familyFold, 0) on a 1-short line.
                    dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                            ? p.nonEmptyCount()
                            : dev.vox.lss.common.wire.NativeSectionShape
                                    .foldedCountPaperFamily(p.nonEmptyCount(), p.fluidCount()),
                    dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                            ? p.fluidCount() : 0,
                    dev.vox.lss.common.wire.NativeToV20Translator.convertIndexed(
                            t.blockBits(), t.blockIds(), t.blockData(), true, dict, blockIdentity),
                    dev.vox.lss.common.wire.NativeToV20Translator.convertIndexed(
                            t.biomeBits(), t.biomeIds(), t.biomeData(), false, dict, biomeIdentity),
                    p.litByBlock() ? p.blockLight() : null,
                    p.litBySky() ? p.skyLight() : null));
        }
        return dev.vox.lss.common.wire.WireSectionCursor.emit(
                new dev.vox.lss.common.wire.WireSectionCursor.WireColumn(dict.entries(), sections),
                dev.vox.lss.common.wire.WireSectionCursor.Layout.V20);
    }

    /** The C2 egress inverse of {@link #toV20} (XVER §4.2) — Fabric's
     *  {@code NbtSectionSerializer.fromV20} twin: v20 body → native section layout
     *  against this server's OWN registries, exact and lossless same-version. Throws
     *  {@code WireFormatException} on any malformed body or unresolvable identity. */
    static byte[] fromV20(byte[] v20Body, RegistryAccess registryAccess) {
        var blockIds = PaperIdentityTables.blockIdsByIdentity();
        return dev.vox.lss.common.wire.V20ToNativeTranslator.translate(v20Body,
                identity -> blockIds.getOrDefault(identity, -1),
                biomeIdLookup(registryAccess),
                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size(),
                biomeIdCount(registryAccess));
    }

    // The codec/idMap derivations cost per call — measurable
    // allocation churn when every disk read pays it (review 2026-07-27). The registry access
    // is stable for a server's lifetime; a single-slot memo (atomic pair via one volatile)
    // covers it and survives the odd registry swap in tests. The key is held WEAKLY so a
    // departed world doesn't keep its dynamic registries pinned until the next world load
    // (final review 2026-07-27); the factory dies with its key.
    private static volatile java.util.Map.Entry<java.lang.ref.WeakReference<RegistryAccess>, RegistryScoped> factoryMemo;

    // 1.21.1 line: the seam handle is the biome Registry (no PalettedContainerFactory on
    // this MC); the name is kept so the PaperSectionSerializer call site and its pins
    // stay textually stable across lines.
    static Registry<Biome> factoryFor(RegistryAccess registryAccess) {
        return scopedFor(registryAccess).biomeRegistry();
    }

    private static RegistryScoped scopedFor(RegistryAccess registryAccess) {
        var memo = factoryMemo;
        if (memo != null && memo.getKey().get() == registryAccess) return memo.getValue();
        var biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        var idMap = biomeRegistry.asHolderIdMap();
        // Vanilla's makeBiomeCodec shape (ChunkSerializer), memoized here (1.21.1 line).
        var biomeCodec = PalettedContainer.codecRO(
                idMap, biomeRegistry.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        var identityFor = buildBiomeIdentities(idMap);
        var inverse = new java.util.HashMap<String, Integer>(idMap.size() * 2);
        for (int id = 0; id < idMap.size(); id++) {
            inverse.put(identityFor.apply(id), id);
        }
        var frozenInverse = java.util.Map.copyOf(inverse);
        var scoped = new RegistryScoped(biomeRegistry, biomeCodec, new BiomeIdResolver(
                biomeRegistry, idMap,
                idMap.getId(biomeRegistry.getHolderOrThrow(Biomes.PLAINS)), new ConcurrentHashMap<>()),
                identityFor,
                identity -> frozenInverse.getOrDefault(identity, -1),
                idMap.size());
        factoryMemo = java.util.Map.entry(new java.lang.ref.WeakReference<>(registryAccess), scoped);
        return scoped;
    }
    /** V-2/S1 headerDerivation, PAPER family: the native count header this family's
     *  (Moonrise-patched) vanilla writes, derived from {@code NativeSectionShape} —
     *  26.x: the two-short pair verbatim; a 1-short line writes the family fold
     *  (1.21.11: {@code nonEmpty} alone — Moonrise's recalc, a DIFFERENT fold from
     *  Fabric's on the same line). Both headless write sites route here. */
    private static void writeNativeCountHeader(net.minecraft.network.FriendlyByteBuf buf,
                                               int nonEmpty, int fluid) {
        if (dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2) {
            buf.writeShort(nonEmpty);
            buf.writeShort(fluid);
        } else {
            buf.writeShort(dev.vox.lss.common.wire.NativeSectionShape
                    .foldedCountPaperFamily(nonEmpty, fluid));
        }
    }

}
