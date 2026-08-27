package dev.vox.lss.networking.client;

import dev.vox.lss.seed.WorldSubKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store's two-axis surfaces (plan §2.3/§2.4): the reset sweep
 * ({@code clearForServers} — bare bucket + anchored {@code .world-*} siblings,
 * case-insensitive member match, per-entry containment) and world-bucket preparation
 * ({@code prepareWorldBucket} — once-ever legacy adoption, the failed-move degrade,
 * the sibling cap). Real filesystem under the resolved cache root, unique component
 * names per test.
 */
class TwoAxisCacheKeyStoreTest {

    private static final AtomicLong UNIQUE = new AtomicLong(System.nanoTime());

    private static String component(String tag) {
        return "twoaxis-" + tag + "-" + UNIQUE.incrementAndGet();
    }

    private static Path bucket(String name) throws IOException {
        Path dir = ColumnCacheStore.cacheRoot().resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("minecraft_overworld.bin"), "stamps");
        return dir;
    }

    private static String seeded(String comp, long seed) {
        return comp + "." + WorldSubKey.format(seed);
    }

    // ---- clearForServers ----

    @Test
    void sweepDeletesTheBareBucketAndEveryAnchoredSibling() throws IOException {
        String comp = component("sweep");
        Path bare = bucket(comp);
        Path sib1 = bucket(seeded(comp, 1L));
        Path sib2 = bucket(seeded(comp, -1L));
        Path bystander = bucket(component("bystander"));

        ColumnCacheStore.clearForServers(List.of(comp));

        assertFalse(Files.exists(bare));
        assertFalse(Files.exists(sib1));
        assertFalse(Files.exists(sib2));
        assertTrue(Files.exists(bystander), "unrelated buckets survive");
        Files.deleteIfExists(bystander.resolve("minecraft_overworld.bin"));
        Files.deleteIfExists(bystander);
    }

    @Test
    void sweepMatchesMembersCaseInsensitively() throws IOException {
        String comp = component("case");
        // A historically capitalized bucket (an old connect spelling) and its sibling.
        String capitalized = comp.toUpperCase(java.util.Locale.ROOT);
        Path oldBare = bucket(capitalized);
        Path oldSib = bucket(seeded(capitalized, 7L));

        ColumnCacheStore.clearForServers(List.of(comp));

        assertFalse(Files.exists(oldBare), "capitalized historical buckets are swept");
        assertFalse(Files.exists(oldSib));
    }

    @Test
    void sweepNeverTouchesAnotherServersSeededBucketViaTheEscapedTail() throws IOException {
        // The §9 hazard: a connect address spelled like victim's seeded bucket. Its
        // component is ESCAPED at key build, so sweeping it must not reach the victim.
        String victim = component("victim");
        String victimSeededName = seeded(victim, 0x0123456789abcdefL);
        Path victimBucket = bucket(victimSeededName);
        String hostileComponent = WorldSubKey.escapeReservedTail(victimSeededName);
        Path hostileBucket = bucket(hostileComponent);
        Path hostileSibling = bucket(seeded(hostileComponent, 5L));

        ColumnCacheStore.clearForServers(List.of(hostileComponent));

        assertTrue(Files.exists(victimBucket), "the victim's seeded bucket survives");
        assertFalse(Files.exists(hostileBucket));
        assertFalse(Files.exists(hostileSibling));
        Files.deleteIfExists(victimBucket.resolve("minecraft_overworld.bin"));
        Files.deleteIfExists(victimBucket);
    }

    @Test
    void oneUndeletableBucketDoesNotStrandTheRestOfTheSweep() throws IOException {
        String comp = component("contained");
        Path bare = bucket(comp);
        Path stubborn = bucket(seeded(comp, 3L));
        // A planted NON-EMPTY subdirectory the flat delete refuses to walk: deleting it
        // throws DirectoryNotEmptyException, the per-entry containment absorbs it, and
        // the sweep continues to the other entries.
        Files.createDirectories(stubborn.resolve("planted"));
        Files.writeString(stubborn.resolve("planted").resolve("foreign.txt"), "keep");

        ColumnCacheStore.clearForServers(List.of(comp));

        assertFalse(Files.exists(bare), "the deletable bucket still went");
        assertTrue(Files.exists(stubborn.resolve("planted").resolve("foreign.txt")),
                "the planted subtree is refused, not walked");
        // Cleanup.
        Files.deleteIfExists(stubborn.resolve("planted").resolve("foreign.txt"));
        Files.deleteIfExists(stubborn.resolve("planted"));
        Files.deleteIfExists(stubborn);
    }

    @Test
    void sweepAcceptsMultipleMembers() throws IOException {
        String a = component("member-a");
        String b = component("member-b");
        Path bareA = bucket(a);
        Path sibB = bucket(seeded(b, 9L));

        ColumnCacheStore.clearForServers(List.of(a, b));

        assertFalse(Files.exists(bareA));
        assertFalse(Files.exists(sibB));
    }

    // ---- prepareWorldBucket ----

    @Test
    void adoptionMovesTheBareBucketOnceEver() throws IOException {
        String comp = component("adopt");
        Path bare = bucket(comp);
        String sub = WorldSubKey.format(42L);

        ColumnCacheStore.prepareWorldBucket(comp, sub, true);

        Path seeded = ColumnCacheStore.cacheRoot().resolve(comp + "." + sub);
        assertFalse(Files.exists(bare), "the bare bucket moved wholesale");
        assertTrue(Files.isRegularFile(seeded.resolve("minecraft_overworld.bin")),
                "the stamps came with it");
        ColumnCacheStore.clearForServers(List.of(comp));
    }

    @Test
    void anExistingSiblingOfAnySeedBlocksAdoptionForever() throws IOException {
        // §9 M-A2: adoption is once EVER per component — a reseed must not swallow
        // lobby/seedless residue accumulated in the re-created bare bucket.
        String comp = component("noreadopt");
        bucket(seeded(comp, 1L)); // the first world's bucket, from the original adoption
        Path bare = bucket(comp); // seedless residue since then

        ColumnCacheStore.prepareWorldBucket(comp, WorldSubKey.format(2L), true);

        assertTrue(Files.exists(bare), "the bare residue stays where it is");
        assertFalse(Files.exists(ColumnCacheStore.cacheRoot()
                        .resolve(comp + "." + WorldSubKey.format(2L))),
                "the new world's bucket starts empty (created on first save, not here)");
        ColumnCacheStore.clearForServers(List.of(comp));
    }

    @Test
    void anAlreadyPreparedSeededBucketIsANoOp() throws IOException {
        String comp = component("prepared");
        Path bare = bucket(comp);
        String sub = WorldSubKey.format(11L);
        Path seeded = bucket(comp + "." + sub);
        Files.writeString(seeded.resolve("marker.bin"), "x");

        ColumnCacheStore.prepareWorldBucket(comp, sub, true);

        assertTrue(Files.exists(bare), "nothing moves once the seeded bucket exists");
        assertTrue(Files.exists(seeded.resolve("marker.bin")));
        ColumnCacheStore.clearForServers(List.of(comp));
    }

    @Test
    void aFailedAdoptionMoveDegradesToAnEmptySeededBucket() throws IOException {
        String comp = component("movefail");
        Path bare = bucket(comp);
        String sub = WorldSubKey.format(13L);
        // Occupy the target name with a FILE: the move fails, the bare bucket stays,
        // and nothing throws out of the IO task (retry next session).
        Path blocker = ColumnCacheStore.cacheRoot().resolve(comp + "." + sub);
        Files.writeString(blocker, "in the way");

        ColumnCacheStore.prepareWorldBucket(comp, sub, true);

        assertTrue(Files.exists(bare), "a failed move leaves the source intact");
        Files.deleteIfExists(blocker);
        ColumnCacheStore.clearForServers(List.of(comp));
    }

    @Test
    void aSymlinkedBucketIsDeletedAsALinkItsTargetSurvives() throws IOException {
        // Panel MAJOR: the deleter must be NON-FOLLOWING — a user relocating their
        // cache via a symlink (or a hostile link planted in the root) must never have
        // the TARGET tree emptied.
        String comp = component("symlink");
        Path target = ColumnCacheStore.cacheRoot().resolve(component("symlink-target"));
        Files.createDirectories(target);
        Files.writeString(target.resolve("minecraft_overworld.bin"), "foreign");
        Path link = ColumnCacheStore.cacheRoot().resolve(comp);
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return; // filesystem without symlink support — nothing to pin here
        }

        ColumnCacheStore.clearForServers(List.of(comp));

        assertFalse(Files.exists(link), "the link itself is removed");
        assertTrue(Files.isRegularFile(target.resolve("minecraft_overworld.bin")),
                "the target tree survives untouched");
        ColumnCacheStore.clearForServers(List.of(target.getFileName().toString()));
    }

    @Test
    void sweepRespectsThePrefixBoundary() throws IOException {
        // play.example.com must not sweep play.example.com.au (or comp.backup): only
        // the exact bucket and the anchored .world-<hex> tail match.
        String comp = component("prefix");
        Path bare = bucket(comp);
        Path neighbour = bucket(comp + ".au");
        Path backup = bucket(comp + ".backup");

        ColumnCacheStore.clearForServers(List.of(comp));

        assertFalse(Files.exists(bare));
        assertTrue(Files.exists(neighbour), "a longer hostname sharing the prefix survives");
        assertTrue(Files.exists(backup), "a non-reserved suffix survives");
        ColumnCacheStore.clearForServers(List.of(comp + ".au", comp + ".backup"));
    }

    @Test
    void aQueuedPriorSaveIsCarriedByTheAdoptionMove() throws IOException {
        // Plan §4.4: "a queued prior-session save cannot resurrect the moved bare
        // bucket" — the single-FIFO IO thread orders the save BEFORE the move, so the
        // stamps land in the bare bucket and travel with it.
        String comp = component("fifo");
        Path bare = ColumnCacheStore.cacheRoot().resolve(comp);
        Files.createDirectories(bare);
        var stamps = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
        stamps.defaultReturnValue(-1L);
        stamps.put(42L, 1234L);
        var dim = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.parse("lss_test:fifo_world"));
        ColumnCacheStore.saveAsync(comp, dim, stamps);              // the prior session's save
        String sub = WorldSubKey.format(77L);
        ColumnCacheStore.prepareWorldBucketAsync(comp, sub, true);  // the next session's prepare
        ColumnCacheStore.flushPendingIo();

        Path seeded = ColumnCacheStore.cacheRoot().resolve(comp + "." + sub);
        assertFalse(Files.exists(bare), "the bare bucket moved — the save did not resurrect it");
        assertEquals(1234L, ColumnCacheStore.load(comp + "." + sub, dim).get(42L),
                "the queued save's stamps travelled with the move");
        ColumnCacheStore.clearForServers(List.of(comp));
        assertFalse(Files.exists(seeded));
    }

    @Test
    void adoptionIsSkippedWhenTheSessionAlreadyUsedTheBareBucket() throws IOException {
        // Panel fix: a seedless lobby leg's residue must not become the game world's
        // stamps — allowAdoption=false leaves the bare bucket where it is.
        String comp = component("residue");
        Path bare = bucket(comp);

        ColumnCacheStore.prepareWorldBucket(comp, WorldSubKey.format(9L), false);

        assertTrue(Files.exists(bare), "the same-session bare residue is not adopted");
        assertFalse(Files.exists(ColumnCacheStore.cacheRoot()
                .resolve(comp + "." + WorldSubKey.format(9L))));
        ColumnCacheStore.clearForServers(List.of(comp));
    }

    @Test
    void theSiblingCapCountsCaseVariantsAsOneNamespace() throws IOException {
        // Panel fix: bucket names preserve raw-address case, and on the
        // case-insensitive filesystems most clients run, two spellings share one
        // namespace — the cap (and the adoption blocker) must see them together.
        String comp = component("capcase");
        String upper = comp.toUpperCase(java.util.Locale.ROOT);
        bucket(seeded(upper, 1L));
        Path bare = bucket(comp);

        ColumnCacheStore.prepareWorldBucket(comp, WorldSubKey.format(2L), true);

        assertTrue(Files.exists(bare),
                "a case-variant sibling blocks adoption exactly like a same-case one");
        ColumnCacheStore.clearForServers(List.of(comp));
    }

    @Test
    void theSiblingCapEvictsTheOldestBucketWithAWarn() throws IOException {
        String comp = component("cap");
        Path oldest = null;
        for (int i = 1; i <= ColumnCacheStore.MAX_WORLD_SIBLINGS; i++) {
            Path sib = bucket(seeded(comp, i));
            Files.setLastModifiedTime(sib, FileTime.fromMillis(1_000_000L * i));
            if (i == 1) oldest = sib;
        }

        ColumnCacheStore.lastCapWarnForTest = null;
        ColumnCacheStore.prepareWorldBucket(comp, WorldSubKey.format(0x99L), true);

        assertFalse(Files.exists(oldest), "the oldest sibling is evicted at the cap");
        assertTrue(ColumnCacheStore.lastCapWarnForTest != null
                        && ColumnCacheStore.lastCapWarnForTest.contains("UNSTABLE"),
                "the seed-unstable WARN is the cap's whole user-facing half — silent "
                        + "eviction is the silent warm-path death the §9 fold names");
        long remaining = 0;
        try (var entries = Files.newDirectoryStream(ColumnCacheStore.cacheRoot())) {
            for (Path e : entries) {
                if (e.getFileName().toString().startsWith(comp + ".")) remaining++;
            }
        }
        assertEquals(ColumnCacheStore.MAX_WORLD_SIBLINGS - 1, remaining,
                "room is left for the new bucket to make exactly the cap");
        ColumnCacheStore.clearForServers(List.of(comp));
    }
}
