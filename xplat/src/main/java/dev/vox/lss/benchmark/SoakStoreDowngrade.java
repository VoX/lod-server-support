package dev.vox.lss.benchmark;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.store.LodStoreService;
import dev.vox.lss.common.store.StoreCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;

/**
 * Dev-only (excluded from release jars): downgrades a staged schema-4/v20 LOD store
 * back to the EXACT released v0.9.x shape — schema 3, wire 19, FNV hashes,
 * native-layout bodies, no {@code wirefmt} column — so the {@code store-migration-join}
 * soak scenario (XVER §9's store-migration variant, C6) can drive the REAL lazy 3→4
 * upgrade + serve-rung translation + background walk against live traffic. This closes
 * C4's accepted gap: no ordinary soak can produce a live 19-row.
 *
 * <p>Runs at SERVER_STARTING (registries loaded, store not yet opened — the service
 * constructs at SERVER_STARTED). Bodies translate v20→native through the PRODUCTION
 * egress translator ({@code NbtSectionSerializer.fromV20}, reached reflectively —
 * package-private by design, and this dev-only class must not widen it). A wrong
 * downgrade cannot silently pass: every serve FNV-validates the rewritten hashes, so
 * corruption reads as zero store hits, which the gate wrapper asserts against.
 */
final class SoakStoreDowngrade {
    private SoakStoreDowngrade() {}

    static void run(MinecraftServer server) {
        Path db = server.getWorldPath(LevelResource.ROOT).normalize()
                .resolve(dev.vox.lss.common.Brand.lowerShortName() + "-lod").resolve("store.db");
        if (!Files.isRegularFile(db)) {
            throw new IllegalStateException("[Soak] downgradeStoreToV19: no store at " + db
                    + " — stage a warmed store (SOAK_WORLD_FROM) before arming the flag");
        }
        try {
            downgrade(db, server.registryAccess());
        } catch (Exception e) {
            // Loud and fatal: a half-downgraded store would make the scenario measure
            // nonsense. The soak.sh join timeout converts the dead server into a red.
            throw new IllegalStateException("[Soak] store downgrade failed", e);
        }
    }

    private record NativeRow(long pos, byte[] nativeBody) {}

    private static void downgrade(Path db, RegistryAccess registryAccess) throws Exception {
        StoreCodec codec = StoreCodec.zstdOrNull();
        if (codec == null) throw new IllegalStateException("zstd natives unavailable");
        Method fromV20 = Class.forName("dev.vox.lss.networking.server.NbtSectionSerializer")
                .getDeclaredMethod("fromV20", byte[].class, RegistryAccess.class);
        fromV20.setAccessible(true);

        int rows = 0;
        int allAir = 0;
        // DriverManager, not SQLiteDataSource: sqlite-jdbc is a runtime dependency of
        // common (present in every dev run), not on fabric's compile classpath — and
        // this dev-only tool must not add it there.
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db)) {
            c.setAutoCommit(false);
            var dimIds = new ArrayList<Integer>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id FROM dims")) {
                while (rs.next()) dimIds.add(rs.getInt(1));
            }
            for (int dimId : dimIds) {
                // Translate first, update second — soak discs are small, so buffering
                // the dimension keeps the SQL simple (no cursor-under-write).
                var translated = new ArrayList<NativeRow>();
                try (Statement st = c.createStatement();
                     ResultSet rs = st.executeQuery(
                             "SELECT pos, usize, blob FROM lods_" + dimId)) {
                    while (rs.next()) {
                        int usize = rs.getInt(2);
                        if (usize == 0) {
                            allAir++; // all-air short-circuits hash validation; leave it
                            continue;
                        }
                        byte[] v20 = codec.decompress(rs.getBytes(3), usize);
                        translated.add(new NativeRow(rs.getLong(1),
                                (byte[]) fromV20.invoke(null, v20, registryAccess)));
                    }
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE lods_" + dimId + " SET chash=?, usize=?, fhash=?, blob=?"
                                + " WHERE pos=?")) {
                    for (NativeRow row : translated) {
                        byte[] frame = codec.compress(row.nativeBody());
                        up.setLong(1, LodStoreService.legacyContentHashFnv(row.nativeBody()));
                        up.setInt(2, row.nativeBody().length);
                        up.setLong(3, LodStoreService.legacyContentHashFnv(frame));
                        up.setBytes(4, frame);
                        up.setLong(5, row.pos());
                        up.executeUpdate();
                        rows++;
                    }
                }
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("ALTER TABLE lods_" + dimId + " DROP COLUMN wirefmt");
                }
            }
            // Synthesize one all-air 19-row per dimension (C6 review M-1): the walk
            // must RETAG usize-0 rows, and no populate world reliably produces one —
            // without this the gate's zero-anomaly assertion never covers the shape
            // that a real End/void upgrade hits on every column.
            for (int dimId : dimIds) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT OR IGNORE INTO lods_" + dimId
                                + " (pos, ts, chash, usize, src_stamp, fhash, blob)"
                                + " VALUES (?, ?, 0, 0, ?, 0, ?)")) {
                    long pos = (1_000_000L << 32) | (1_000_000L & 0xFFFFFFFFL);
                    long now = System.currentTimeMillis() / 1000L;
                    ins.setLong(1, pos);
                    ins.setLong(2, now);
                    ins.setLong(3, now);
                    ins.setBytes(4, new byte[0]);
                    ins.executeUpdate();
                }
            }
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE meta SET v='3' WHERE k='schema_version'");
                st.executeUpdate("UPDATE meta SET v='19' WHERE k='wire_format_version'");
                st.executeUpdate("DELETE FROM meta WHERE k IN ('store_layout',"
                        + " 'migrate_pending', 'migrate_total', 'migrate_done')"
                        + " OR k LIKE 'migrate_progress_%'");
            }
            c.commit();
        }
        LSSLogger.info("[Soak] Store downgraded to the released v0.9.x shape: " + rows
                + " rows rewritten native/FNV (+" + allAir + " all-air untouched),"
                + " schema 3, wire 19, wirefmt column dropped");
    }
}
