package dev.vox.lss;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** The real-tree gametests exercise Fabric; Paper keeps the exact dev-only executor. */
class SoakCommandExecutorContractTest {
    @Test
    void paperExecutorMatchesTheRealTreeTestedFabricExecutor() throws Exception {
        String fabric = Files.readString(locate("fabric/src/main/java/dev/vox/lss/benchmark/SoakCommandExecutor.java"));
        String paper = Files.readString(locate("paper/src/main/java/dev/vox/lss/paper/soak/SoakCommandExecutor.java"));
        assertEquals(fabric.replace("package dev.vox.lss.benchmark;", "package dev.vox.lss.paper.soak;"), paper);
    }

    @Test
    void executorPackagesRemainExcludedFromReleaseJars() throws Exception {
        assertTrue(Files.readString(locate("fabric/build.gradle")).contains("exclude 'dev/vox/lss/benchmark/**'"));
        assertTrue(Files.readString(locate("paper/build.gradle")).contains("exclude 'dev/vox/lss/paper/soak/**'"));
    }

    private static Path locate(String relative) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path path = dir.resolve(relative);
            if (Files.exists(path)) return path;
        }
        throw new IllegalStateException("Cannot find " + relative);
    }
}
