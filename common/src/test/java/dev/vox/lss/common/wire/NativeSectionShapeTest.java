package dev.vox.lss.common.wire;

import dev.vox.lss.common.wire.WireSectionCursor.Layout;
import dev.vox.lss.common.wire.WireSectionCursor.WireColumn;
import dev.vox.lss.common.wire.WireSectionCursor.WireContainer;
import dev.vox.lss.common.wire.WireSectionCursor.WireSection;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V-2/S1 (version-port-isolation-plan.md §3): the {@link NativeSectionShape} descriptor's
 * own contract — the NATIVE-only scope-hygiene pins, the fold unreachability rule, and
 * the layout round-trips DERIVED from the descriptor (so the suite is line-invariant:
 * a 1-short support line edits the descriptor and these assertions follow).
 */
class NativeSectionShapeTest {

    private static final WireContainer SINGLE_BLOCK = new WireContainer(0, new int[] {1}, new long[0]);
    private static final WireContainer SINGLE_BIOME = new WireContainer(0, new int[] {0}, new long[0]);

    @Test
    void v20CarriesBothCountsRegardlessOfTheNativeDescriptor() {
        // The never-tiered wire spec: V20 bodies decode on ANY supported MC line, so the
        // v20 header can NEVER follow the per-line native shape. On a 1-short line this
        // test is the tripwire for a port that wrongly couples the v20 branch to
        // NATIVE_COUNT_SHORTS (both ends of that line would agree — nothing else reds).
        var column = new WireColumn(List.of("minecraft:stone", "minecraft:plains"),
                List.of(new WireSection(3, 100, 7, SINGLE_BLOCK, SINGLE_BIOME, null, null)));
        var back = WireSectionCursor.parse(
                WireSectionCursor.emit(column, Layout.V20), Layout.V20).sections().get(0);
        assertEquals(100, back.nonEmptyBlockCount());
        assertEquals(7, back.fluidCount(), "v20 carries fluidCount on EVERY line");
    }

    @Test
    void nativeFluidSurvivesExactlyWhenTheLineCarriesTheSecondShort() {
        var column = new WireColumn(List.of(),
                List.of(new WireSection(3, 100, 7, SINGLE_BLOCK, SINGLE_BIOME, null, null)));
        var back = WireSectionCursor.parse(
                WireSectionCursor.emit(column, Layout.NATIVE), Layout.NATIVE).sections().get(0);
        // Derived both ways (V-2 review MAJOR-1 — FOLD, not drop): a 1-short line's
        // native header carries the LINE fold (recorded 1.21.11: the sum), and fluid
        // itself survives only where the second short exists.
        assertEquals(NativeSectionShape.NATIVE_COUNT_SHORTS == 2
                        ? 100 : NativeSectionShape.foldedCountForNativeHeader(100, 7),
                back.nonEmptyBlockCount(),
                "the one-short native header carries the line-level fold");
        assertEquals(NativeSectionShape.NATIVE_COUNT_SHORTS == 2 ? 7 : 0, back.fluidCount(),
                "the native layout preserves fluidCount only where the line carries it");
    }

    @Test
    void foldsAreUnreachableWhileTheLineCarriesTwoShorts() {
        // The folds are 1-short-line values (each port defines its recorded family
        // fold); on a two-short line reaching one is a caller bug and must be LOUD.
        if (NativeSectionShape.NATIVE_COUNT_SHORTS == 2) {
            assertThrows(IllegalStateException.class,
                    () -> NativeSectionShape.foldedCountForNativeHeader(1, 2));
            assertThrows(IllegalStateException.class,
                    () -> NativeSectionShape.foldedCountFabricFamily(1, 2));
            assertThrows(IllegalStateException.class,
                    () -> NativeSectionShape.foldedCountPaperFamily(1, 2));
            assertTrue(NativeSectionShape.familiesFoldIdentically(),
                    "two verbatim pairs are trivially identical — the corpus parity "
                            + "runs strict byte identity on this line");
        } else {
            // A 1-short line MUST have defined all three folds (no throw) — and the
            // parity flip key must reflect whether the families agree.
            NativeSectionShape.foldedCountForNativeHeader(100, 7);
            int fab = NativeSectionShape.foldedCountFabricFamily(100, 7);
            int pap = NativeSectionShape.foldedCountPaperFamily(100, 7);
            assertEquals(fab == pap, NativeSectionShape.familiesFoldIdentically());
        }
    }

    @Test
    void v20BitWidthConstantsAreLiteralsNeverDescriptorDerived() throws Exception {
        // Scope hygiene (the plan's S1 pin): V20_BLOCK_MAX_BITS / V20_BIOME_MAX_BITS are
        // wire spec. A port that "helpfully" derives them from the descriptor forks the
        // never-tiered wire silently — both ends of that line agree, so only a source
        // pin catches it.
        Path cursor = locate("common/src/main/java/dev/vox/lss/common/wire/WireSectionCursor.java");
        String source = Files.readString(cursor);
        for (String name : new String[] {"V20_BLOCK_MAX_BITS", "V20_BIOME_MAX_BITS"}) {
            var m = Pattern.compile(name + "\\s*=\\s*(\\S+?);").matcher(source);
            assertTrue(m.find(), name + " declaration not found in WireSectionCursor");
            assertTrue(m.group(1).matches("\\d+"),
                    name + " must be an integer literal (wire spec), never derived — got '"
                            + m.group(1) + "'");
        }
        String descriptor = Files.readString(locate(
                "common/src/main/java/dev/vox/lss/common/wire/NativeSectionShape.java"));
        String codeOnly = descriptor.replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
        assertTrue(!codeOnly.contains("V20_"),
                "the descriptor must carry NATIVE fields only — no V20_* references in "
                        + "code (javadoc may NAME the constants it disclaims)");
    }

    private static Path locate(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("cannot locate " + repoRelative);
    }
}
