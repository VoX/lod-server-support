package dev.vox.lss.common.config;

import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The {@code /lsslod set} key registry (v0.11.0 stage C + the R-9 far-player rows at E1,
 * runtime-settings-commands-plan.md as amended by the mega plan's R-2/R-5) — shared by
 * both command surfaces so Fabric/Paper cannot drift (the HandshakeGate parity
 * precedent).
 *
 * <p>Each row carries a PER-KEY CLAMP FUNCTION — the exact static helpers
 * {@link ServerConfigBase#validate()} calls, never a bare (min,max) band (the R-2
 * registry clamp rule: a naive band would turn {@code set dirtyBroadcastIntervalSeconds
 * 0} into 1 s — re-breaking DIRTY0 through the command surface — and {@code set
 * maxConcurrentDiskReads 0} into K=1 instead of AUTO).
 *
 * <p>The apply sequence the command surfaces run (the plan's threading fix): parse →
 * clamp on a scratch value and assign the final post-clamp value ONCE (Paper's ingress
 * and handshake paths read config off-pump, so a raw-assign-then-validate window could
 * leak one out-of-band read) → {@code config.validate()} (idempotent, pinned; performs
 * the cross-field work) → {@code config.save()} → reply with the post-validate value
 * read back through {@link SettingKey#current}.
 */
public final class RuntimeSettings {

    /** One settable key: parse+clamp+assign in {@code apply} (throws
     *  IllegalArgumentException on a malformed value), current-value render, and the
     *  human "when it applies" note for the command reply. */
    public record SettingKey(String name,
                             Function<ServerConfigBase, String> current,
                             BiFunction<ServerConfigBase, String, Void> apply,
                             String applyNote) {}

    private static int parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not an integer: '" + raw + "'");
        }
    }

    /** Strict boolean parse for the registry's boolean rows: only "true"/"false" —
     *  Boolean.parseBoolean's everything-else-is-false would make a typo a silent
     *  disable at the command line. */
    private static boolean parseBoolean(String raw) {
        raw = raw.trim();
        if ("true".equalsIgnoreCase(raw)) return true;
        if ("false".equalsIgnoreCase(raw)) return false;
        throw new IllegalArgumentException("expected true or false, got '" + raw + "'");
    }

    private static double parseDouble(String raw) {
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: '" + raw + "'");
        }
        if (!Double.isFinite(v)) throw new IllegalArgumentException("not finite: '" + raw + "'");
        return v;
    }

    private static final List<SettingKey> KEYS = List.of(
            new SettingKey("lodDistanceChunks",
                    c -> String.valueOf(c.lodDistanceChunks),
                    (c, raw) -> {
                        c.lodDistanceChunks = ServerConfigBase.clampLodDistance(parseInt(raw));
                        return null;
                    },
                    "applies now server-side; current-protocol clients get a config re-push"
                            + " (legacy clients update on rejoin — a SHRINK leaves them"
                            + " re-asking beyond the new distance until rejoin); the AUTO"
                            + " timestamp cache stays at boot sizing until restart"),
            new SettingKey("generationConcurrencyLimitGlobal",
                    c -> String.valueOf(c.generationConcurrencyLimitGlobal),
                    (c, raw) -> {
                        c.generationConcurrencyLimitGlobal =
                                ServerConfigBase.clampGenGlobal(parseInt(raw));
                        return null;
                    },
                    "applies to new generation admissions within a tick; in-flight"
                            + " generations are never cancelled; lowering the global also"
                            + " drags the per-player cap down to it"),
            new SettingKey("generationConcurrencyLimitPerPlayer",
                    c -> String.valueOf(c.generationConcurrencyLimitPerPlayer),
                    (c, raw) -> {
                        c.generationConcurrencyLimitPerPlayer = ServerConfigBase
                                .clampGenPerPlayer(parseInt(raw), c.generationConcurrencyLimitGlobal);
                        return null;
                    },
                    "applies to new admissions within a tick, including existing sessions"),
            new SettingKey("mbPerSecondLimitPerPlayer",
                    c -> String.valueOf(c.mbPerSecondLimitPerPlayer),
                    (c, raw) -> {
                        c.mbPerSecondLimitPerPlayer =
                                ServerConfigBase.clampMbPerPlayer(parseDouble(raw));
                        return null;
                    },
                    "applies immediately (read per tick at the flush); counts RAW bytes,"
                            + " before wire compression"),
            new SettingKey("mbPerSecondLimitGlobal",
                    c -> String.valueOf(c.mbPerSecondLimitGlobal),
                    (c, raw) -> {
                        c.mbPerSecondLimitGlobal =
                                ServerConfigBase.clampMbGlobal(parseDouble(raw));
                        return null;
                    },
                    "applies within a tick (the shared limiter reconfigures at the next"
                            + " tick); counts RAW bytes, before wire compression"),
            new SettingKey("dirtyBroadcastIntervalSeconds",
                    c -> String.valueOf(c.dirtyBroadcastIntervalSeconds),
                    (c, raw) -> {
                        c.dirtyBroadcastIntervalSeconds =
                                ServerConfigBase.clampDirtyBroadcastInterval(parseInt(raw));
                        return null;
                    },
                    "applies at the next broadcast tick; 0 DISABLES dirty pushes (the"
                            + " server-side invalidation drain keeps running; clients then"
                            + " refresh only on rejoin or their own re-requests)"),
            new SettingKey("maxConcurrentDiskReads",
                    c -> String.valueOf(c.maxConcurrentDiskReads),
                    (c, raw) -> {
                        c.maxConcurrentDiskReads =
                                ServerConfigBase.clampMaxConcurrentDiskReads(parseInt(raw));
                        return null;
                    },
                    "applies within a tick; 0 = AUTO (half the reader pool with the store"
                            + " on, the whole pool with it off); lowering lets in-flight"
                            + " reads finish"),
            // The ping backstop's live A/B lever (adaptive-transfer-rate-plan.md —
            // the registry's first BOOLEAN row; decision recorded in the plan's
            // review log). Strict parse: only "true"/"false".
            new SettingKey("enablePingBackstop",
                    c -> String.valueOf(c.enablePingBackstop),
                    (c, raw) -> {
                        c.enablePingBackstop = parseBoolean(raw);
                        return null;
                    },
                    "applies within a tick; false also resets any live per-player cut"
                            + " back to full allocation"),
            // Send pacing's live A/B lever (send-pacing-plan.md v2).
            new SettingKey("enableSendPacing",
                    c -> String.valueOf(c.enableSendPacing),
                    (c, raw) -> {
                        c.enableSendPacing = parseBoolean(raw);
                        return null;
                    },
                    "applies within a tick; spreads LOD send bursts toward the cap's"
                            + " per-tick rate so game packets interleave (never paces"
                            + " below the configured cap)"),
            // R-9 (E1): the privacy keys an admin answering a complaint must not need a
            // restart for. farPlayers is the registry's one STRING-typed row — a strict
            // parse rejects garbage at the command line, then the value routes through
            // the EXACT validate() helper (identity for valid forms — the R-2 rule).
            new SettingKey("farPlayers",
                    c -> c.farPlayers,
                    (c, raw) -> {
                        c.farPlayers = ServerConfigBase.clampFarPlayersMode(
                                parseFarPlayersMode(raw));
                        return null;
                    },
                    "applies at the next broadcast tick; off disables far players,"
                            + " opt-in serves only players whose own client opted in,"
                            + " on serves everyone minus the exclude list"),
            new SettingKey("farPlayersMaxDistanceBlocks",
                    c -> String.valueOf(c.farPlayersMaxDistanceBlocks),
                    (c, raw) -> {
                        c.farPlayersMaxDistanceBlocks =
                                ServerConfigBase.clampFarPlayersMaxDistance(parseInt(raw));
                        return null;
                    },
                    "applies at the next broadcast tick; each client's own preference"
                            + " intersects it"),
            // The service gate's rollout lever (service-permission-gate-plan.md §2.5) —
            // strict boolean, no clamp (R-2: boolean rows carry none). Disarming leaves
            // the grant sweep draining the denied-session memo, so no player stays dark.
            new SettingKey("requireServicePermission",
                    c -> String.valueOf(c.requireServicePermission),
                    (c, raw) -> {
                        c.requireServicePermission = parseBoolean(raw);
                        return null;
                    },
                    "current-protocol sessions are re-checked within ~20 s (legacy"
                            + " clients heal at rejoin); denied players are re-offered when"
                            + " granted or when the gate is disarmed"));

    private static String parseFarPlayersMode(String raw) {
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!v.equals("off") && !v.equals("opt-in") && !v.equals("optin")
                && !v.equals("opt_in") && !v.equals("on")) {
            throw new IllegalArgumentException("not a far-player mode: '" + raw
                    + "' (off | opt-in | on)");
        }
        return v;
    }

    public static List<SettingKey> keys() {
        return KEYS;
    }

    public static SettingKey byName(String name) {
        for (var k : KEYS) {
            if (k.name().equalsIgnoreCase(name)) return k;
        }
        return null;
    }

    /** The tab-completion / usage list, registry-derived so it cannot drift. */
    public static List<String> keyNames() {
        return KEYS.stream().map(SettingKey::name).toList();
    }

    /**
     * The full apply sequence minus the platform-specific reply/re-push: parse+clamp+
     * assign-once via the row, then validate() (cross-field work) and save(). Returns
     * the post-validate effective value. Throws IllegalArgumentException on a malformed
     * value (nothing assigned).
     */
    public static String applyAndPersist(ServerConfigBase config, SettingKey key, String rawValue) {
        key.apply().apply(config, rawValue);
        config.validate();
        config.save();
        return key.current().apply(config);
    }

    /** The reply's "(clamped from 'raw')" suffix, or "" when the value was accepted
     *  as given. Numeric-aware (review F3): "25" rendering back as "25.0" is a formatting
     *  difference, not a clamp — compare parsed values when both sides parse. */
    public static String clampedSuffix(String effective, String rawValue) {
        String raw = rawValue.trim();
        if (effective.equals(raw)) return "";
        try {
            if (Double.parseDouble(effective) == Double.parseDouble(raw)) return "";
        } catch (NumberFormatException ignored) {
            // non-numeric render: fall through to the string verdict
        }
        return " (clamped from '" + raw + "')";
    }

    /** The no-args `set` listing: one "key = value" line per registry row. */
    public static List<String> listLines(ServerConfigBase config) {
        return KEYS.stream()
                .map(k -> k.name() + " = " + k.current().apply(config))
                .toList();
    }

    private RuntimeSettings() {}

    static {
        // Registry hygiene: duplicate names would make byName order-dependent.
        assert KEYS.stream().map(k -> k.name().toLowerCase(Locale.ROOT)).distinct().count()
                == KEYS.size();
    }
}
