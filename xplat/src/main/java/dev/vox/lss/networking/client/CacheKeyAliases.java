package dev.vox.lss.networking.client;

import dev.vox.lss.seed.WorldSubKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The ADDRESS AXIS of the two-axis cache key
 * (docs/planning/cache-alias-keying-and-reset-override-plan.md §2.2): parsing,
 * validation and matching for {@code cacheAddressAliases} — groups of addresses that
 * name ONE server, so a warm cache built under one entry address serves the others.
 * The first entry of a group is CANONICAL: the group's shared bucket is the canonical
 * entry's RAW spelling sanitized (warm-cache adoption holds only for that exact
 * historical spelling — documented), and membership matches NORMALIZED (trim +
 * lowercase ONLY — deliberately no default-port strip: SRV resolution makes
 * {@code a.com} and {@code a.com:25565} potentially different servers).
 *
 * <p>PURE — no Minecraft, no IO, no state. Whether a matched group is actually APPLIED
 * to a connection is {@link AliasCorroboration}'s question, not this class's.
 */
public final class CacheKeyAliases {

    /** The reserved world-axis tail, anywhere in an entry (validation is allowed to be
     *  stricter than the key-build escape): a config entry must not smuggle a seeded
     *  bucket name into the address axis. */
    private static final Pattern RESERVED_ANYWHERE =
            Pattern.compile("(?i)\\.world-[0-9a-f]{16}");

    private CacheKeyAliases() {
    }

    /** A validated group: the canonical (first) entry's raw spelling + every member's
     *  raw spelling in config order. */
    public record Group(String canonicalRaw, List<String> membersRaw) {

        /** Membership test against a normalized connect address. */
        boolean containsNormalized(String normalizedAddr) {
            for (String member : membersRaw) {
                if (normalize(member).equals(normalizedAddr)) return true;
            }
            return false;
        }
    }

    /** Membership normalization: trim + lowercase, nothing else (see the class javadoc
     *  for why no port strip). */
    public static String normalize(String addr) {
        return addr == null ? "" : addr.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The bucket-name component a raw address (or a canonical entry) contributes:
     * sanitized, with the reserved {@code .world-<16 hex>} tail escaped
     * ({@link WorldSubKey#escapeReservedTail}) so no raw spelling can occupy a seeded
     * bucket's name or sit inside the reset sweep's glob.
     */
    public static String addressComponent(String raw) {
        return WorldSubKey.escapeReservedTail(ColumnCacheStore.sanitizeForFilePath(raw));
    }

    /**
     * Validates the raw config shape ({@code List<List<String>>}, GSON may null any
     * level of it) into the surviving groups. A rejected group drops WHOLE with one
     * warning naming the group and the reason (plan §2.2) — fail-open like the
     * {@code crossVersionBlockFallbacks} convention: a broken group must not take the
     * session down, and it must not half-apply either.
     *
     * <p>Rejections: null groups / null-blank entries; {@code unknown}, {@code local:*},
     * {@code realms} (the non-remote bucket names — aliasing them would smear unrelated
     * sessions); entries sanitizing to the {@code _} collapse segment; entries carrying
     * the reserved {@code .world-} tail pattern anywhere; a PORT-BEARING canonical
     * (voxy-extra substitutes the canonical verbatim into a path — a surviving
     * {@code :} breaks Voxy on Windows and splits voxy-extra's own store; plan §8);
     * a member already claimed by an earlier group; a canonical whose bucket collides
     * (case-insensitively) with an earlier group's.
     */
    public static List<Group> validated(List<List<String>> raw, Consumer<String> warn) {
        if (raw == null || raw.isEmpty()) return List.of();
        var out = new ArrayList<Group>();
        var claimedMembers = new HashSet<String>();
        var claimedBuckets = new HashSet<String>();
        for (int i = 0; i < raw.size(); i++) {
            List<String> entries = raw.get(i);
            String label = "cacheAddressAliases group " + (i + 1);
            String reason = rejectReason(entries);
            if (reason != null) {
                warn.accept(label + " dropped: " + reason);
                continue;
            }
            // Intra-group normalized duplicates (case/whitespace variants of one
            // spelling) are REDUNDANT under normalized matching, not errors — dedupe
            // keeping the first spelling (panel fix: dropping the whole group punished
            // exactly the case-variant listing an alias list invites).
            var members = new ArrayList<String>();
            var groupNormalized = new HashSet<String>();
            for (String entry : entries) {
                if (groupNormalized.add(normalize(entry))) {
                    members.add(entry);
                }
            }
            // Cross-group duplicates are checked against SURVIVING groups only, and a
            // dropped group claims nothing (panel fix: the old eager claim let one
            // dropped group cascade spurious drops onto innocent later groups, with a
            // message naming a group that does not exist).
            String duplicate = null;
            for (String normalized : groupNormalized) {
                if (claimedMembers.contains(normalized)) {
                    duplicate = normalized;
                    break;
                }
            }
            if (duplicate != null) {
                warn.accept(label + " dropped: entry '" + duplicate
                        + "' already belongs to an earlier group");
                continue;
            }
            String bucket = addressComponent(members.get(0)).toLowerCase(Locale.ROOT);
            if (!claimedBuckets.add(bucket)) {
                warn.accept(label + " dropped: its canonical bucket '" + bucket
                        + "' collides with an earlier group's");
                continue;
            }
            claimedMembers.addAll(groupNormalized);
            out.add(new Group(members.get(0), List.copyOf(members)));
        }
        return List.copyOf(out);
    }

    /** The per-group structural rejection reason, or null when the group is well formed. */
    private static String rejectReason(List<String> entries) {
        if (entries == null || entries.isEmpty()) return "empty group";
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) return "null/blank entry";
            String normalized = normalize(entry);
            if (normalized.equals("unknown")) return "'unknown' is a reserved bucket name";
            if (normalized.startsWith("local:")) return "'local:*' names a single-player world";
            if (normalized.equals("realms")) return "'realms' is a reserved bucket name";
            if (ColumnCacheStore.sanitizeForFilePath(entry).equals("_")) {
                return "entry '" + entry + "' sanitizes to nothing";
            }
            if (RESERVED_ANYWHERE.matcher(entry).find()) {
                return "entry '" + entry + "' carries the reserved .world-<hex> suffix";
            }
        }
        if (entries.get(0).contains(":")) {
            return "the canonical (first) entry '" + entries.get(0) + "' carries a port — "
                    + "voxy-extra substitutes the canonical verbatim into a directory name, "
                    + "so list the port-free spelling first";
        }
        return null;
    }

    /** The first group whose members contain the connect address (normalized), or null. */
    public static Group match(List<Group> groups, String connectAddr) {
        String normalized = normalize(connectAddr);
        if (normalized.isEmpty()) return null;
        for (Group group : groups) {
            if (group.containsNormalized(normalized)) return group;
        }
        return null;
    }
}
