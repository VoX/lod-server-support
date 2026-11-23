package dev.vox.lss.common.wire;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dictionary validates every identity it is given, and remembers the strings that
 * passed so the next column does not parse them again.
 */
class IdentityDictionaryTest {

    @Test
    void indicesAreFirstSeenAndStable() {
        var dict = new IdentityDictionary();
        assertEquals(0, dict.indexOf("idtest:first[a=1,b=2]"));
        assertEquals(1, dict.indexOf("idtest:second"));
        assertEquals(0, dict.indexOf("idtest:first[a=1,b=2]"));
        assertEquals(2, dict.size());
        assertEquals(List.of("idtest:first[a=1,b=2]", "idtest:second"), dict.entries());
    }

    /** One parse for the first dictionary that sees a string, none for the next. */
    @Test
    void anIdentityThatPassedIsNotParsedAgainByAnotherDictionary() {
        var memory = new IdentityDictionary.Memory(100);
        String identity = "idtest:parsed_once[facing=north,waterlogged=false]";
        assertEquals(0, new IdentityDictionary(memory).indexOf(identity));
        assertEquals(1, memory.parses.get(), "the first sight is parsed");
        for (int i = 0; i < 1000; i++) {
            // an equal string in another object, as a second table or a decoded frame gives
            assertEquals(0, new IdentityDictionary(memory).indexOf(new String(identity)));
        }
        assertEquals(1, memory.parses.get(), "a remembered identity must not be parsed again");
        assertEquals(1, memory.remembered());
    }

    /** The production constructor uses the shared memory: same results, and a string
     *  seen by one dictionary is accepted by the next. */
    @Test
    void theDefaultDictionaryValidatesToo() {
        assertEquals(0, new IdentityDictionary().indexOf("idtest:shared_memory[lit=true]"));
        assertEquals(0, new IdentityDictionary().indexOf("idtest:shared_memory[lit=true]"));
        assertThrows(IllegalArgumentException.class, () -> new IdentityDictionary().indexOf("idtest:Shared"));
    }

    /** A failure is never remembered: the same bad string throws every time, in a new
     *  dictionary and in the same one, and leaves the dictionary as it was. */
    @Test
    void anInvalidIdentityThrowsEveryTime() {
        for (String bad : new String[]{"idtest:Upper", "no_namespace", "idtest:a[b=1,a=2]",
                "idtest:a[]", "idtest:a b", "", "idtest:a[b=1", "a:b:c"}) {
            var dict = new IdentityDictionary();
            assertEquals(0, dict.indexOf("idtest:good"));
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> dict.indexOf(bad), bad);
                assertThrows(IllegalArgumentException.class, () -> new IdentityDictionary().indexOf(bad), bad);
            }
            assertEquals(1, dict.size(), "a refused identity must not take an index");
            assertEquals(1, dict.indexOf("idtest:next"));
        }
    }

    @Test
    void nullIsRefused() {
        var dict = new IdentityDictionary();
        assertThrows(IllegalArgumentException.class, () -> dict.indexOf(null));
        assertThrows(IllegalArgumentException.class, () -> dict.indexOf(null));
        assertEquals(0, dict.size());
    }

    /** Past the bound a new identity is still accepted and still validated, every time. */
    @Test
    void theMemoryIsBoundedAndTheBoundDoesNotChangeTheResult() {
        assertTrue(IdentityDictionary.MAX_REMEMBERED >= 65_536, "room for the vanilla registries several times over");
        var memory = new IdentityDictionary.Memory(16);
        var dict = new IdentityDictionary(memory);
        for (int i = 0; i < 500; i++) {
            assertEquals(i, dict.indexOf("idtest:flood_" + i));
        }
        assertEquals(500, dict.size());
        assertEquals(16, memory.remembered(), "the memory stops at its bound");
        assertEquals(500, memory.parses.get());

        // full: a new valid string works and is parsed each time, a new invalid one throws
        assertEquals(0, new IdentityDictionary(memory).indexOf("idtest:after_the_flood"));
        assertEquals(0, new IdentityDictionary(memory).indexOf("idtest:after_the_flood"));
        assertEquals(502, memory.parses.get());
        assertThrows(IllegalArgumentException.class, () -> new IdentityDictionary(memory).indexOf("idtest:After"));
        // and one that got in before the bound is still remembered
        assertEquals(0, new IdentityDictionary(memory).indexOf("idtest:flood_3"));
        assertEquals(503, memory.parses.get());
    }

    /** Readers and the backfill translate on several threads at once. */
    @Test
    void manyThreadsMayValidateTheSameStringsAtOnce() throws Exception {
        int threads = 8;
        var memory = new IdentityDictionary.Memory(1000);
        var start = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var workers = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            var worker = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 200; round++) {
                        var dict = new IdentityDictionary(memory);
                        for (int i = 0; i < 64; i++) {
                            assertEquals(i, dict.indexOf("idtest:shared_" + i + "[level=" + (i & 7) + "]"));
                        }
                        assertThrows(IllegalArgumentException.class, () -> dict.indexOf("idtest:BAD"));
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (var worker : workers) worker.join(30_000);
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertEquals(64, memory.remembered(), "each valid string once, the invalid one never");
    }
}
