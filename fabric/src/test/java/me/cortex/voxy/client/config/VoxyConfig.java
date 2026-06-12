package me.cortex.voxy.client.config;

/**
 * Test stub of Voxy's config, mirroring the exact shape VoxyCompat reflects: public static
 * field {@code CONFIG} plus public instance {@code float sectionRenderDistance}. VoxyCompat
 * converts to chunks via {@code Math.round(sectionRenderDistance * 32)} and re-reads the
 * live field on every query — mutate {@link #CONFIG} directly (the SpiralScanner distance
 * tests rely on this); call {@link #reset()} between tests.
 */
public class VoxyConfig {

    /** The instance VoxyCompat's reflected static getter reads. */
    public static VoxyConfig CONFIG = new VoxyConfig();

    /** Default 0 maps to a 0-chunk distance, which the scanner's min-ladder ignores (voxy>0 only). */
    public float sectionRenderDistance = 0f;

    public static void reset() {
        CONFIG = new VoxyConfig();
    }
}
