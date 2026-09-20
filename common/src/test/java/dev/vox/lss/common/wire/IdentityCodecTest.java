package dev.vox.lss.common.wire;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The canonical identity form (XVER §2.1): all properties, sorted by name, no spaces;
 * bare form without properties; strict parse that accepts ONLY the canonical
 * spelling; and the charset-safety pin — the grammar PROVES the four structural
 * characters cannot occur inside any segment, which is the no-escaping premise.
 */
class IdentityCodecTest {

    @Test
    void bareFormForPropertylessStates() {
        assertEquals("minecraft:stone", IdentityCodec.canonical("minecraft:stone", Map.of()));
        assertEquals("minecraft:stone", IdentityCodec.canonical("minecraft:stone", null));
    }

    @Test
    void propertiesAreSortedByNameRegardlessOfInputOrder() {
        var props = new LinkedHashMap<String, String>();
        props.put("waterlogged", "false");
        props.put("facing", "north");
        props.put("half", "top");
        assertEquals("minecraft:oak_stairs[facing=north,half=top,waterlogged=false]",
                IdentityCodec.canonical("minecraft:oak_stairs", props));
    }

    @Test
    void roundTripThroughParse() {
        String identity = "mod_pack:heavy/ore_block[age=25,lit=true]";
        var parsed = IdentityCodec.parse(identity);
        assertEquals("mod_pack:heavy/ore_block", parsed.name());
        assertEquals(List.of(Map.entry("age", "25"), Map.entry("lit", "true")),
                parsed.properties());
        assertEquals(identity, IdentityCodec.canonical(parsed.name(),
                Map.ofEntries(parsed.properties().toArray(Map.Entry[]::new))));
    }

    @Test
    void biomeStyleBareIdentifiersValidate() {
        IdentityCodec.validate("minecraft:windswept_gravelly_hills");
        IdentityCodec.validate("terralith:skylands_autumn");
    }

    @Test
    void malformedStringsAreRejected() {
        for (String bad : new String[] {
                "", "stone", ":stone", "minecraft:", "a:b:c",
                "minecraft:stone[]",                       // empty list must be bare
                "minecraft:stone[facing]",                 // no '='
                "minecraft:stone[facing=]",                // empty value
                "minecraft:stone[=north]",                 // empty name
                "minecraft:stone[facing=north",            // unterminated
                "minecraft:stone[b=1,a=2]",                // unsorted
                "minecraft:stone[a=1,a=2]",                // duplicate
                "minecraft:stone[a=1,,b=2]",               // empty pair
                "minecraft:Stone",                         // uppercase path
                "Minecraft:stone",                         // uppercase namespace
                "minecraft:stone[Facing=north]",           // uppercase prop name
                "minecraft:stone[facing=North]",           // uppercase prop value
                "minecraft:sto ne", "minecraft:stone[a=1] ",
        }) {
            assertThrows(IllegalArgumentException.class, () -> IdentityCodec.validate(bad),
                    "should have rejected: " + bad);
        }
    }

    /**
     * The no-escaping premise, pinned as grammar: every segment charset excludes
     * '[' ']' ',' '=' and whitespace, so no legal identity can contain a structural
     * character anywhere but its structural position.
     */
    @Test
    void structuralCharactersCannotOccurInsideAnySegment() {
        for (char c : new char[] { '[', ']', ',', '=', ' ', '\t' }) {
            String inNamespace = "min" + c + "ecraft:stone";
            String inPath = "minecraft:st" + c + "one";
            String inPropName = "minecraft:stone[fa" + c + "cing=north]";
            String inPropValue = "minecraft:stone[facing=no" + c + "rth]";
            for (String bad : new String[] { inNamespace, inPath, inPropName, inPropValue }) {
                assertThrows(IllegalArgumentException.class, () -> IdentityCodec.validate(bad),
                        "structural char '" + c + "' must be rejected in: " + bad);
            }
        }
    }

    @Test
    void canonicalValidatesItsOwnOutput() {
        assertThrows(IllegalArgumentException.class,
                () -> IdentityCodec.canonical("minecraft:stone", Map.of("bad key", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> IdentityCodec.canonical("no-colon", Map.of()));
    }

    @Test
    void dictionaryAssignsFirstSeenIndicesDeterministically() {
        var dict = new IdentityDictionary();
        assertEquals(0, dict.indexOf("minecraft:stone"));
        assertEquals(1, dict.indexOf("minecraft:dirt"));
        assertEquals(0, dict.indexOf("minecraft:stone"));   // repeat: stable
        assertEquals(2, dict.indexOf("minecraft:plains"));  // biomes share the table
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:plains"),
                dict.entries());
        assertEquals(3, dict.size());
    }

    @Test
    void dictionaryValidatesOnFirstInsertion() {
        var dict = new IdentityDictionary();
        assertThrows(IllegalArgumentException.class, () -> dict.indexOf("not an identity"));
        assertTrue(dict.entries().isEmpty(), "rejected identity must not be retained");
    }
}
