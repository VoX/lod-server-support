package dev.vox.lss.networking.server;

import com.mojang.serialization.Codec;
import dev.vox.lss.common.LSSConstants;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.processing.ColumnDecodeNotes;
import dev.vox.lss.compat.AntiXrayCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import dev.vox.lss.common.wire.WireSectionCursor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads chunk NBT from disk and serializes sections into MC-native wire format.
 * Used by {@link ChunkDiskReader} for async disk reads.
 *
 * <p>Headless serve path (2026-07-29 profile — the recount + palette-decode chains were
 * ~45% of all server CPU during saturated backfill): the UNMASKED path never constructs a
 * {@link LevelChunkSection}. The two wire count headers ({@code nonEmptyBlockCount} and
 * {@code fluidCount} — 26.2 writes both) are computed by {@link #countNonEmptyAndFluid}'s
 * palette histogram instead of the ctor's per-cell hashmap recount, and the containers
 * write themselves ({@code section.write} is exactly the two shorts + the two container
 * writes — pinned by the headless-vs-section fuzz test). Palette-entry block-state decode
 * goes through {@link MemoizedNbtCodec}. The MASKED path still constructs real sections:
 * mask semantics deliberately rely on the counting ctor for the masked headers
 * (fluid-in/fluid-out replacement makes them non-adjustable — see XrayMaskFilter).
 *
 * <p>Round-2 transcode (docs/planning/nbt-transcode-design.md, {@code useNbtTranscode}):
 * the default disk path now skips the container codec entirely — {@link #transcodeSection}
 * reads the palette + long array straight out of the NBT and {@link TranscodedBody} emits
 * the wire bytes directly. This is byte-identical by the unpack-verbatim invariant: for
 * every {@code Configuration.Simple} case, disk and memory agree on bit width and palette
 * order, so the codec-built container the object path serializes holds the disk palette
 * list and long array by reference — the object round-trip contributes nothing to the
 * bytes. Shapes outside that invariant fall back PER SECTION to the object path (the
 * permanent rung, definitionally today's bytes and error semantics): {@code Global}
 * configs (>256-entry block / >8-entry biome palettes), malformed or missing block data,
 * empty palettes, non-long-array data tags, and mask-needing sections (the pre-gate
 * mirrors {@code XrayMaskFilter.needsMasking} exactly — a section it clears is one the
 * filter provably leaves untouched).
 */
final class NbtSectionSerializer {
    private NbtSectionSerializer() {}

    private static final byte[] EMPTY = new byte[0];
    private static final long[] EMPTY_LONGS = new long[0];
    private static final byte[] ZERO_NIBBLES = new byte[2048];

    /**
     * Seam for the region-file NBT read: FOREGROUND ({@code chunkMap.read}) or BACKGROUND
     * priority, per {@code useBackgroundReadPriority}. Mirrors Paper's {@code ChunkNbtRead}.
     */
    @FunctionalInterface
    interface ChunkNbtRead {
        CompletableFuture<Optional<CompoundTag>> read(int cx, int cz);
    }

    /**
     * A chunk's raw region record (Phase 3 split, perf-round plan R1): the still-
     * compressed record payload + its {@code RegionFileVersion} id byte. "payload", not
     * "compressed" — {@code VERSION_NONE} (id 3) payloads are uncompressed. A plain
     * value object by design: no fd, no stream ownership, no close protocol — nothing
     * for a timed-out {@code future.get} to leak (a {@code byte[]} is just garbage).
     */
    record RawChunkRecord(byte[] payload, byte version) {}

    /**
     * Seam for the SPLIT background read (Phase 3): the executor fetches the raw record
     * ({@code RegionFileRawRead}); inflate + NBT parse happen on the CALLING pool thread
     * in {@link #parseRawChunk}. Used only by the vanilla-IOWorker background rung —
     * {@link ChunkNbtRead} is deliberately unchanged (the Moonrise rung returns its
     * bridge future directly, and the ladder pins assert tag identity through it).
     */
    @FunctionalInterface
    interface ChunkRawRead {
        CompletableFuture<Optional<RawChunkRecord>> read(int cx, int cz);
    }

    /**
     * Read chunk NBT from disk, verify FULL status, and serialize sections
     * into MC-native wire format. {@code maskEntry} (nullable) is the dimension's x-ray
     * mask, captured by the caller at submit time.
     * Returns the serialized byte array, or null if the chunk is missing/not FULL/empty.
     */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            XrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY,
                                            boolean useNbtTranscode) throws Exception {
        return readAndSerializeSections(read, registryAccess, cx, cz, maskEntry, minSectionY,
                maxSectionY, useNbtTranscode, null);
    }

    /** The {@link ChunkNbtRead} flavor with the reader's {@link ColumnDecodeNotes}
     *  (nullable): null bytes with the notes marked means the column is UNDECODABLE (see
     *  {@link #serializeChunkNbt(CompoundTag, RegistryAccess, XrayMaskManager.MaskEntry,
     *  int, int, boolean, ColumnDecodeNotes)}), never a plain miss. */
    static byte[] readAndSerializeSections(ChunkNbtRead read, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            XrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY,
                                            boolean useNbtTranscode,
                                            ColumnDecodeNotes notes) throws Exception {
        var future = read.read(cx, cz);
        var optionalTag = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (optionalTag.isEmpty()) return null;
        var chunkNbt = optionalTag.get();
        // The shim scope covers the codec parse AND the section writes (both carry AntiXray
        // mixin injections); it deliberately excludes the blocking read above.
        return AntiXrayCompat.callSerializing(
                () -> serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY,
                        maxSectionY, useNbtTranscode, notes));
    }

    /**
     * Split-read flavor (Phase 3): the timeout bounds the EXECUTOR fetch only; the
     * inflate + parse below run on THIS pool thread as bounded CPU work over a private
     * in-memory buffer — which is the whole point of the split (the IOWorker used to
     * carry pread + inflate + full NBT parse for every LOD read). Carries the Phase 4
     * selective-parse flag with NO default-true convenience overload BY DESIGN (review
     * B4-8: a defaulted flag at a future call site would silently pin selective ON and
     * kill the rollback).
     */
    static byte[] readAndSerializeSections(ChunkRawRead rawRead, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            XrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY,
                                            boolean useNbtTranscode,
                                            boolean useSelectiveNbtParse) throws Exception {
        return readAndSerializeSections(rawRead, registryAccess, cx, cz, maskEntry, minSectionY,
                maxSectionY, useNbtTranscode, useSelectiveNbtParse, null);
    }

    /** The split-read flavor with the reader's {@link ColumnDecodeNotes} (nullable) —
     *  same contract as the {@link ChunkNbtRead} notes flavor above. */
    static byte[] readAndSerializeSections(ChunkRawRead rawRead, RegistryAccess registryAccess,
                                            int cx, int cz,
                                            XrayMaskManager.MaskEntry maskEntry,
                                            int minSectionY, int maxSectionY,
                                            boolean useNbtTranscode,
                                            boolean useSelectiveNbtParse,
                                            ColumnDecodeNotes notes) throws Exception {
        var future = rawRead.read(cx, cz);
        var optionalRecord = future.get(LSSConstants.DISK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var chunkNbt = parseRawChunk(optionalRecord, cx, cz, useSelectiveNbtParse);
        if (chunkNbt == null) return null;
        // Pool-side NbtIo.read stays OUTSIDE the AntiXray shim, exactly like the blocking
        // read in the ChunkNbtRead flavor above: the shim covers the codec parse and the
        // section writes (the AntiXray injection points); the raw inflate/parse has none.
        return AntiXrayCompat.callSerializing(
                () -> serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY,
                        maxSectionY, useNbtTranscode, notes));
    }

    /**
     * Pool-side reconstruction of vanilla's {@code createChunkInputStream} — ALL THREE
     * branches (26.2 bytecode, decompiled shape in the B3 recon entry), not just the
     * happy path: (a) {@code RegionFileVersion.fromId} returns NULL for unknown ids —
     * a naive {@code wrap} would NPE; unknown resolves authoritative not-found,
     * matching vanilla; (b) {@code VERSION_CUSTOM} (id 127) logs and resolves
     * not-found (vanilla reads the UTF id for its message — mirrored); (c) a valid id
     * wraps — and {@code wrap} includes vanilla's {@code FastBufferedInputStream}
     * layer, so valid-branch equivalence holds by construction. Then the same
     * {@code NbtIo.read} vanilla's {@code RegionFileStorage.read} calls.
     *
     * <p>Null = not servable (authoritative not-found). Package-private for the
     * injected-value unit pins (the raw FETCH itself is gametest-covered).
     */
    static CompoundTag parseRawChunk(Optional<RawChunkRecord> record, int cx, int cz,
                                     boolean useSelectiveNbtParse) throws java.io.IOException {
        if (record.isEmpty()) return null;
        var rec = record.get();
        var version = net.minecraft.world.level.chunk.storage.RegionFileVersion.fromId(rec.version());
        if (version == net.minecraft.world.level.chunk.storage.RegionFileVersion.VERSION_CUSTOM) {
            String id = "<unreadable>";
            try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(rec.payload()))) {
                id = in.readUTF();
            } catch (Exception ignored) {
                // the id is for the log only; an unreadable one changes nothing
            }
            warnRawParse("custom-compression chunk (id " + id + ") at [" + cx + "," + cz + "]");
            return null;
        }
        if (version == null) {
            warnRawParse("unknown region stream version " + rec.version()
                    + " at [" + cx + "," + cz + "]");
            return null;
        }
        // Phase 4 (R2): selective root-whitelist parse first; ANY throw falls back to
        // the full parse over a FRESH wrap of the same compressed buffer — free under
        // the raw-record design (a byte[] re-wraps at zero IO cost), and the fallback
        // keeps the documented leniency divergence one-directional.
        if (useSelectiveNbtParse) {
            try (var in = new java.io.DataInputStream(
                    version.wrap(new java.io.ByteArrayInputStream(rec.payload())))) {
                return SelectiveChunkNbtLoader.load(in);
            } catch (Exception e) {
                // NOT a not-found resolution — its own throttle and counter (reviews
                // B4-3/B4-4: sharing the raw-parse throttle would let a steady fallback
                // stream silence version-corruption warns, and the counter is the
                // divergence-rate instrument the diag line surfaces as sel_fallbacks=).
                SELECTIVE_FALLBACKS.incrementAndGet();
                long released = SELECTIVE_FALLBACK_WARN_THROTTLE
                        .recordAndTryAcquire(System.nanoTime() / 1_000_000);
                if (released > 0) {
                    dev.vox.lss.common.LSSLogger.warn("Selective chunk parse failed at ["
                            + cx + "," + cz + "] (" + e + ") — retried with full parse"
                            + (released > 1 ? " (+" + (released - 1) + " more)" : ""));
                }
            }
        }
        try (var in = new java.io.DataInputStream(
                version.wrap(new java.io.ByteArrayInputStream(rec.payload())))) {
            return net.minecraft.nbt.NbtIo.read(in);
        }
    }

    /** Selective-parse fallback occurrences (review B4-3 — surfaced on the reader's
     *  read_path diag line as {@code sel_fallbacks=}; static is fine, one production
     *  reader per server and the count is a rate instrument, not per-reader state). */
    static final java.util.concurrent.atomic.AtomicLong SELECTIVE_FALLBACKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final dev.vox.lss.common.LogThrottle SELECTIVE_FALLBACK_WARN_THROTTLE =
            new dev.vox.lss.common.LogThrottle(60_000);

    // Dedicated throttle (review C3): sharing PARSE_WARN_THROTTLE with the section
    // block_states warns would let an upgraded world's continuous rename warns hold the
    // window and completely silence version-corruption warns — unrelated conditions,
    // separate windows.
    private static final dev.vox.lss.common.LogThrottle RAW_PARSE_WARN_THROTTLE =
            new dev.vox.lss.common.LogThrottle(60_000);

    private static void warnRawParse(String detail) {
        long released = RAW_PARSE_WARN_THROTTLE.recordAndTryAcquire(System.nanoTime() / 1_000_000);
        if (released > 0) {
            dev.vox.lss.common.LSSLogger.warn("Raw chunk parse: " + detail
                    + " — resolved as not-found"
                    + (released > 1 ? " (+" + (released - 1) + " more)" : ""));
        }
    }

    /** Unmasked flavor — the shape the pre-masking tests and corpus pin. */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess) {
        return serializeChunkNbt(chunkNbt, registryAccess, null);
    }

    /** Range-free flavor (tests + corpus — the committed goldens serialize out-of-world
     *  Y values like -128 and must keep doing so; only the production path gates). */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    XrayMaskManager.MaskEntry maskEntry) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry,
                Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** Production-default flavor: transcode ON (the {@code useNbtTranscode} default). */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    XrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY) {
        return serializeChunkNbt(chunkNbt, registryAccess, maskEntry, minSectionY, maxSectionY, true);
    }

    // Unparseable-section warns are throttled: on the mainstream trigger (an upgraded
    // world's pre-DFU chunks) every affected column re-reads at ~1 Hz until its
    // generation ticket serves it — unthrottled, that is a #32-class console flood.
    private static final dev.vox.lss.common.LogThrottle PARSE_WARN_THROTTLE =
            new dev.vox.lss.common.LogThrottle(60_000);

    /** A parsed section, headless: either a wire-ready {@code transcoded} body OR the
     *  object path's containers (exactly one is set), plus the two wire count headers and
     *  light. {@code litByBlock}/{@code litBySky} are the all-zero scans computed exactly
     *  once (the wire's "absent means all-zero" rule). */
    record ParsedSection(int sectionY,
                         TranscodedBody transcoded,
                         PalettedContainer<BlockState> states,
                         PalettedContainerRO<Holder<Biome>> biomes,
                         int nonEmptyCount, int fluidCount,
                         byte[] blockLight, byte[] skyLight,
                         boolean litByBlock, boolean litBySky) {}

    /**
     * A wire-ready transcoded section body: both containers as raw descriptors — palette
     * GLOBAL ids in disk-list order plus the disk long array by reference. {@code write}
     * emits exactly {@code PalettedContainer$Data.write}'s shape (bits byte; single = one
     * varint id, linear/hashmap = varint count + ids in list order, duplicates included;
     * raw big-endian longs, NO length prefix), which the unpack-verbatim invariant makes
     * byte-identical to parsing and re-serializing the container.
     */
    record TranscodedBody(int blockBits, int[] blockIds, long[] blockData,
                          int biomeBits, int[] biomeIds, long[] biomeData) {

        /** Both containers' wire size (the section's two count shorts are the caller's). */
        int serializedSize() {
            return containerSize(this.blockBits, this.blockIds, this.blockData)
                    + containerSize(this.biomeBits, this.biomeIds, this.biomeData);
        }

        private static int containerSize(int bits, int[] ids, long[] data) {
            int size = 1 + data.length * 8;
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
            for (long l : data) {
                buf.writeLong(l);
            }
        }
    }

    /** Direct-emit routing telemetry (C6 follow-up): bumped once per column served
     *  through {@link #emitV20Direct}. Outputs are byte-identical to the translate
     *  route, so WITHOUT this counter a regression silently re-routing everything
     *  through the native intermediate would pass every golden — tests pin the
     *  routing, and a benchmark validity check can assert the fast path engaged. */
    static final AtomicLong DIRECT_V20_EMITS = new AtomicLong();

    /** Sizing-exactness telemetry: bumped when the exact pre-size mismatched the written
     *  bytes and the safe copy fallback ran (never wrong bytes, one warn). Tests pin 0. */
    static final AtomicLong SIZE_MISMATCH_FALLBACKS = new AtomicLong();
    private static final AtomicBoolean SIZE_MISMATCH_WARNED = new AtomicBoolean();

    /**
     * Serialize a chunk's NBT (as read from a region file) into MC-native wire format.
     * Returns {@code null} if the chunk is not FULL, has no sections, or contains a
     * truly-unparseable section (an authoritative miss — serving a column with a silently
     * missing section would stamp a persistent hole no re-declaration heals; the miss
     * escalates to a generation ticket that loads the chunk through the REAL DataFixer
     * pipeline instead). Returns an empty array if every section is empty. Sections
     * outside {@code [minSectionY, maxSectionY]} are dropped before parsing: vanilla
     * saves light-only entries one section beyond the block range, the live path can
     * never emit them (disk/live parity), and their out-of-world sectionY reaches
     * consumers unchecked. Package-visible for testing.
     */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    XrayMaskManager.MaskEntry maskEntry,
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
     * error, and no in-range section has a non-air cell left. Before this rule such a
     * column serialized as all-air (or as lit air sections): stamped, stored, and sent as
     * a clear over the client's cached terrain. A column with ANY decoded block keeps the
     * vanilla leniency exactly (unknown entries substitute air in place).
     */
    static byte[] serializeChunkNbt(CompoundTag chunkNbt, RegistryAccess registryAccess,
                                    XrayMaskManager.MaskEntry maskEntry,
                                    int minSectionY, int maxSectionY, boolean useNbtTranscode,
                                    ColumnDecodeNotes notes) {
        var statusStr = chunkNbt.getStringOr("Status", null);
        if (statusStr == null || ChunkStatus.byName(statusStr) != ChunkStatus.FULL) return null;

        var scoped = scopedFor(registryAccess);
        var factory = scoped.factory();
        // Block-state container codec is LSS-built: vanilla's exact codecRW arguments
        // (fuzz + goldens pin equivalence) with only the ELEMENT codec swapped for the
        // palette-entry memo. Biomes keep the factory codec (tiny palettes, dynamic
        // registry — not worth the per-registry memo lifecycle).
        var blockStateCodec = BlockCodecHolder.CODEC;
        var biomeCodec = factory.biomeContainerCodec();

        var sectionsTag = chunkNbt.getList("sections");
        if (sectionsTag.isEmpty()) return null;

        var sectionsList = sectionsTag.orElseThrow();

        // First pass: parse sections and check if any are non-empty
        var parsed = new java.util.ArrayList<ParsedSection>(sectionsList.size());

        int[] unparseable = {0};
        // The first hard-error palette entry that a CELL uses, across the in-range
        // sections (null = none): the undecodable-column gate below reads it.
        String[] usedHardError = {null};
        boolean[] fallback = {false};
        for (var sectionElement : sectionsList) {
            var sectionTag = (CompoundTag) sectionElement;
            int sectionY = sectionTag.getIntOr("Y", Integer.MIN_VALUE);
            if (sectionY == Integer.MIN_VALUE) continue;
            // Range gate BEFORE parse: an out-of-range garbage entry must not count as
            // unparseable and condemn a column it would have been dropped from anyway.
            if (sectionY < minSectionY || sectionY > maxSectionY) continue;

            byte[] blockLightData = sectionTag.getByteArray("BlockLight").orElse(EMPTY);
            byte[] skyLightData = sectionTag.getByteArray("SkyLight").orElse(EMPTY);
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
        if (usedHardError[0] != null && !hasNonAirCell(parsed)) {
            // Undecodable column: cells point at palette entries that failed to decode,
            // and nothing else in the column decoded to a block. Everything LSS could say
            // about it would be substituted air, so it says nothing: null, and the notes
            // (when the caller passed them) carry the reason. Light does not matter (a
            // lit column of substituted air is the same lie). Checked BEFORE the
            // unparseable rung on purpose: that rung's miss may escalate to generation,
            // and a chunk whose blocks this server does not know must never be loaded by
            // LSS (vanilla turns them into air on load and may save the chunk).
            if (notes != null) notes.markUndecodable(usedHardError[0]);
            return null;
        }
        if (unparseable[0] > 0) {
            // Authoritative miss (null): a truly-unparseable section (no partial value —
            // real corruption, not a recoverable rename) makes the whole column
            // unservable. Serving without the section would stamp a persistent invisible
            // hole; null rides the existing not-found ladder instead (memoized, and on
            // gen-enabled servers the generation ticket loads the chunk through the real
            // DataFixer pipeline and serves it correctly).
            return null;
        }

        // Boundary-light band (2026-07-27, black-boundary-faces fix): SKY-lit air sections
        // are served only within one section of the column's CONTENT band — vanilla's own
        // stored-light coverage (heightmap+1), matching the live path exactly (disk/live
        // byte parity) — so a void/cleared column's ambient sky can never turn a
        // zero-section CLEAR into a data column. BLOCK-lit air keeps its long-standing
        // unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var p : parsed) {
            if (p.nonEmptyCount() != 0) {
                minContent = Math.min(minContent, p.sectionY());
                maxContent = Math.max(maxContent, p.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        parsed.removeIf(p -> p.nonEmptyCount() == 0
                && !p.litByBlock()
                && (noContent || p.sectionY() < lo || p.sectionY() > hi));

        if (parsed.isEmpty()) return new byte[0];

        // Masked path: parsed sections are throwaway — construct real sections and mask in
        // place, inside the same choke point the live path masks in, so disk and live
        // serves stay byte-identical. Mask semantics rely on the counting ctor for the
        // masked headers (they can only be recomputed, never adjusted — the fluid gotcha
        // cuts both ways), so this branch keeps the pre-headless shape wholesale.
        // Transcoded sections are SKIPPED here by construction: the transcode pre-gate
        // routed every section the filter would touch through the object path, so a
        // transcoded entry is one mask() provably returns unchanged. The counter
        // attributes to whatever manager is current at COMPLETION time (a read
        // straddling a service restart credits the successor) — diag-only cosmetics;
        // the mask itself always comes from the immutable submit-time entry.
        LevelChunkSection[] maskedSections = null;
        if (maskEntry != null) {
            maskedSections = new LevelChunkSection[parsed.size()];
            int[] replacedCells = new int[1];
            for (int i = 0; i < parsed.size(); i++) {
                var p = parsed.get(i);
                if (p.transcoded() != null) continue;
                var section = dev.vox.lss.platform.SectionConstruction.fromContainers(p.states(), p.biomes(), factory);
                var masked = XrayMaskFilter.mask(section, p.sectionY(),
                        maskEntry.mask(), maskEntry.kind(), factory, replacedCells);
                maskedSections[i] = masked;
                // Count only when cells were actually hidden: a stale-palette rebuild
                // (mined-out section still listing its ore) swaps the section for the
                // palette prune but masks nothing.
                if (masked != section && replacedCells[0] > 0) {
                    var manager = XrayMaskManager.current();
                    if (manager != null) manager.countMaskedSection();
                }
            }
        }

        // Direct v20 emit (the C6-triggered follow-up, 2026-08-08): when EVERY surviving
        // section is transcoded, the descriptors already hold global ids in wire order
        // plus the verbatim disk longs — build the v20 column straight from them instead
        // of emitting native bytes and re-parsing the whole column through the
        // translator (the measured +18.5% serve cost of translate-at-producer).
        // Byte-identical by the translator's indexed rule (the transcode pre-gate
        // guarantees indexed shapes; the transcode-vs-object fuzz now compares this
        // path against the translate route for free). Any object-path or mask-needing
        // section keeps the native-emit + translate route wholesale.
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

        // Second pass: serialize to wire format, into an EXACTLY-sized buffer (the old
        // 1 KB/section estimate was 4-8x short, so netty regrew and recopied the payload
        // 2-3 times per column). A correct size means zero growth and the backing array
        // IS the payload (stolen below — no copy-out); a mismatch falls back to the copy,
        // never to wrong bytes.
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
                    // the count header, then the two containers.
                    writeNativeCountHeader(buf, p.nonEmptyCount(), p.fluidCount());
                    p.states().write(buf);
                    p.biomes().write(buf);
                }

                // All-zero layers are skipped to match SectionSerializer exactly: the live path
                // omits them ("absent" means all-zero on the wire), and vanilla saves the light
                // engine's allocated-but-zeroed arrays (e.g. after a light source is removed), so
                // shipping them would make disk serves byte-diverge from live serves of the same
                // content — breaking the up-to-date economy and DirtyContentFilter seeding.
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
     * The transcode descriptor pass: palette global ids via the memo, counts via a raw
     * bit-storage histogram, light presence — no containers, no codecs. Returns a
     * transcoded {@link ParsedSection}; or null with {@code fallback[0]} SET for shapes
     * the transcoder does not own ({@code Global} configs, empty palettes,
     * missing/mistyped/mis-sized block data, non-long-array data tags, mask-needing
     * sections) — the caller re-parses through the object path, which is definitionally
     * today's bytes and error semantics; or null with {@code fallback[0]} clear when the
     * section is dropped by the same air/no-light gate the object path applies.
     */
    private static ParsedSection transcodeSection(CompoundTag sectionTag, int sectionY,
            BiomeIdResolver biomeResolver, XrayMaskManager.MaskEntry maskEntry,
            byte[] blockLightData, byte[] skyLightData, boolean[] fallback,
            String[] usedHardError) {

        // ---- blocks: palette ids + the two count headers off the raw long array ----
        int blockBits = 0;
        int[] blockIds;
        long[] blockData = EMPTY_LONGS;
        int nonEmpty = 0, fluid = 0;
        int hardErrors = 0;
        String firstHardError = null;

        var blockStatesOpt = sectionTag.getCompound("block_states");
        if (blockStatesOpt.isEmpty()) {
            // Vanilla's light-only cap entries (heightmap+1) carry SkyLight but no
            // block_states: an all-air single container, counts 0.
            blockIds = BlockCodecHolder.AIR_SINGLE;
        } else {
            var bs = blockStatesOpt.get();
            var paletteOpt = bs.getList("palette");
            if (paletteOpt.isEmpty()) {
                fallback[0] = true;   // missing/mistyped palette — object path (condemns)
                return null;
            }
            var palette = paletteOpt.get();
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
                long[] data = bs.getLongArray("data").orElse(null);
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
        var biomesOpt = sectionTag.getCompound("biomes");
        if (biomesOpt.isPresent()) {
            var bt = biomesOpt.get();
            var palette = bt.getList("palette").orElse(null);
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
                            ? biomeResolver.idFor(st.value()) : -1;
                    if (id < 0) clean = false;
                    else ids[i] = id;
                }
                if (clean) {
                    if (n == 1) {
                        biomeIds = ids;   // ZeroBitStorage: data ignored
                    } else {
                        int bits = 32 - Integer.numberOfLeadingZeros(n - 1);
                        if (bt.get("data") != null && bt.getLongArray("data").isEmpty()) {
                            fallback[0] = true;   // present but not a long array
                            return null;
                        }
                        long[] data = bt.getLongArray("data").orElse(null);
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
            PalettedContainerFactory factory,
            byte[] blockLightData, byte[] skyLightData, int[] unparseable,
            String[] usedHardError) {

        var blockStatesOpt = sectionTag.getCompound("block_states");
        PalettedContainer<BlockState> blockStates;
        boolean knownAir = false;
        if (blockStatesOpt.isEmpty()) {
            // Vanilla's light-only cap entries (heightmap+1) carry SkyLight but no
            // block_states — exactly the boundary layers the fix serves. Build an all-air
            // container for them; the air/light gate below decides whether it ships.
            blockStates = factory.createForBlockStates();
            knownAir = true;
        } else {
            var blockStatesResult = blockStateCodec.parse(NbtOps.INSTANCE, blockStatesOpt.get());
            // Vanilla-lenient (resultOrPartial): a recoverable palette error — e.g. a
            // pre-DataFixer block rename in an upgraded world's unvisited chunk (this
            // path reads RAW region NBT, no DFU runs) — substitutes the container
            // default (air) for the unknown entry and KEEPS the section, the same
            // container leniency vanilla applies (vanilla's own load runs the DataFixer
            // first, so it would see the renamed block). The strict result() used to drop the whole section
            // silently, and an all-drop column was served as an authoritative 0-section
            // CLEAR that wiped the client's correct cached LOD.
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
                // No partial either — true corruption, not a rename. Counted: the caller
                // resolves the whole column as an authoritative miss.
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
        var optBiomes = sectionTag.getCompound("biomes");
        if (optBiomes.isPresent()) {
            var biomesResult = biomeCodec.parse(NbtOps.INSTANCE, optBiomes.get());
            biomes = biomesResult.result().orElse(null);
        }
        if (biomes == null) {
            biomes = factory.createForBiomes();
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
     * The two wire count headers, packed {@code (nonEmpty << 16) | fluid} — exactly
     * {@code LevelChunkSection.recalcBlockCounts}' BlockCounter minus the ticking counts
     * the wire never carries: per distinct state, a non-air state adds its cells to
     * nonEmpty, and (inside that branch) a non-empty fluid state adds them to fluid.
     * Replaces the ctor's per-cell {@code Int2IntOpenHashMap} recount with an
     * {@code int[palette.getSize()]} histogram (2026-07-29 profile: the recount chain was
     * ~22% of all server CPU). The container is thread-confined (freshly parsed on this
     * reader thread), so the raw {@code data} read is safe.
     */
    static int countNonEmptyAndFluid(PalettedContainer<BlockState> states) {
        var data = states.data; // access-widened; public (Moonrise) on the Paper twin
        var palette = data.palette();
        var storage = data.storage();
        int n = palette.getSize();
        int nonEmpty = 0, fluid = 0;
        if (n == 1) {
            var s = palette.valueFor(0);
            if (!s.isAir()) {
                int c = storage.getSize();
                nonEmpty = c;
                if (!s.getFluidState().isEmpty()) fluid = c;
            }
        } else if (n <= 4096) {
            int[] hist = new int[n];
            storage.getAll(id -> hist[id]++);
            for (int i = 0; i < n; i++) {
                int c = hist[i];
                if (c == 0) continue;
                var s = palette.valueFor(i);
                if (!s.isAir()) {
                    nonEmpty += c;
                    if (!s.getFluidState().isEmpty()) fluid += c;
                }
            }
        } else {
            // Global palette (getSize() is registry-sized): rare on disk — count through
            // vanilla's own path rather than allocating a registry-sized histogram.
            int[] acc = new int[2];
            states.count((state, c) -> {
                if (!state.isAir()) {
                    acc[0] += c;
                    if (!state.getFluidState().isEmpty()) acc[1] += c;
                }
            });
            nonEmpty = acc[0];
            fluid = acc[1];
        }
        return (nonEmpty << 16) | fluid;
    }

    private static boolean hasNonZeroNibble(byte[] light) {
        // Intrinsified vectorized mismatch — ~an order of magnitude over the byte loop.
        // Callers guarantee length == 2048.
        return !java.util.Arrays.equals(light, ZERO_NIBBLES);
    }

    // Static (unlike factoryMemo): the block registry is bootstrap-frozen, so the memoized
    // element codec and its cache live for the JVM. Arguments mirror
    // PalettedContainerFactory.create's codecRW call exactly (fuzz + goldens pin it);
    // the element memo doubles as the transcoder's palette-id resolver.
    private static final class BlockCodecHolder {
        static final MemoizedNbtCodec<BlockState> ELEMENT = new MemoizedNbtCodec<>(
                BlockState.CODEC, 1 << 16, Blocks.AIR.defaultBlockState(),
                state -> MemoizedNbtCodec.packMeta(Block.BLOCK_STATE_REGISTRY.getId(state),
                        state.isAir(), !state.getFluidState().isEmpty()),
                NbtSectionSerializer::upgradeLegacyBlockStateEntry);
        static final Codec<PalettedContainer<BlockState>> CODEC = PalettedContainer.codecRW(
                ELEMENT,
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                Blocks.AIR.defaultBlockState());
        static final int[] AIR_SINGLE =
                {Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())};
    }

    /**
     * Per-RegistryAccess biome-palette resolver for the transcoder: disk names to the
     * ids the biome strategy's global map writes, plus the factory-default (plains) id
     * for the strict-biome collapse. Known names memoize (bounded by the registry);
     * unknown or unparseable names return -1 uncached and collapse the section's biomes
     * to the default container, exactly like the object path's strict {@code result()}.
     */
    record BiomeIdResolver(Registry<Biome> registry, IdMap<Holder<Biome>> idMap,
                           int defaultId, ConcurrentHashMap<String, Integer> byName) {
        int idFor(String name) {
            Integer hit = this.byName.get(name);
            if (hit != null) return hit;
            var rl = Identifier.tryParse(name);
            if (rl == null) return -1;
            var holder = this.registry.get(rl).orElse(null);
            if (holder == null) return -1;
            int id = this.idMap.getId(holder);
            this.byName.put(name, id);
            return id;
        }
    }

    /** The registry-scoped pair the single-slot memo holds: the container factory and
     *  the transcoder's biome resolver share one lifetime (both die with their key). */
    private record RegistryScoped(PalettedContainerFactory factory, BiomeIdResolver biomeResolver,
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
     *  live path ({@code SectionSerializer}) and the transcode path via the same memo. */
    static java.util.function.IntFunction<String> biomeIdentityLookup(RegistryAccess registryAccess) {
        return scopedFor(registryAccess).biomeIdentityFor();
    }

    /** The C1 produce-path v20 hook (progress-doc decision 2026-08-07): every producer
     *  emits its NATIVE body exactly as before and translates at the return boundary —
     *  ONE corpus-proven encoder, byte-determinism across paths by construction. Since
     *  the C6-gated follow-up landed, the all-transcoded disk column bypasses this via
     *  {@link #emitV20Direct} (byte-identical by the translator's indexed rule); this
     *  translate route remains for the live path, mixed/fallback columns, and masking. */
    static byte[] toV20(byte[] nativeBody, RegistryAccess registryAccess) {
        return dev.vox.lss.common.wire.NativeToV20Translator.translate(nativeBody,
                IdentityTables::blockIdentityFor,
                biomeIdentityLookup(registryAccess));
    }

    /** V-2/S1 headerDerivation, FABRIC family: the native count header this family's
     *  vanilla writes, derived from {@code NativeSectionShape} — 26.x: the two-short
     *  pair verbatim (matching {@code LevelChunkSection.write}); a 1-short line writes
     *  the family fold (1.21.11: {@code nonEmpty + fluid}). Both headless write sites
     *  route here so a port edits the DESCRIPTOR, not the sites. */
    private static void writeNativeCountHeader(FriendlyByteBuf buf, int nonEmpty, int fluid) {
        if (dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2) {
            buf.writeShort(nonEmpty);
            buf.writeShort(fluid);
        } else {
            buf.writeShort(dev.vox.lss.common.wire.NativeSectionShape
                    .foldedCountFabricFamily(nonEmpty, fluid));
        }
    }

    /** The direct transcode-path v20 emit (C6 follow-up): every section's descriptor
     *  carries palette GLOBAL ids in wire order + the disk long words verbatim, which
     *  is exactly the translator's indexed input — so the dictionary walk (first-seen,
     *  sections in order, blocks then biomes) runs on the descriptors and the native
     *  intermediate (emit + whole-column re-parse) disappears. Callers guarantee every
     *  section is transcoded (the assembly's allTranscoded gate). */
    private static byte[] emitV20Direct(java.util.List<ParsedSection> parsed,
                                        RegistryAccess registryAccess) {
        DIRECT_V20_EMITS.incrementAndGet();
        var dict = new dev.vox.lss.common.wire.IdentityDictionary();
        java.util.function.IntFunction<String> blockIdentity = IdentityTables::blockIdentityFor;
        var biomeIdentity = biomeIdentityLookup(registryAccess);
        var sections = new java.util.ArrayList<WireSectionCursor.WireSection>(parsed.size());
        for (var p : parsed) {
            var t = p.transcoded();
            sections.add(new WireSectionCursor.WireSection(
                    // (byte) cast: the native route's writeByte TRUNCATES an
                    // out-of-byte-range sectionY (translate then round-trips the
                    // truncated value), so the direct route must truncate identically —
                    // production gates Y to the world range, but the range-free corpus/
                    // tool overload serializes garbage Y and the byte-identity claim
                    // must hold there too (review finding 2).
                    (byte) p.sectionY(),
                    // Derived (V-2 review MAJOR-2): the direct route must stay
                    // byte-identical to the translate route, whose v20 counts pass
                    // through this family's NATIVE header — a 1-short line carries
                    // (familyFold, 0) there, so the direct emit must match.
                    dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                            ? p.nonEmptyCount()
                            : dev.vox.lss.common.wire.NativeSectionShape
                                    .foldedCountFabricFamily(p.nonEmptyCount(), p.fluidCount()),
                    dev.vox.lss.common.wire.NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                            ? p.fluidCount() : 0,
                    dev.vox.lss.common.wire.NativeToV20Translator.convertIndexed(
                            t.blockBits(), t.blockIds(), t.blockData(), true, dict, blockIdentity),
                    dev.vox.lss.common.wire.NativeToV20Translator.convertIndexed(
                            t.biomeBits(), t.biomeIds(), t.biomeData(), false, dict, biomeIdentity),
                    p.litByBlock() ? p.blockLight() : null,
                    p.litBySky() ? p.skyLight() : null));
        }
        return WireSectionCursor.emit(
                new WireSectionCursor.WireColumn(dict.entries(), sections),
                WireSectionCursor.Layout.V20);
    }

    /** The C2 egress inverse of {@link #toV20} (XVER §4.2): v20 body → native section
     *  layout against this server's OWN registries — exact and lossless same-version
     *  (every identity this server emitted exists in its registry; the inverses are the
     *  emit tables inverted, bijective by construction). Throws {@code
     *  WireFormatException} on any malformed body or unresolvable identity — a table
     *  bug must fail loudly, never serve wrong blocks. */
    static byte[] fromV20(byte[] v20Body, RegistryAccess registryAccess) {
        var blockIds = IdentityTables.blockIdsByIdentity();
        return dev.vox.lss.common.wire.V20ToNativeTranslator.translate(v20Body,
                identity -> blockIds.getOrDefault(identity, -1),
                biomeIdLookup(registryAccess),
                Block.BLOCK_STATE_REGISTRY.size(),
                biomeIdCount(registryAccess));
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
            String identity = key.identifier().toString();
            dev.vox.lss.common.wire.IdentityCodec.validate(identity);
            table[id] = identity;
        }
        return id -> id >= 0 && id < table.length ? table[id] : null;
    }

    // PalettedContainerFactory.create builds two strategies + codecs per call — measurable
    // allocation churn when every disk read pays it (review 2026-07-27). The registry access
    // is stable for a server's lifetime; a single-slot memo (atomic pair via one volatile)
    // covers it and survives the odd registry swap in tests. The key is held WEAKLY so an
    // integrated server's departed world doesn't keep its dynamic registries pinned until
    // the next world load (final review 2026-07-27); the factory dies with its key.
    private static volatile java.util.Map.Entry<java.lang.ref.WeakReference<RegistryAccess>, RegistryScoped> factoryMemo;

    static PalettedContainerFactory factoryFor(RegistryAccess registryAccess) {
        return scopedFor(registryAccess).factory();
    }

    private static RegistryScoped scopedFor(RegistryAccess registryAccess) {
        var memo = factoryMemo;
        if (memo != null && memo.getKey().get() == registryAccess) return memo.getValue();
        var factory = PalettedContainerFactory.create(registryAccess);
        var idMap = factory.biomeStrategy().globalMap();
        var identityFor = buildBiomeIdentities(idMap);
        var inverse = new java.util.HashMap<String, Integer>(idMap.size() * 2);
        for (int id = 0; id < idMap.size(); id++) {
            inverse.put(identityFor.apply(id), id);
        }
        var frozenInverse = java.util.Map.copyOf(inverse);
        var scoped = new RegistryScoped(factory, new BiomeIdResolver(
                registryAccess.lookupOrThrow(Registries.BIOME), idMap,
                idMap.getId(factory.defaultBiome()), new ConcurrentHashMap<>()),
                identityFor,
                identity -> frozenInverse.getOrDefault(identity, -1),
                idMap.size());
        factoryMemo = java.util.Map.entry(new java.lang.ref.WeakReference<>(registryAccess), scoped);
        return scoped;
    }

    /** LINE FLAVOR (26.3): {@code BlockStateFieldNamesFix} renamed block-state compounds'
     *  {@code Name}→{@code id} and {@code Properties}→{@code properties}, and the 26.3 codec
     *  REJECTS the old names. Vanilla runs that fix (DFU) when it LOADS a chunk; this path
     *  reads RAW region NBT, so a chunk not yet re-saved by 26.3 — most of an upgraded world —
     *  still carries the old names, and every entry would substitute air. Mirrors the fix per
     *  entry on a COPY (the read tag may be a pending-write snapshot; it is never mutated).
     *  Runs inside the palette memo's MISS path (BlockCodecHolder.ELEMENT), keyed by the raw
     *  entry, so both the transcode and the object path see it and a hit allocates nothing. */
    static net.minecraft.nbt.Tag upgradeLegacyBlockStateEntry(net.minecraft.nbt.Tag entry) {
        if (!(entry instanceof CompoundTag c) || c.contains("id") || !c.contains("Name")) return entry;
        var upgraded = new CompoundTag();
        for (String key : c.keySet()) {
            String renamed = key.equals("Name") ? "id" : key.equals("Properties") ? "properties" : key;
            upgraded.put(renamed, c.get(key));
        }
        return upgraded;
    }
}
