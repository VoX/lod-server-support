package dev.vox.lss.common.wire;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cursor's NATIVE-layout knowledge, proven against the REAL emitter's output:
 * every committed {@code nbt-corpus} golden (raw {@code serializeChunkNbt} bodies —
 * incl. DIRECT/global palettes, negative Y, waterlogged, light combos, masked) must
 * scan to its exact length and survive parse → emit byte-identical. A cursor that
 * misread any layout detail (palette thresholds, implied long counts, light framing)
 * cannot pass this against all fourteen shapes.
 */
class WireSectionCursorCorpusTest {

    private static Path corpusDir() {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/test/java/dev/vox/lss"))) {
                return dir.resolve("src/test/resources/nbt-corpus");
            }
            Path nested = dir.resolve("fabric");
            if (Files.isDirectory(nested.resolve("src/test/java/dev/vox/lss"))) {
                return nested.resolve("src/test/resources/nbt-corpus");
            }
        }
        throw new IllegalStateException("cannot locate the fabric module source tree from "
                + Path.of("").toAbsolutePath());
    }

    private static List<Path> corpusFiles() throws IOException {
        try (Stream<Path> files = Files.list(corpusDir())) {
            List<Path> bins = files.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .sorted().toList();
            // Count-pinned like the repo's other fixture suites: a vanished golden must
            // red THIS suite, not silently shrink its coverage. Update alongside any
            // corpus addition (the generator lives in NbtSectionSerializerTest).
            assertEquals(14, bins.size(), "nbt-corpus golden count drifted: " + bins);
            return bins;
        }
    }

    @Test
    void everyCorpusGoldenScansToItsExactLength() throws IOException {
        for (Path bin : corpusFiles()) {
            byte[] body = Files.readAllBytes(bin);
            assertEquals(body.length,
                    WireSectionCursor.scan(body, 0, WireSectionCursor.Layout.NATIVE),
                    bin.getFileName() + " must scan cleanly to its end");
        }
    }

    @Test
    void everyCorpusGoldenSurvivesParseEmitByteIdentical() throws IOException {
        boolean sawDirect = false;
        for (Path bin : corpusFiles()) {
            byte[] body = Files.readAllBytes(bin);
            var column = WireSectionCursor.parse(body, WireSectionCursor.Layout.NATIVE);
            assertArrayEquals(body,
                    WireSectionCursor.emit(column, WireSectionCursor.Layout.NATIVE),
                    bin.getFileName() + " must re-emit byte-identical");
            sawDirect |= column.sections().stream().anyMatch(
                    s -> s.blocks().isDirect() || s.biomes().isDirect());
        }
        assertTrue(sawDirect, "corpus must exercise at least one DIRECT container "
                + "(global-palette / biome-global goldens) or this suite lost its teeth");
    }

    /**
     * The translator against the real emitter's output (review MINOR 11): every
     * corpus golden — duplicate palettes, DIRECT/global tiers, masked sections —
     * translates under synthetic identity tables, reparses as legal v20, and matches
     * VOXEL FOR VOXEL: the identity at every block/biome position must equal the
     * identity of the native id at that position. Sections, counts, and light must
     * survive untouched.
     */
    @Test
    void everyCorpusGoldenTranslatesToV20VoxelForVoxel() throws IOException {
        java.util.function.IntFunction<String> blockIdentity = id -> "syntheticblocks:state_" + id;
        java.util.function.IntFunction<String> biomeIdentity = id -> "syntheticbiomes:biome_" + id;
        for (Path bin : corpusFiles()) {
            byte[] body = Files.readAllBytes(bin);
            var nat = WireSectionCursor.parse(body, WireSectionCursor.Layout.NATIVE);
            byte[] v20Bytes = NativeToV20Translator.translate(body, blockIdentity, biomeIdentity);
            var v20 = WireSectionCursor.parse(v20Bytes, WireSectionCursor.Layout.V20);
            assertEquals(nat.sections().size(), v20.sections().size(), bin.getFileName().toString());
            for (int i = 0; i < nat.sections().size(); i++) {
                var n = nat.sections().get(i);
                var v = v20.sections().get(i);
                String at = bin.getFileName() + " section " + i;
                assertEquals(n.sectionY(), v.sectionY(), at);
                assertEquals(n.nonEmptyBlockCount(), v.nonEmptyBlockCount(), at);
                assertEquals(n.fluidCount(), v.fluidCount(), at);
                assertArrayEquals(n.blockLight(), v.blockLight(), at);
                assertArrayEquals(n.skyLight(), v.skyLight(), at);
                assertVoxelsMatch(n.blocks(), v.blocks(), 4096, blockIdentity, v20.dictionary(), at + " blocks");
                assertVoxelsMatch(n.biomes(), v.biomes(), 64, biomeIdentity, v20.dictionary(), at + " biomes");
            }
        }
    }

    private static void assertVoxelsMatch(WireSectionCursor.WireContainer nat,
                                          WireSectionCursor.WireContainer v20,
                                          int entries,
                                          java.util.function.IntFunction<String> identity,
                                          List<String> dict, String at) {
        int[] natValues = nat.bits() == 0 ? null
                : WireSectionCursor.unpack(nat.data(), nat.bits(), entries);
        int[] v20Values = v20.bits() == 0 ? null
                : WireSectionCursor.unpack(v20.data(), v20.bits(), entries);
        for (int i = 0; i < entries; i++) {
            int natId = natValues == null ? nat.palette()[0]
                    : (nat.isDirect() ? natValues[i] : nat.palette()[natValues[i]]);
            int dictIndex = v20Values == null ? v20.palette()[0] : v20.palette()[v20Values[i]];
            assertEquals(identity.apply(natId), dict.get(dictIndex), at + " voxel " + i);
        }
    }
}
