package dev.vox.lss.paper;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link PaperSoakBridge}'s never-throw contract — the one line of dev-harness glue
 * that runs inside every production onEnable. Two halves:
 *
 * <ul>
 *   <li>Gate: with {@code -Dlss.soak.scenario} unset OR blank there must be zero reflective
 *       activity. Blank is the production-relevant arm: runSoakServer always defines the
 *       property ({@code findProperty('soak.scenario') ?: ''}), so a plain run hands the
 *       bridge an empty string. A broken gate is detectable because the driver init then
 *       explodes (it re-reads the property itself) and the bridge logs the containment row
 *       this test asserts is absent.</li>
 *   <li>Containment: with the property set and the driver init throwing (unreadable scenario
 *       path — the real PaperSoakScenarioDriver throws IllegalStateException before touching
 *       the plugin, so a null plugin is safe), onEnable must proceed: init returns normally
 *       and the failure is logged, not rethrown.</li>
 * </ul>
 *
 * <p>The ClassNotFoundException warn branch (release jar without the soak package) is not
 * reachable here: the soak classes are on the test classpath by construction.
 */
class PaperSoakBridgeTest {

    private static final String PROPERTY = "lss.soak.scenario";

    private LssLogCapture capture;
    private String savedProperty;

    @BeforeEach
    void setUp() {
        savedProperty = System.getProperty(PROPERTY);
        System.clearProperty(PROPERTY);
        capture = new LssLogCapture();
    }

    @AfterEach
    void tearDown() {
        capture.close();
        if (savedProperty == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, savedProperty);
        }
    }

    @Test
    void unsetPropertyMeansZeroReflectiveActivity() {
        // null plugin: if the gate ever let the driver init run, it would explode and the
        // bridge would log the containment row — so "no rows at all" pins the early return.
        assertDoesNotThrow(() -> PaperSoakBridge.init(null));
        assertEquals(List.of(), capture.rows(),
                "property unset must return before any reflective/driver activity");
    }

    @Test
    void blankPropertyMeansZeroReflectiveActivity() {
        System.setProperty(PROPERTY, "");
        assertDoesNotThrow(() -> PaperSoakBridge.init(null));
        assertEquals(List.of(), capture.rows(),
                "runSoakServer without -Psoak.scenario sets the property to '' — the isBlank arm is load-bearing");
    }

    @Test
    void explodingDriverIsCaughtAndLoggedAndOnEnableProceeds(@TempDir Path tmp) {
        System.setProperty(PROPERTY, tmp.resolve("missing-scenario.json").toString());
        assertDoesNotThrow(() -> PaperSoakBridge.init(null),
                "a throwing soak driver must never brick onEnable");

        var errors = capture.rows().stream().filter(r -> r.level() == Level.ERROR).toList();
        assertEquals(1, errors.size(), "the failure is contained AND logged, never silent");
        assertTrue(errors.get(0).message().contains("Failed to initialize soak scenario driver"),
                "unexpected containment row: " + errors.get(0).message());
        assertNotNull(errors.get(0).thrown(), "the driver's exception is attached to the log row");
    }

    // ---- log capture (LSSLogger is static-final; observing 'logged' must hook the backend) ----

    private record LogRow(Level level, String message, Throwable thrown) {}

    /**
     * Captures rows logged through {@code LSSLogger} by attaching a synchronous appender to
     * the log4j root logger config (the Paper dev bundle binds SLF4J to log4j-core). Appender
     * refs added programmatically to the root LoggerConfig are invoked on the logging thread,
     * so no async flush is needed before asserting.
     */
    private static final class LssLogCapture extends AbstractAppender implements AutoCloseable {
        private final List<LogRow> rows = new CopyOnWriteArrayList<>();
        private final LoggerContext ctx;

        LssLogCapture() {
            super("lss-soak-bridge-test-capture", null, null, true, Property.EMPTY_ARRAY);
            start();
            ctx = (LoggerContext) LogManager.getContext(false);
            ctx.getConfiguration().getRootLogger().addAppender(this, Level.ALL, null);
            ctx.updateLoggers();
        }

        @Override
        public void append(LogEvent event) {
            if ("LSS".equals(event.getLoggerName())) {
                rows.add(new LogRow(event.getLevel(), event.getMessage().getFormattedMessage(),
                        event.getThrown()));
            }
        }

        List<LogRow> rows() {
            return rows;
        }

        @Override
        public void close() {
            ctx.getConfiguration().getRootLogger().removeAppender(getName());
            ctx.updateLoggers();
            stop();
        }
    }
}
