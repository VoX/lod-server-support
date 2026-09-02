package dev.vox.lss.common.config;

import dev.vox.lss.common.LSSConstants;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                        applyLodDistance(c, raw);
                        return null;
                    },
                    "applies now server-side; current-protocol clients get a config re-push"
                            + " (legacy clients update on rejoin — a SHRINK leaves them"
                            + " re-asking beyond the new distance until rejoin); the AUTO"
                            + " timestamp cache stays at boot sizing until restart; "
                            + "`<world> <n>` sets a per-world override (Paper: Bukkit "
                            + "world name, Fabric/NeoForge: dimension id) and "
                            + "`<world> default` clears it"),
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

    /** Result of {@link #applyAndPersist}. {@code display} is the value text as the row
     *  produces it: the BARE effective value for most keys, or the fully-rendered
     *  per-world text (own clamp note baked) for lodDistanceChunks — use
     *  {@link #renderReplyValue} to turn it into a reply, so the bare/baked split lives in
     *  ONE place, not duplicated across the platform command surfaces. {@code repush} is
     *  whether the apply owes a live SessionConfig re-push: only a real lodDistance
     *  mutation (scalar OR a per-world put/remove) sets it, and the command surfaces
     *  re-push on THIS, never on a reply-string comparison (which a per-world set, whose
     *  scalar is unchanged, would silently fail). */
    public record ApplyResult(String display, boolean repush) {}

    /**
     * The full apply sequence minus the platform-specific reply/re-push: parse+clamp+
     * assign-once via the row, then validate() (cross-field work) and save(). Returns
     * the reply's value text plus whether a re-push is owed. Throws
     * IllegalArgumentException on a malformed value (nothing assigned).
     */
    public static ApplyResult applyAndPersist(ServerConfigBase config, SettingKey key, String rawValue) {
        boolean isLod = key.name().equals("lodDistanceChunks");
        int beforeScalar = isLod ? config.lodDistanceChunks : 0;
        Map<String, Integer> beforeMap = isLod ? snapshotByWorld(config) : null;
        key.apply().apply(config, rawValue);
        config.validate();
        config.save();
        if (isLod) {
            boolean repush = beforeScalar != config.lodDistanceChunks
                    || !beforeMap.equals(snapshotByWorld(config));
            return new ApplyResult(lodDistanceDisplay(config, rawValue), repush);
        }
        return new ApplyResult(key.current().apply(config), false);
    }

    /** The reply's value text: lodDistanceChunks bakes its own per-world-aware clamp note
     *  into {@code display}, so it renders verbatim; every other key gets the pure-numeric
     *  {@link #clampedSuffix} appended here. ONE home for the split — both command surfaces
     *  call this, so they cannot drift. */
    public static String renderReplyValue(SettingKey key, ApplyResult result, String rawValue) {
        return key.name().equals("lodDistanceChunks")
                ? result.display()
                : result.display() + clampedSuffix(result.display(), rawValue);
    }

    private static Map<String, Integer> snapshotByWorld(ServerConfigBase config) {
        return new LinkedHashMap<>(config.lodDistanceChunksByWorld == null
                ? Map.of() : config.lodDistanceChunksByWorld);
    }

    /** The reply's "(clamped from 'raw')" suffix, or "" when the value was accepted
     *  as given. PURELY numeric (review F3): "25" rendering back as "25.0" is a
     *  formatting difference, not a clamp — compare parsed values when both sides parse.
     *  The per-world form does its own token-level clamp note in
     *  {@link #lodDistanceDisplay}, so this method stays per-world-agnostic. */
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

    /** The no-args {@code set} listing: one "key = value" line per registry row,
     *  plus one {@code lodDistanceChunks[world] = n} line per per-world override. */
    public static List<String> listLines(ServerConfigBase config) {
        List<String> lines = new ArrayList<>();
        for (var k : KEYS) {
            lines.add(k.name() + " = " + k.current().apply(config));
            if (k.name().equals("lodDistanceChunks") && config.lodDistanceChunksByWorld != null) {
                for (var e : config.lodDistanceChunksByWorld.entrySet()) {
                    lines.add("lodDistanceChunks[" + e.getKey() + "] = " + e.getValue());
                }
            }
        }
        return lines;
    }

    /**
     * {@code <n>} sets the default; {@code <world> <n>} sets a per-world override;
     * {@code <world> default} (also {@code clear}/{@code -}) removes it. Parse
     * happens BEFORE any assign so a malformed value leaves the config untouched
     * (the existing command-surface pin).
     */
    static void applyLodDistance(ServerConfigBase c, String raw) {
        String s = raw.trim();
        // Split at the LAST whitespace so the value is always the final token and a world
        // KEY may contain spaces (a Bukkit/Multiverse name like "my world" → key "my
        // world", value the trailing token). A single token is the scalar-default form.
        int split = lastWhitespace(s);
        if (split < 0) {
            c.lodDistanceChunks = ServerConfigBase.clampLodDistance(parseInt(s));
            return;
        }
        String world = s.substring(0, split).trim();
        String val = s.substring(split + 1).trim();
        if (world.isEmpty() || val.isEmpty()) {
            throw new IllegalArgumentException(
                    "expected <distance> or <world> <distance>");
        }
        if (world.length() > LSSConstants.MAX_DIMENSION_STRING_LENGTH) {
            throw new IllegalArgumentException("world key too long (max "
                    + LSSConstants.MAX_DIMENSION_STRING_LENGTH + " chars)");
        }
        Map<String, Integer> map = new LinkedHashMap<>(
                c.lodDistanceChunksByWorld == null ? Map.of() : c.lodDistanceChunksByWorld);
        if (isLodDistanceClearToken(val)) {
            map.remove(world);
        } else {
            map.put(world, ServerConfigBase.clampLodDistance(parseInt(val)));
        }
        c.lodDistanceChunksByWorld = map;
    }

    /** The reply's value text for a lodDistance apply: the scalar (with its numeric
     *  clamp note) for {@code <n>}, or {@code world=n} / {@code world=default} for the
     *  per-world forms, with a clamp note comparing the distance token only. All
     *  per-world formatting lives HERE, keeping {@link #clampedSuffix} pure-numeric. */
    static String lodDistanceDisplay(ServerConfigBase config, String rawValue) {
        String raw = rawValue.trim();
        int split = lastWhitespace(raw);
        if (split < 0) {
            String v = String.valueOf(config.lodDistanceChunks);
            return v + clampedSuffix(v, raw);
        }
        String world = raw.substring(0, split).trim();
        String val = raw.substring(split + 1).trim();
        if (isLodDistanceClearToken(val)
                || config.lodDistanceChunksByWorld == null
                || !config.lodDistanceChunksByWorld.containsKey(world)) {
            return world + "=default";
        }
        String eff = String.valueOf(config.lodDistanceChunksByWorld.get(world));
        return world + "=" + eff + clampedSuffix(eff, val);
    }

    private static boolean isLodDistanceClearToken(String val) {
        return val.equals("-") || val.equalsIgnoreCase("default")
                || val.equalsIgnoreCase("clear");
    }

    private static int lastWhitespace(String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private RuntimeSettings() {}

    static {
        // Registry hygiene: duplicate names would make byName order-dependent.
        assert KEYS.stream().map(k -> k.name().toLowerCase(Locale.ROOT)).distinct().count()
                == KEYS.size();
    }
}
