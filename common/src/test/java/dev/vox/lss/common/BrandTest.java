package dev.vox.lss.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the runtime display-branding token. The whole design rests on one property: an
 * un-repackaged jar (and every test) defaults to LOD Server Support / LSS, so the Voxy
 * Server Side repackage is the ONLY thing that changes any string, and it changes only
 * display + local command text — never the wire.
 */
class BrandTest {

    @AfterEach
    void resetToDefault() {
        // Brand is a process-global; restore the LSS defaults so no other test observes
        // a VSS value (the default IS what every other assertion in the suite relies on).
        Brand.apply("LSS", "LOD Server Support", "lss", "lsslod");
    }

    @Test
    void defaultsAreLodServerSupport() {
        assertEquals("LSS", Brand.shortName());
        assertEquals("LOD Server Support", Brand.displayName());
        assertEquals("lss", Brand.clientCommand());
        assertEquals("lsslod", Brand.serverCommand());
    }

    @Test
    void applySetsEveryFieldTheVssRepackageRewrites() {
        Brand.apply("VSS", "Voxy Server Side", "vss", "vsslod");
        assertEquals("VSS", Brand.shortName());
        assertEquals("Voxy Server Side", Brand.displayName());
        assertEquals("vss", Brand.clientCommand());
        assertEquals("vsslod", Brand.serverCommand());
    }

    @Test
    void nullOrBlankValuesKeepTheCurrentBranding() {
        Brand.apply("VSS", "Voxy Server Side", "vss", "vsslod");
        Brand.apply(null, "  ", null, "");
        assertEquals("VSS", Brand.shortName(), "a missing value must not wipe branding to empty");
        assertEquals("Voxy Server Side", Brand.displayName());
        assertEquals("vss", Brand.clientCommand());
        assertEquals("vsslod", Brand.serverCommand());
    }

    @Test
    void loadReadsBrandPropertiesFromTheClassLoader() {
        var props = "shortName=VSS\ndisplayName=Voxy Server Side\nclientCommand=vss\nserverCommand=vsslod\n";
        var loader = new ClassLoader(null) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return Brand.RESOURCE.equals(name)
                        ? new ByteArrayInputStream(props.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        : null;
            }
        };
        Brand.load(loader);
        assertEquals("VSS", Brand.shortName());
        assertEquals("Voxy Server Side", Brand.displayName());
        assertEquals("vss", Brand.clientCommand());
        assertEquals("vsslod", Brand.serverCommand());
    }

    @Test
    void loadWithoutTheResourceKeepsTheLssDefaults() {
        Brand.load(new ClassLoader(null) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return null; // a jar with no brand.properties (or a stripped classpath)
            }
        });
        assertEquals("LSS", Brand.shortName(), "a missing resource must leave the LSS defaults intact");
    }
}
