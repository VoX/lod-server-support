package dev.vox.lss.common.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The v20 column dictionary: one entry per distinct canonical identity, indexed in
 * FIRST-SEEN order (deterministic given section walk order — pinned by goldens).
 * Blocks and biomes share one table; context (which palette references an index)
 * disambiguates. A clear column never touches the dictionary, so it emits empty —
 * the {@code dictCount==0 ⇔ sectionCount==0} ghost-clear invariant costs nothing here
 * and is enforced at the emitters.
 *
 * <p>Every identity is {@link IdentityCodec#validate validated} before it is inserted:
 * the dictionary is the last common choke point before strings hit the wire (or a
 * migrated store row), so an out-of-charset identity fails loudly here rather than
 * shipping unparseable.
 *
 * <p>Validity depends on the string alone, so a string that passed once is not parsed
 * again by the next column: the strings that passed are remembered for the life of the
 * process ({@link Memory}). A column has about 45 identities and the same few hundred
 * make up nearly every column, and the parse was a tenth of the server thread's LSS time
 * in a flight profile. A string that fails is never remembered, so it throws every time.
 */
public final class IdentityDictionary {
    /** Bounded, because nothing process-wide may grow without limit: the identities come
     *  from the registry tables, and a heavily modded registry can hold several hundred
     *  thousand block states. Past the bound a new identity is validated every time, as
     *  before; the common ones are seen first and are in. */
    static final int MAX_REMEMBERED = 131_072;

    /** The strings that passed validation. One shared instance in production; its own
     *  class so a test can fill one without touching the rest of the run. */
    static final class Memory {
        private final java.util.Set<String> passed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final int max;
        /** Parses actually run (test instrument). */
        final java.util.concurrent.atomic.AtomicLong parses = new java.util.concurrent.atomic.AtomicLong();

        Memory(int max) {
            this.max = max;
        }

        /** Throws unless {@code identity} is canonical; parses it only if it has not
         *  passed before (or no longer fits in the memory). */
        void check(String identity) {
            if (identity != null && this.passed.contains(identity)) {
                return;
            }
            this.parses.incrementAndGet();
            IdentityCodec.validate(identity);
            // A few entries past the bound under a race are harmless.
            if (this.passed.size() < this.max) {
                this.passed.add(identity);
            }
        }

        int remembered() {
            return this.passed.size();
        }
    }

    private static final Memory SHARED = new Memory(MAX_REMEMBERED);

    private final LinkedHashMap<String, Integer> indices = new LinkedHashMap<>();
    private final Memory memory;

    public IdentityDictionary() {
        this(SHARED);
    }

    /** Test seam: a private memory. */
    IdentityDictionary(Memory memory) {
        this.memory = memory;
    }

    /** Returns the identity's dictionary index, assigning the next one on first sight. */
    public int indexOf(String identity) {
        Integer existing = indices.get(identity);
        if (existing != null) {
            return existing;
        }
        this.memory.check(identity);
        int index = indices.size();
        indices.put(identity, index);
        return index;
    }

    public int size() {
        return indices.size();
    }

    /** Entries in index order (index {@code i} is {@code entries().get(i)}). */
    public List<String> entries() {
        return new ArrayList<>(indices.keySet());
    }
}
