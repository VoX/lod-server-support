package dev.vox.lss.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cacheAddressAliases} load validation (plan §2.2 as amended by the §11 fold):
 * a malformed group drops WHOLE from the WORKING set at {@code validate()}, while the
 * user's FIELD is deliberately NEVER rewritten — the load-time re-save must not erase
 * the user's groups from their file; session code re-parses through {@code validated()}
 * with a silent sink. GSON-null shapes restore the empty default.
 */
class ClientAliasConfigValidationTest {

    @Test
    void aNullFieldRestoresTheEmptyDefault() {
        var c = new LSSClientConfig();
        c.cacheAddressAliases = null;
        c.validate();
        assertEquals(List.of(), c.cacheAddressAliases);
    }

    @Test
    void malformedGroupsWarnButTheUsersFieldIsNeverRewritten() {
        // Panel fix: the config re-saves on load, so rewriting the field to the
        // survivors would permanently ERASE the user's dropped groups from their file
        // with one scrolled warning as the only trace. The field stays verbatim;
        // session code re-validates it silently.
        var c = new LSSClientConfig();
        var original = List.of(
                List.of("good.example.com", "alt.good.example.com"),
                List.of("bad.example.com:25565", "alt.bad.example.com"));
        c.cacheAddressAliases = new ArrayList<>();
        for (var g : original) c.cacheAddressAliases.add(new ArrayList<>(g));
        c.validate();
        assertEquals(original, c.cacheAddressAliases,
                "the user's groups — including the rejected one — stay in their file");
    }

    @Test
    void theDefaultsCarryNoGroupsAndSubBucketsOn() {
        var c = new LSSClientConfig();
        c.validate();
        assertEquals(List.of(), c.cacheAddressAliases);
        assertTrue(c.useWorldSubBuckets,
                "the world axis ships ON — (address, seed) can only be finer than any "
                        + "address-derived consumer partition (plan §2.3)");
    }

    @Test
    void validateIsIdempotentOnACleanField() {
        var c = new LSSClientConfig();
        c.cacheAddressAliases = new ArrayList<>(List.of(
                new ArrayList<>(List.of("a.example.com", "b.example.com"))));
        c.validate();
        var afterFirst = List.copyOf(c.cacheAddressAliases);
        c.validate();
        assertEquals(afterFirst, c.cacheAddressAliases,
                "validate() re-runs on every load — a clean field must pass unchanged");
    }

}
