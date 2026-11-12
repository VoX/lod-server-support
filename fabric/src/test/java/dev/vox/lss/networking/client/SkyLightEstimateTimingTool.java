package dev.vox.lss.networking.client;

import com.mojang.serialization.Lifecycle;
import dev.vox.lss.api.VoxelColumnData;
import dev.vox.lss.common.voxel.SkyLightEstimate;
import dev.vox.lss.networking.server.DiskSerializerTestAccess;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement tool for the sky-light estimate (issue #382), NOT a CI test: it skips itself
 * unless the environment variable {@code LSS_SKY_ESTIMATE_REGIONS} names a directory of
 * Overworld region files ({@code r.X.Z.mca}, section range -4..19).
 *
 * <pre>
 * LSS_SKY_ESTIMATE_REGIONS=/path/to/region bash tools/verify/run-gradle.sh :fabric:test \
 *     --tests '*SkyLightEstimateTimingTool*' -i
 * </pre>
 * Every FULL chunk is stripped of its light as vanilla's {@code ChunkDeleteLightFix} does,
 * serialized through the production disk path, and decoded by the client. Timed per
 * column, after one warm-up pass: the decode itself, and what the estimate adds to it
 * (the predicate plus the estimate). Also timed: the predicate alone on the same columns
 * with their light left in, which is what every ordinary column pays. Two synthetic
 * pathological columns follow (stairs and slabs, and piston heads, through the full height).
 * Results are printed
 * and, when {@code LSS_SKY_ESTIMATE_OUT} names a file, written there;
 * {@code LSS_SKY_ESTIMATE_PROFILE=1} adds a crude stack-sample breakdown.
 */
class SkyLightEstimateTimingTool {
    private static final int MIN_SECTION = -4, MAX_SECTION = 19, SECTIONS = 24, ROUNDS = 3;
    private static final int PATHOLOGICAL_WARMUP = 10, PATHOLOGICAL_RUNS = 60;

    private static final class Series {
        private long[] v = new long[4096];
        private int n;
        void add(long x) {
            if (n == v.length) v = Arrays.copyOf(v, n * 2);
            v[n++] = x;
        }
        long pct(double p) {
            if (n == 0) return 0;
            long[] c = Arrays.copyOf(v, n);
            Arrays.sort(c);
            return c[Math.min(n - 1, (int) Math.floor(p * n))];
        }
        long total() {
            long s = 0;
            for (int i = 0; i < n; i++) s += v[i];
            return s;
        }
        String line(String name) {
            return String.format("%-34s n=%d median=%d ns  p90=%d ns  p99=%d ns  max=%d ns  mean=%d ns",
                    name, n, pct(0.5), pct(0.9), pct(0.99), pct(1.0), n == 0 ? 0 : total() / n);
        }
    }

    @Test
    void measure() throws Exception {
        String dir = System.getenv("LSS_SKY_ESTIMATE_REGIONS");
        assumeTrue(dir != null && !dir.isBlank(), "not requested (LSS_SKY_ESTIMATE_REGIONS is not set)");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var source = VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.BIOME);
        var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        source.listElements().forEach(ref -> biomes.register(ref.key(), ref.value(), RegistrationInfo.BUILT_IN));
        biomes.freeze();
        RegistryAccess registryAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(biomes));
        var factory = PalettedContainerFactory.create(registryAccess);
        var resolver = new ClientIdentityResolver(registryAccess.lookupOrThrow(Registries.BIOME),
                factory.biomeStrategy().globalMap());

        // The client-native bytes of every column, with and without its light.
        List<byte[]> stripped = new ArrayList<>(), lit = new ArrayList<>();
        List<Path> regions;
        try (var files = Files.list(Path.of(dir))) {
            regions = files.filter(p -> p.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca")).sorted().toList();
        }
        assumeTrue(!regions.isEmpty(), "no region files in " + dir);
        int chunks = 0;
        for (Path region : regions) {
            try (var raf = new RandomAccessFile(region.toFile(), "r")) {
                if (raf.length() < 8192) continue;
                byte[] header = new byte[4096];
                raf.readFully(header);
                for (int i = 0; i < 1024; i++) {
                    int loc = (header[i * 4] & 0xFF) << 16 | (header[i * 4 + 1] & 0xFF) << 8 | header[i * 4 + 2] & 0xFF;
                    if (loc == 0) continue;
                    CompoundTag chunk = readChunk(raf, loc);
                    if (chunk == null) continue;
                    byte[] litWire = DiskSerializerTestAccess.serialize(chunk, registryAccess, MIN_SECTION, MAX_SECTION, true);
                    if (litWire == null || litWire.length == 0) continue;
                    chunk.remove("isLightOn");
                    for (var e : chunk.getList("sections").orElseThrow()) {
                        ((CompoundTag) e).remove("SkyLight");
                        ((CompoundTag) e).remove("BlockLight");
                    }
                    byte[] strippedWire = DiskSerializerTestAccess.serialize(chunk, registryAccess, MIN_SECTION, MAX_SECTION, true);
                    lit.add(resolver.toNative(litWire));
                    stripped.add(resolver.toNative(strippedWire));
                    chunks++;
                }
            }
        }
        assumeTrue(chunks > 0, "no FULL chunks in " + dir);

        var estimator = new ClientSkyLightEstimator();
        var decodeNs = new Series();
        var estimateNs = new Series();
        var predicateNs = new Series();
        var litSections = new Series();
        var addedSections = new Series();
        var spreadSteps = new Series();
        var sampler = Sampler.startIfRequested(Thread.currentThread());
        int estimated = 0, capped = 0, unlitColumns = 0, litColumnsThatLackLight = 0;
        for (int round = 0; round <= ROUNDS; round++) {
            boolean record = round > 0;   // round 0 warms the JIT
            for (int i = 0; i < chunks; i++) {
                long t0 = System.nanoTime();
                var decoded = ClientColumnProcessor.decodeColumn(stripped.get(i), SECTIONS, factory);
                long t1 = System.nanoTime();
                ClientSkyLightEstimator.Result result = null;
                boolean lacks = ClientSkyLightEstimator.lacksSkyLight(decoded.sections());
                if (lacks) result = estimator.estimate(decoded.sections(), SECTIONS, MIN_SECTION, factory);
                long t2 = System.nanoTime();
                if (record) {
                    decodeNs.add(t1 - t0);
                    estimateNs.add(t2 - t1);
                }
                if (round == 1) {
                    if (lacks) unlitColumns++;
                    if (result != null) {
                        estimated++;
                        if (result.capped()) capped++;
                        litSections.add(result.litSections());
                        addedSections.add(result.addedSections());
                        spreadSteps.add(result.spreadSteps());
                    }
                }
                var withLight = ClientColumnProcessor.decodeColumn(lit.get(i), SECTIONS, factory);
                long t3 = System.nanoTime();
                boolean lacksAlthoughLit = ClientSkyLightEstimator.lacksSkyLight(withLight.sections());
                long t4 = System.nanoTime();
                if (record) predicateNs.add(t4 - t3);
                if (round == 1 && lacksAlthoughLit) litColumnsThatLackLight++;
            }
        }

        // Pathological columns (not terrain): what an unlucky or hostile column can cost.
        var pathological = new StringBuilder();
        var columns = new ArrayList<Pathological>();
        columns.add(stairsAndSlabs(factory));
        var heads = partialTopsAndBottoms(factory);
        if (heads != null) columns.add(heads);
        else pathological.append("pathological: no state with partial top and bottom faces found, column skipped\n");
        for (var column : columns) {
            var ns = new Series();
            ClientSkyLightEstimator.Result last = null;
            for (int i = 0; i < PATHOLOGICAL_WARMUP + PATHOLOGICAL_RUNS; i++) {
                long t0 = System.nanoTime();
                ClientSkyLightEstimator.Result result = null;
                if (ClientSkyLightEstimator.lacksSkyLight(column.sections())) {
                    result = estimator.estimate(column.sections(), SECTIONS, MIN_SECTION, factory);
                }
                long t1 = System.nanoTime();
                if (i >= PATHOLOGICAL_WARMUP) ns.add(t1 - t0);
                last = result;
            }
            pathological.append(ns.line("pathological: " + column.name())).append('\n');
            pathological.append(last == null ? "    (no light)\n" : String.format("    spread steps=%d capped=%s lit sections=%d%n",
                    last.spreadSteps(), last.capped(), last.litSections()));
        }

        var report = new StringBuilder();
        report.append("Sky-light estimate timing, regions ").append(dir).append('\n');
        report.append("java ").append(System.getProperty("java.version")).append(", ")
                .append(Runtime.getRuntime().availableProcessors()).append(" cpus, ")
                .append(ROUNDS).append(" measured rounds after 1 warm-up round\n");
        report.append(String.format("columns=%d (stripped: %d have blocks and no sky light; %d received an estimate, %d capped)%n",
                chunks, unlitColumns, estimated, capped));
        report.append(String.format("lit columns the predicate would still pass to the estimate: %d%n", litColumnsThatLackLight));
        report.append(decodeNs.line("decode (stripped column)")).append('\n');
        report.append(estimateNs.line("predicate + estimate (stripped)")).append('\n');
        report.append(predicateNs.line("predicate alone (lit column)")).append('\n');
        report.append(String.format("estimate / decode, total time: %.2f%n", (double) estimateNs.total() / Math.max(1, decodeNs.total())));
        report.append(String.format("sections with an estimated layer per column: median=%d max=%d; added air sections: median=%d max=%d%n",
                litSections.pct(0.5), litSections.pct(1.0), addedSections.pct(0.5), addedSections.pct(1.0)));
        report.append(String.format("spread steps per column: median=%d p90=%d p99=%d max=%d (budget %d)%n",
                spreadSteps.pct(0.5), spreadSteps.pct(0.9), spreadSteps.pct(0.99), spreadSteps.pct(1.0), 1 << 17));
        report.append(pathological);
        if (sampler != null) report.append(sampler.finish());
        System.out.print(report);
        String out = System.getenv("LSS_SKY_ESTIMATE_OUT");
        if (out != null && !out.isBlank()) Files.writeString(Path.of(out), report.toString());
    }

    private record Pathological(String name, VoxelColumnData.SectionData[] sections) {}

    private interface CellState {
        BlockState at(int x, int y, int z);
    }

    /** A column of the level's full height, every cell from {@code cells} (y counts from the level bottom). */
    private static VoxelColumnData.SectionData[] fullHeight(PalettedContainerFactory factory, CellState cells) {
        var sections = new VoxelColumnData.SectionData[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            var section = new LevelChunkSection(factory);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                section.setBlockState(x, y, z, cells.at(x, i * 16 + y, z), false);
            }
            sections[i] = new VoxelColumnData.SectionData(MIN_SECTION + i, section, null, null);
        }
        return sections;
    }

    /**
     * Stairs and slabs alternating through the full height, behind one open plane of air at
     * x = 0: the sky reaches every height there and the light then has to work its way
     * through partial faces, pair by pair.
     */
    private static Pathological stairsAndSlabs(PalettedContainerFactory factory) {
        var air = Blocks.AIR.defaultBlockState();
        return new Pathological("stairs and slabs, full height", fullHeight(factory, (x, y, z) -> {
            if (x == 0) return air;
            if (((x + y + z) & 1) == 0) {
                return Blocks.OAK_STAIRS.defaultBlockState()
                        .setValue(StairBlock.FACING, Direction.from2DDataValue((x + z) & 3))
                        .setValue(StairBlock.HALF, ((y >> 1) & 1) == 0 ? Half.BOTTOM : Half.TOP);
            }
            return Blocks.OAK_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, ((x + (y >> 1)) & 1) == 0 ? SlabType.BOTTOM : SlabType.TOP);
        }));
    }

    /**
     * A solid block of piston heads facing sideways: no dampening, and the top and bottom
     * faces are both partial, so the source pass runs down the full height and has to
     * decide a pair of partial faces for every cell it enters. Null when this line's piston
     * head does not have that shape.
     */
    private static Pathological partialTopsAndBottoms(PalettedContainerFactory factory) {
        var head = Blocks.PISTON_HEAD.defaultBlockState();
        int descriptor = ClientSkyLightEstimator.describe(head.setValue(PistonHeadBlock.FACING, Direction.EAST),
                ClientSkyLightEstimator.VANILLA);
        boolean partialTopAndBottom = (descriptor & 15) == 0
                && (descriptor >>> 10 + SkyLightEstimate.UP & 1) != 0 && (descriptor >>> 10 + SkyLightEstimate.DOWN & 1) != 0;
        if (!partialTopAndBottom) return null;
        return new Pathological("piston heads, full height", fullHeight(factory, (x, y, z) ->
                head.setValue(PistonHeadBlock.FACING, Direction.from2DDataValue((x + y + z) & 3))));
    }

    /** A crude stack sampler ({@code LSS_SKY_ESTIMATE_PROFILE=1}): where the measured thread is, every millisecond. */
    private static final class Sampler extends Thread {
        private final Thread target;
        private final java.util.Map<String, Integer> top = new java.util.HashMap<>();
        private volatile boolean running = true;
        private int samples;

        private Sampler(Thread target) {
            super("sky-estimate-sampler");
            this.target = target;
            setDaemon(true);
        }

        static Sampler startIfRequested(Thread target) {
            if (!"1".equals(System.getenv("LSS_SKY_ESTIMATE_PROFILE"))) return null;
            var sampler = new Sampler(target);
            sampler.start();
            return sampler;
        }

        @Override public void run() {
            while (running) {
                var stack = target.getStackTrace();
                if (stack.length > 0) {
                    samples++;
                    for (int depth = 0; depth < Math.min(3, stack.length); depth++) {
                        String frame = depth + " " + stack[depth].getClassName().replaceAll(".*\\.", "") + "." + stack[depth].getMethodName();
                        top.merge(frame, 1, Integer::sum);
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        String finish() throws InterruptedException {
            running = false;
            join(1000);
            var out = new StringBuilder("stack samples (depth frame share), " + samples + " samples:\n");
            top.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(30)
                    .forEach(e -> out.append(String.format("  %5.1f%%  %s%n", 100.0 * e.getValue() / samples, e.getKey())));
            return out.toString();
        }
    }

    private static CompoundTag readChunk(RandomAccessFile raf, int sectorOffset) throws IOException {
        long at = (long) sectorOffset * 4096;
        if (at + 5 > raf.length()) return null;
        raf.seek(at);
        int length = raf.readInt();
        if (length <= 1 || at + 4 + length > raf.length()) return null;
        int compression = raf.readByte() & 0xFF;
        byte[] data = new byte[length - 1];
        raf.readFully(data);
        byte[] nbt;
        try {
            nbt = switch (compression) {
                case 1 -> new GZIPInputStream(new ByteArrayInputStream(data)).readAllBytes();
                case 2 -> new InflaterInputStream(new ByteArrayInputStream(data)).readAllBytes();
                case 3 -> data;
                default -> null;   // external (.mcc) and LZ4 chunks are skipped
            };
            if (nbt == null) return null;
            try (var in = new DataInputStream(new ByteArrayInputStream(nbt))) {
                return NbtIo.read(in, NbtAccounter.unlimitedHeap());
            }
        } catch (IOException e) {
            return null;
        }
    }
}
