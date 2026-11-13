package dev.vox.lss.testutil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One Mockito mock maker in the Fabric test JVM: no test source names one, so every mock
 * comes from the default (inline) maker.
 *
 * <p>mockito-core 5.20.0, the pin in {@code fabric/build.gradle}, registers each maker a
 * test names. To find a mock's handler it asks the registered makers in hash-map order and
 * asserts that the first one to recognise the mock is the one that made it
 * ({@code MockUtil.getMockHandlerOrNull}). The subclass maker recognises every generated
 * subclass, and that is what the default maker builds for an abstract type or an interface.
 * So a single {@code mockMaker(MockMakers.SUBCLASS)} made each later {@code mock(Level.class)}
 * in the same JVM pass or throw {@code AssertionError} by two identity hashes. Gradle runs
 * the classes that failed last time first, which is how {@code DhClientSessionTest} came to
 * run ahead of {@code XaeroLoadPixelParityTest} and {@code JourneyMapDecoderParityTest} on a
 * release gate (docs/operations/test-flakes.md).
 *
 * <p>Mockito 5.24.0 fixed the lookup (mockito/mockito#3795, issues #3406 and #2823). Lift
 * this pin only together with a Mockito pin at or above that version. Paper's test JVM has
 * the same Mockito pin and no guard of its own.
 */
class SingleMockMakerContractTest {

    /** {@code MockMakers.X}, {@code withSettings().mockMaker(..)} and {@code @Mock(mockMaker = ..)}. */
    private static final Pattern NAMES_A_MAKER = Pattern.compile("\\bMockMakers\\b|\\bmockMaker\\s*[(=]");

    /** Every tree this JVM compiles tests from; the DH live rig joins it through
     *  {@code fixture-tests.gradle}. */
    private static final String[] TEST_TREES = {
            "fabric/src/test",
            "test-fixtures/dh-live/src",
            "test-fixtures/dh-live/tests",
    };

    @Test
    void noTestSourceNamesAMockitoMockMaker() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String tree : TEST_TREES) {
            Path root = RepoPaths.locate(tree);
            assertTrue(Files.isDirectory(root), tree + " moved: update TEST_TREES, do not drop the tree");
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    if (file.getFileName().toString().equals("SingleMockMakerContractTest.java")) continue;
                    if (NAMES_A_MAKER.matcher(read(file)).find()) {
                        offenders.add(tree + "/" + root.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        assertTrue(scanned > 100, "the scan found only " + scanned + " test sources: the trees moved");
        assertTrue(offenders.isEmpty(),
                "a second Mockito mock maker makes default-maker mocks of abstract types fail by"
                        + " hash order in the same JVM (Mockito below 5.24.0): use plain mock(..) in "
                        + offenders);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
