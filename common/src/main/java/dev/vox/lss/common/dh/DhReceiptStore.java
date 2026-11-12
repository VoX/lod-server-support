package dev.vox.lss.common.dh;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;

/**
 * Persistent DH receipts: tile -> server source timestamp, one small file per logical
 * world. A receipt claims only that a complete whole-tile delivery of that server
 * timestamp was handed to the destination; it never stores terrain. On rejoin the
 * caller probes the destination before trusting a receipt, then asks the server
 * whether the tile changed since that timestamp.
 *
 * <p>Concurrency: worker-only IO, serialized in-process. Two clients sharing one cache
 * root race only on whole-file atomic replacement; a lost receipt means one
 * re-download, never wrong terrain.
 */
public final class DhReceiptStore {
    public static final String DIRECTORY = "!dh-receipts-v1";
    /** Pre-2026-10-10 dev builds stored source DTOs here; reclaimed on first use. */
    static final List<String> LEGACY_TERRAIN_DIRECTORIES = List.of("!dh-terrain-v1", "dh-terrain-v1");
    /** Bump whenever DhColumnConverter output changes; older receipts then re-download. */
    public static final int CONVERSION_VERSION = 7;
    public static final int MAX_ENTRIES = 65_536;
    public static final int MAX_BUCKETS = 64;
    private static final int MAGIC = 0x44485243, VERSION = 1, ENTRY_BYTES = 20, HEADER_BYTES = 44;
    private static final String SUFFIX = ".rcpt";
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    private static final Object IO_LOCK = new Object();
    private static final Set<Path> LEGACY_SWEPT = new HashSet<>();

    public record Entry(long timestamp, int conversion) {
        public boolean current() { return conversion == CONVERSION_VERSION && timestamp > 0; }
    }
    public record Key(String address, String server, String world, String dimension, long seed,
                      DhProfile profile, DhTerrainTile.WorldShape shape) {
        public Key {
            for (String value : List.of(address, server, world, dimension))
                if (value.length() > 4096) throw new IllegalArgumentException("DH receipt identity too long");
            Objects.requireNonNull(profile); Objects.requireNonNull(shape);
        }
        byte[] digest() {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(VERSION);
                for (String value : List.of(normalizeAddress(address), server, world, dimension, profile.name())) out.writeUTF(value);
                out.writeLong(seed); out.writeInt(shape.minY()); out.writeInt(shape.height()); out.writeBoolean(shape.sky());
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return sha256(bytes.toByteArray());
        }
    }

    private final Path root, file;
    private final byte[] identity;

    public DhReceiptStore(Path cacheRoot, Key key) {
        root = cacheRoot.toAbsolutePath().normalize().resolve(DIRECTORY);
        identity = key.digest();
        file = root.resolve(addressHash(key.address()) + "." + HexFormat.of().formatHex(identity) + SUFFIX);
    }

    /** Address spellings that differ only in case, a trailing dot or the default port share receipts. */
    public static String normalizeAddress(String address) {
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    /** Never throws for content problems: an unreadable or foreign file reads as empty. */
    public LinkedHashMap<DhPosition, Entry> load() throws IOException {
        synchronized (IO_LOCK) {
            sweepLegacy(root.getParent());
            var result = new LinkedHashMap<DhPosition, Entry>();
            if (!regularFile(file)) return result;
            long size = Files.size(file);
            if (size < HEADER_BYTES + 8 || size > HEADER_BYTES + 8L + (long) MAX_ENTRIES * ENTRY_BYTES) { deleteOwned(file); return result; }
            byte[] bytes;
            try (var in = Files.newInputStream(file, NOFOLLOW)) { bytes = in.readNBytes((int) size + 1); }
            if (bytes.length != size) return result;
            var buffer = ByteBuffer.wrap(bytes);
            var crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
            byte[] storedIdentity = new byte[32];
            if (buffer.getInt() != MAGIC || buffer.getInt() != VERSION) { deleteOwned(file); return result; }
            buffer.get(storedIdentity);
            int count = buffer.getInt();
            if (!MessageDigest.isEqual(storedIdentity, identity) || count < 0 || count > MAX_ENTRIES
                    || (long) count * ENTRY_BYTES != size - HEADER_BYTES - 8
                    || ByteBuffer.wrap(bytes, bytes.length - 8, 8).getLong() != crc.getValue()) {
                deleteOwned(file); return result;
            }
            for (int i = 0; i < count; i++) {
                long packed = buffer.getLong(), timestamp = buffer.getLong(); int conversion = buffer.getInt();
                try {
                    var position = DhPosition.unpack(packed);
                    if (position.detail() == 6 && timestamp > 0) result.put(position, new Entry(timestamp, conversion));
                } catch (IllegalArgumentException ignored) { /* unrepresentable position: skip */ }
            }
            touch(file);
            return result;
        }
    }

    /** Atomic whole-file replacement, newest entries last; oldest entries beyond the cap are dropped. */
    public void save(Map<DhPosition, Entry> entries) throws IOException {
        synchronized (IO_LOCK) {
            var list = new ArrayList<Map.Entry<DhPosition, Entry>>();
            for (var e : entries.entrySet()) if (e.getValue().timestamp() > 0) list.add(e);
            if (list.size() > MAX_ENTRIES) list = new ArrayList<>(list.subList(list.size() - MAX_ENTRIES, list.size()));
            if (list.isEmpty()) { if (regularFile(file)) deleteOwned(file); return; }
            ensureRoot();
            var buffer = ByteBuffer.allocate(HEADER_BYTES + list.size() * ENTRY_BYTES + 8);
            buffer.putInt(MAGIC).putInt(VERSION).put(identity).putInt(list.size());
            for (var e : list) buffer.putLong(e.getKey().packed()).putLong(e.getValue().timestamp()).putInt(e.getValue().conversion());
            var crc = new CRC32(); crc.update(buffer.array(), 0, buffer.position());
            buffer.putLong(crc.getValue());
            Path temp = Files.createTempFile(root, ".rcpt-", ".tmp");
            try {
                try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    buffer.flip();
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                if (Files.exists(file, NOFOLLOW) && !regularFile(file)) throw new IOException("Unsafe DH receipt file");
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
            trimBuckets();
        }
    }

    /** {@code address==null} clears every receipt bucket; otherwise only that server's. */
    public static void clear(Path cacheRoot, String address) throws IOException {
        synchronized (IO_LOCK) {
            Path base = cacheRoot.toAbsolutePath().normalize();
            sweepLegacy(base);
            Path receipts = base.resolve(DIRECTORY);
            if (!realDirectory(receipts)) return;
            String prefix = address == null ? null : addressHash(address) + ".";
            IOException failure = null;
            try (var files = Files.newDirectoryStream(receipts)) {
                for (Path p : files) {
                    String name = p.getFileName().toString();
                    if (!(name.endsWith(SUFFIX) || name.startsWith(".rcpt-")) || prefix != null && !name.startsWith(prefix)) continue;
                    try { deleteOwned(p); } catch (IOException e) { failure = e; }
                }
            }
            if (failure != null) throw failure;
        }
    }

    private void trimBuckets() throws IOException {
        var buckets = new ArrayList<Path>();
        try (var files = Files.newDirectoryStream(root, "*" + SUFFIX)) { for (Path p : files) if (regularFile(p)) buckets.add(p); }
        if (buckets.size() <= MAX_BUCKETS) return;
        buckets.sort(Comparator.comparing(DhReceiptStore::modified));
        for (int i = 0; i < buckets.size() - MAX_BUCKETS; i++) if (!buckets.get(i).equals(file)) deleteOwned(buckets.get(i));
    }

    /** Reclaim the obsolete DTO cache once per root: our own file shapes only; foreign files and links stay. */
    private static void sweepLegacy(Path base) {
        if (base == null || !LEGACY_SWEPT.add(base)) return;
        for (String name : LEGACY_TERRAIN_DIRECTORIES) {
            Path legacy = base.resolve(name);
            if (!realDirectory(legacy)) continue;
            try (var dirs = Files.newDirectoryStream(legacy)) {
                for (Path dir : dirs) {
                    if (realDirectory(dir) && dir.getFileName().toString().matches("[0-9a-f]{64}\\.[0-9a-f]{64}")) {
                        try (var files = Files.newDirectoryStream(dir)) {
                            for (Path f : files) if (f.getFileName().toString().matches("[0-9a-f]{16}\\.dht|\\.pending-.*\\.tmp")) deleteOwned(f);
                        }
                        try { Files.delete(dir); } catch (IOException ignored) { /* foreign contents preserved */ }
                    } else if (dir.getFileName().toString().equals("quota.lock")) deleteOwned(dir);
                }
                Files.delete(legacy);
            } catch (IOException ignored) { /* best effort; never blocks receipts */ }
        }
    }

    private void ensureRoot() throws IOException {
        Path parent = root.getParent();
        if (!realDirectory(parent)) Files.createDirectories(parent);
        if (Files.notExists(root, NOFOLLOW)) Files.createDirectory(root);
        if (!realDirectory(root)) throw new IOException("Unsafe DH receipt directory");
    }
    private static boolean realDirectory(Path p) {
        try {
            var a = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW);
            return a.isDirectory() && !a.isSymbolicLink() && !a.isOther();
        } catch (IOException e) { return false; }
    }
    private static boolean regularFile(Path p) {
        try {
            var a = Files.readAttributes(p, BasicFileAttributes.class, NOFOLLOW);
            return a.isRegularFile() && !a.isSymbolicLink() && !a.isOther();
        } catch (IOException e) { return false; }
    }
    private static void deleteOwned(Path p) throws IOException { if (regularFile(p)) Files.deleteIfExists(p); }
    private static void touch(Path p) {
        try { Files.setLastModifiedTime(p, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
        catch (IOException ignored) { /* LRU metadata only */ }
    }
    private static java.nio.file.attribute.FileTime modified(Path p) {
        try { return Files.getLastModifiedTime(p, NOFOLLOW); }
        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
    }
    private static String addressHash(String address) {
        return HexFormat.of().formatHex(sha256(normalizeAddress(address).getBytes(StandardCharsets.UTF_8)), 0, 8);
    }
    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
