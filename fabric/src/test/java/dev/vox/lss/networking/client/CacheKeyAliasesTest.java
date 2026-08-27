package dev.vox.lss.networking.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alias axis's pure half (plan §2.2): normalization, group matching, the bucket
 * component, and the whole-group validation ladder — each rejection reason fires, drops
 * the WHOLE group with one warning, and never takes a neighbouring group with it.
 */
class CacheKeyAliasesTest {

    private static List<CacheKeyAliases.Group> validated(List<List<String>> raw,
                                                         List<String> warns) {
        return CacheKeyAliases.validated(raw, warns::add);
    }

    // ---- normalization + matching ----

    @Test
    void normalizationIsTrimPlusLowercaseOnly() {
        assertEquals("play.example.com", CacheKeyAliases.normalize("  Play.Example.COM  "));
        assertEquals("a.com:25565", CacheKeyAliases.normalize("a.com:25565"),
                "no default-port strip — SRV resolution makes the port-bearing spelling "
                        + "a potentially different server");
        assertEquals("", CacheKeyAliases.normalize(null));
    }

    @Test
    void matchingIsNormalizedMembershipFirstGroupWins() {
        var warns = new ArrayList<String>();
        var groups = validated(List.of(
                List.of("play.example.com", "alt.example.com"),
                List.of("other.net", "mirror.other.net")), warns);
        assertEquals(2, groups.size());
        assertTrue(warns.isEmpty());
        assertEquals("play.example.com",
                CacheKeyAliases.match(groups, "  ALT.Example.Com ").canonicalRaw());
        assertEquals("other.net", CacheKeyAliases.match(groups, "other.net").canonicalRaw());
        assertNull(CacheKeyAliases.match(groups, "unrelated.example"));
        assertNull(CacheKeyAliases.match(groups, "alt.example.com:25565"),
                "a port-bearing spelling is NOT the port-free member");
        assertNull(CacheKeyAliases.match(groups, null));
    }

    // ---- the bucket component ----

    @Test
    void addressComponentSanitizesAndEscapesTheReservedTail() {
        assertEquals("play.example.com_25566",
                CacheKeyAliases.addressComponent("play.example.com:25566"));
        assertEquals("x.world-0123456789abcdef.addr",
                CacheKeyAliases.addressComponent("x.world-0123456789abcdef"));
        assertEquals("_", CacheKeyAliases.addressComponent(".."),
                "the store's dot-collapse still applies");
    }

    // ---- validation: each rejection reason ----

    @Test
    void nullAndEmptyShapesValidateToNothing() {
        assertTrue(CacheKeyAliases.validated(null, w -> {}).isEmpty());
        assertTrue(CacheKeyAliases.validated(List.of(), w -> {}).isEmpty());
        var warns = new ArrayList<String>();
        assertTrue(validated(Arrays.asList((List<String>) null), warns).isEmpty());
        assertEquals(1, warns.size());
    }

    @Test
    void blankAndNullEntriesDropTheGroup() {
        var warns = new ArrayList<String>();
        assertTrue(validated(List.of(Arrays.asList("a.com", "  ")), warns).isEmpty());
        assertTrue(validated(List.of(Arrays.asList("a.com", (String) null)), warns).isEmpty());
        assertEquals(2, warns.size());
    }

    @Test
    void reservedBucketNamesDropTheGroup() {
        var warns = new ArrayList<String>();
        assertTrue(validated(List.of(List.of("a.com", "Unknown")), warns).isEmpty(),
                "'unknown' (any case) is the no-address bucket");
        assertTrue(validated(List.of(List.of("a.com", "local:world")), warns).isEmpty(),
                "'local:*' names a single-player world");
        assertTrue(validated(List.of(List.of("a.com", "realms")), warns).isEmpty());
        assertTrue(validated(List.of(List.of("a.com", "...")), warns).isEmpty(),
                "an entry sanitizing to the '_' collapse segment");
        assertTrue(validated(List.of(List.of("a.com", "b.world-0123456789abcdef")), warns)
                        .isEmpty(),
                "the reserved world-axis suffix may not appear in the address axis");
        assertTrue(validated(List.of(List.of("a.com", "b.WORLD-0123456789ABCDEF.x")), warns)
                        .isEmpty(),
                "…case-insensitively and anywhere in the entry");
        assertEquals(6, warns.size());
    }

    @Test
    void portBearingCanonicalsDropTheGroupButPortBearingMembersAreFine() {
        var warns = new ArrayList<String>();
        assertTrue(validated(List.of(List.of("a.com:25565", "b.com")), warns).isEmpty(),
                "voxy-extra substitutes the canonical verbatim — a ':' in it breaks Voxy "
                        + "on Windows and splits voxy-extra's own store");
        assertEquals(1, warns.size());
        var ok = validated(List.of(List.of("a.com", "a.com:25566")), warns);
        assertEquals(1, ok.size(), "a port-bearing MEMBER is a legitimate second spelling");
    }

    @Test
    void crossGroupDuplicatesDropTheLaterGroupOnly() {
        var warns = new ArrayList<String>();
        var groups = validated(List.of(
                List.of("a.com", "shared.com"),
                List.of("b.com", "SHARED.com")), warns);
        assertEquals(1, groups.size());
        assertEquals("a.com", groups.get(0).canonicalRaw());
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("group 2"), "the LATER group is the dropped one");
    }

    @Test
    void collidingCanonicalBucketsDropTheLaterGroup() {
        var warns = new ArrayList<String>();
        // Different raw canonicals, same sanitized bucket (case-insensitively).
        var groups = validated(List.of(
                List.of("a.com", "x.net"),
                List.of("A.Com", "y.net")), warns);
        assertEquals(1, groups.size());
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("group 2"));
    }

    @Test
    void aDroppedGroupClaimsNothingSoLaterGroupsSurvive() {
        // Panel fix: the old eager claim cascaded — a group dropped for one duplicate
        // poisoned its OTHER members against innocent later groups, with a message
        // naming a group that does not exist.
        var warns = new ArrayList<String>();
        var groups = validated(List.of(
                List.of("a.com", "shared.com"),
                List.of("b.com", "shared.com", "c.com"),
                List.of("d.com", "c.com")), warns);
        assertEquals(2, groups.size(),
                "group 2 drops for 'shared.com'; group 3 keeps 'c.com' — a dropped "
                        + "group must not have claimed it");
        assertEquals("d.com", groups.get(1).canonicalRaw());
        assertEquals(1, warns.size(), warns.toString());
    }

    @Test
    void intraGroupCaseVariantsDedupeInsteadOfDroppingTheGroup() {
        // Panel fix: listing case variants of one hostname is exactly what an alias
        // list invites — redundant under normalized matching, never group-fatal.
        var warns = new ArrayList<String>();
        var groups = validated(List.of(List.of("a.com", "A.COM", "alt.a.com")), warns);
        assertEquals(1, groups.size());
        assertTrue(warns.isEmpty());
        assertEquals(List.of("a.com", "alt.a.com"), groups.get(0).membersRaw(),
                "the first spelling survives, the case variant folds into it");
        assertNotNull(CacheKeyAliases.match(groups, "a.COM"),
                "the deduped spelling still matches (normalized membership)");
    }

    @Test
    void aRejectedGroupDoesNotTakeItsNeighboursDown() {
        var warns = new ArrayList<String>();
        var groups = validated(List.of(
                List.of("good.com", "alt.good.com"),
                List.of("bad.com:1", "alt.bad.com"),
                List.of("also-good.net", "alt.also-good.net")), warns);
        assertEquals(2, groups.size());
        assertEquals(1, warns.size());
        assertNotNull(CacheKeyAliases.match(groups, "alt.also-good.net"));
    }

    @Test
    void aSingleEntryGroupIsAcceptedAndInert() {
        var warns = new ArrayList<String>();
        var groups = validated(List.of(List.of("solo.example.com")), warns);
        assertEquals(1, groups.size());
        assertTrue(warns.isEmpty());
        assertEquals("solo.example.com",
                CacheKeyAliases.match(groups, "solo.example.com").canonicalRaw());
    }
}
