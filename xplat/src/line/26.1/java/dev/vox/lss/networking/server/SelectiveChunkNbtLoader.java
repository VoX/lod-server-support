// OVERLAY OF xplat/src/main/java/dev/vox/lss/networking/server/SelectiveChunkNbtLoader.java @ 780da17adf6cf427aecb130bff0c11e8b0aaded73ec079ca63318fe66d0027ff
// 26.1 line overlay (single-branch-consolidation-plan.md §3.2). Refresh the stamp when the shared file changes.
package dev.vox.lss.networking.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagTypes;

import java.io.DataInput;
import java.io.IOException;
import java.util.Set;

/**
 * Root-level selective NBT parse for the Phase 3 pool-side parse site (perf-round plan
 * Phase 4 / R2, {@code useSelectiveNbtParse} default true): whitelisted root keys load
 * through the standard {@code TagType.load}; every other root subtree is
 * {@code TagType.skip}ped — no String/HashMap/tag construction for data LSS never
 * reads. The result is a standard SPARSE {@link CompoundTag}; downstream is unchanged.
 * This eliminates the structure-building slice of the nbt band and its allocation
 * churn; it does NOT eliminate inflate (a skipped subtree still moves its bytes
 * through the inflater). Early-abort-after-whitelist is the recorded out-of-scope
 * stretch follow-up.
 *
 * <p>The root protocol mirrors {@code NbtIo.readUnnamedTag} exactly (26.2 decompiled —
 * the B4 recon entry in the progress doc; 26.1-line note: the R-7 re-port check found
 * the root shape and the serializer-consumed key set identical on 26.1.2, so the
 * whitelist pin stands unchanged): root type byte (non-compound throws
 * vanilla's own message), {@code StringTag.skipString} for the root name, then the
 * compound load loop ({@code readByte type / readUTF key / load-or-skip}) inside
 * {@code pushDepth/popDepth} on the same {@code NbtAccounter.unlimitedHeap()} the full
 * {@code NbtIo.read(DataInput)} path uses (accounting is a no-op there; the structure
 * is kept so a future bounded accounter behaves identically on both paths).
 *
 * <p><b>Leniency, stated honestly (plan):</b> codec-level leniency inside
 * {@code sections} is untouched (those pins stay green). The accepted one-directional
 * divergence: a corrupt NON-section root subtree that full parse would throw on can
 * skip cleanly here — MORE lenient, defensible for LOD (full parse would condemn a
 * column with intact sections to the error→generation ladder over data LSS never
 * reads). Any throw here is handled by the caller's fallback: a full
 * {@code NbtIo.read} over a fresh wrap of the same compressed buffer.
 *
 * <p>{@link #ROOT_KEY_WHITELIST} is source-regex-pinned against the serializer's root
 * accessors ({@code ChannelAccessorContractTest}): a new root-level getter in
 * {@code NbtSectionSerializer} without a whitelist update fails the build.
 * {@code DataVersion} is unread BY DECISION (the R2-1 amendment — gating on it would
 * turn every upgraded-world disc into a generation storm) and must never become a gate.
 */
final class SelectiveChunkNbtLoader {
    private SelectiveChunkNbtLoader() {}

    /** The serializer's complete consumed root-key set (plan-audited: both platforms'
     *  serializers read exactly these; minSectionY/maxSectionY come from the level). */
    static final Set<String> ROOT_KEY_WHITELIST = Set.of("Status", "sections");

    static CompoundTag load(DataInput input) throws IOException {
        var accounter = NbtAccounter.unlimitedHeap();
        byte rootType = input.readByte();
        if (rootType != Tag.TAG_COMPOUND) {
            throw new IOException("Root tag must be a named compound tag");
        }
        StringTag.skipString(input);
        accounter.pushDepth();
        try {
            // Byte accounting mirrored from CompoundTag$1.loadCompound (48 root, 28 +
            // 2/char per key, 36 per new entry) — inert under unlimitedHeap, kept so a
            // future bounded accounter behaves identically on both paths (review B4-5).
            accounter.accountBytes(48L);
            var result = new CompoundTag();
            byte tagType;
            while ((tagType = input.readByte()) != 0) {
                String key = input.readUTF();
                accounter.accountBytes(28L);
                accounter.accountBytes(2L, key.length());
                var type = TagTypes.getType(tagType);
                if (ROOT_KEY_WHITELIST.contains(key)) {
                    if (result.put(key, type.load(input, accounter)) == null) {
                        accounter.accountBytes(36L);
                    }
                } else {
                    type.skip(input, accounter);
                }
            }
            // EXHAUSTION CHECK (review B4-1 — this is what makes the one-directional
            // leniency claim TRUE BY CONSTRUCTION): the raw record's payload is
            // exact-length, so a well-formed parse ends at EOF. The array-type skips
            // (skipBytes(readInt() * width)) silently skip ZERO bytes on a negative or
            // int-overflowing corrupt length where load would throw — a desynced loop
            // can then hit a stray 0 and return a garbage-shaped sparse tag with NO
            // throw. Trailing bytes prove the desync; throwing here routes every such
            // shape into the caller's full-parse fallback.
            if (input instanceof java.io.InputStream stream && stream.read() != -1) {
                throw new IOException("trailing bytes after root compound (skip desync)");
            }
            return result;
        } finally {
            accounter.popDepth();
        }
    }
}
