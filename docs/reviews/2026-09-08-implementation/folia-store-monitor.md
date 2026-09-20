Corrected full-chain result: **PASS**, root session10111 exit0. Initial rejected attempt is retained below; corrected evidence is in the final section.

# Folia store monitor — initial attempt 2026-09-08

Status: NOT ACCEPTED. The initial auto-prime has invalid setup commands; the parent was notified promptly. This report records the initial attempt and read-only diagnosis, not a final warm-store pass. Parent owns session64309. Monitor has launched nothing, edited no source/runtime fixture, and intervened in no process.

## Actual runtime identity and ancestry

Root log: `262-folia-store-second-join.log`. Outer shell856160 → xvfb856166 → soak856179 → owned prerequisite856189 → fresh-backfill soak856190. Prerequisite owned server856811 and client861254. Runtime directory: `/home/vox/projects/lss-lines/26.2/paper/build/run/folia-soak-server`; planned collected prerequisite `soak-results/fresh-backfill-folia-20260908T224348Z`.

Actual `logs/latest.log:1–2`: Java25.0.2+10 Temurin and Folia26.2-7-ver/26.2.x@14b7fee (2026-08-25T13:29:28Z), MC26.2. `:17` repeats actual server/API26.2.build.7-beta; `:22` says EDF scheduler with initial2 target threads. `:28` loads LodServerSupport v0.14.0; `:47` enables it; `:65` LOD Server Support (Paper) enabled. `:80–81` actual player handshake protocolv20/capabilities3 and request registration. A Fabric client is expected with a Folia server.

## Invalid prerequisite discovered live

At local18:44:30–31, every one of five gamerule setup commands failed parsing in all three dimension fan-out calls (`latest.log:82–115`). The legacy arguments were randomTickSpeed, doMobSpawning, mobGriefing, doFireTick, doDaylightCycle. Teleport did succeed (`:117–118`). The exporter nevertheless records each rejected command with `ok:true`; these actual rows are in current server.jsonl.

The client subsequently converged to2161 columns/in-flight0/ingest-failures0; generation2115/2115, zero timeouts, disk2144/2144, zero errors. These observations show useful ordinary ingress behavior, but do not repair the rejected setup premise and are NOT accepted store or quiescence evidence.

## Source diagnosis and exact per-line adaptation

Both Fabric (`fabric/src/main/java/dev/vox/lss/benchmark/SoakScenarioDriver.java:142–154`) and Paper/Folia (`paper/src/main/java/dev/vox/lss/paper/soak/PaperSoakScenarioDriver.java:162–180`, `:205–219`) pass scenario commands without gamerule-name adaptation. `ok` deliberately means did-not-throw, and `performPrefixedCommand` returns void while handling parser errors internally. Paper fans gamerules out using execute-in per dimension. `scripts/soak_report.py:318` makes no-observable-effect setup commands informational, not anomalies. `scripts/check_soak.py` has no general successful gamerule-state check; selected scenario checks on ok cannot detect these parser failures.

Actual cached mapped MC GameRules.class, inspected read-only in all three modern jars, registers the modern names on1.21.11,26.1,26.2. Vanilla GameRuleRegistryFix explicitly maps legacy fields and proves the fire value transformation: doFireTick=false → fire_spread_radius_around_player=0. Value -1 means unrestricted distance, not disabled. Correct modern commands:

- gamerule minecraft:random_tick_speed 0
- gamerule minecraft:spawn_mobs false
- gamerule minecraft:mob_griefing false
- gamerule minecraft:fire_spread_radius_around_player 0
- gamerule minecraft:advance_time false

Smallest correction is modern-line scenario JSON adaptation, shared by Fabric/Paper/Folia; preserve1.21.1 and1.21.10 legacy command names. Each line currently has33 JSON files with legacy gamerules:32 randomTickSpeed,32 doMobSpawning,31 mobGriefing,32 doFireTick,32 doDaylightCycle, plus4 spawnChunkRadius. Modern GameRuleRegistryFix removes spawnChunkRadius and GameRules has no replacement registration: the obsolete step in stamp-heal-rejoin,stamp-heal-prime,dirty-while-offline-summary,warm-rejoin-summary requires removal with documented modern spawn-ticket semantics/control, not an invented alias. This final removal recommendation needs the runtime spawn-ticket premise audited before acceptance.

For a narrow harness regression, parse every actual command against the real dispatcher (actual26.2 Commands exposes validateParseResults/getParseException) and fail on rejected syntax. Preserve intentionally mapped Folia save-all no-op. Parsing establishes syntax only; gamerule-specific value readback after execution pins the setup semantics. If command result callbacks are used, distinguish success boolean from result integer: setting0 can succeed with result0. Bukkit dispatchCommand recognition is not semantic success. Neither this audit nor the existing checker proves arbitrary command effects.

Actual cached references: `/home/vox/.gradle/caches/fabric-loom/{26.2,26.1}/minecraft-merged.jar`; named1.21.11 jar under `fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/`. Source/API evidence came from zip class constants and javap; no downloads, builds, runtime commands or regenerations.

## Scope

Folia remains experimental. Even a corrected checker-green store-second-join is single-player evidence, with a mid-session clearcache/re-declaration; it is not concurrent multi-region ingress, arbitrary lifecycle races, or graphics validation. Current attempt is invalid pending corrected setup and a new acceptance run. Final exit/cleanup/archival disposition is parent-owned and will be appended when known.

## Final disposition of this attempt and subsequent correction

Parent sent TERM to outer soak856179; session64309 returned143. All recorded owned
descendants exited; regular server489815 remained. Exact logs/exports/client logs and
identity/exit/hash receipts are preserved in `folia-rejected-first/`. No base-folia
existed after termination, so no baseline needed moving. This attempt remains NOT
ACCEPTED and never reached a valid warm-store verdict.

The bounded follow-up correction is now implemented on all five lines. Actual-tree
testing expanded the initial diagnosis:1.21.10 retains legacy rule names but already
lacks spawnChunkRadius/START, so its four obsolete steps are removed too. Only1.21.1
retains that rule. The corrected executor/checker, independent review, exact failures
and focused green gates are recorded in the per-line
`docs/reviews/2026-09-08-implementation/soak-command-validation.md` and the external
`soak-command-*` artifacts. No corrected soak was launched by this subagent.

## Corrected full chain — ACCEPTED, root session10111 exit0

The corrected auto-prime plus store scenario completed; parent confirmed outer session10111
exit0. Root launched the run; this subagent only monitored/read artifacts and wrote this
report. No builds, servers, runtime mutations, process signals or git-index operations
were performed by this monitor. This corrected run used the committed command validation
fix,26.2 commit98b67abc. The rejected first attempt above remains preserved and unaccepted.

| Phase | Result directory under26.2/soak-results | Checker windows / client-laws / quiescent | Verdict |
|---|---|---|---|
| Fresh auto-prime | fresh-backfill-folia-20260908T232131Z |40 /39 /40|PASS,0violations,0warnings|
| Warm store | store-second-join-folia-20260908T232618Z |29 /27 /29|PASS,0violations,0warnings|

Root's complete wrapper log is `runtime/folia-store-corrected.log`. Exact row proofs,
verdicts and SHA-256 hashes of every collected phase artifact are saved in
`runtime/folia-store-corrected-proof.json`.

### Real loaded identity

Both phases ran Folia26.2-7-ver/26.2.x@14b7fee (2026-08-25T13:29:28Z), API26.2.build.7-beta,
on Temurin Java25.0.2+10. Startup enabled LodServerSupport v0.14.0 and the actual player
completed the v20/capabilities3 handshake. The initial scheduler target was2 threads.
Collected prerequisite `server.log:22–23` gives Java/Folia identity; `:56` loads the plugin.
Warm-phase `server.log:54` likewise loads LodServerSupport0.14.0.

Observed real JVM arguments load `/home/vox/.gradle/caches/run-task-jars/folia/jars/26.2/7.jar`
with `-add-plugin=/home/vox/projects/lss-lines/26.2/paper/build/libs/lss-paper-soak.jar`.
This is the intended development plugin variant, retaining the excluded harness helper;
its plugin.yml identifies LodServerSupport0.14.0, API26.2, folia-supported:true. The server
jar SHA-256 is128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3;
the loaded dev plugin SHA-256 isc2895671fead6e9847338d9a73f016ab511273f38b1b711b992fbd8b5fbac12a.
The client was the real Fabric soak client, as designed for this server platform.

### Exact setup semantics

Each phase has exactly5 gamerule command rows with `ok:true` and
`validation:gamerule-readback`: random_tick_speed0, spawn_mobs=false, mob_griefing=false,
fire_spread_radius_around_player0, advance_time=false, all using minecraft identifiers.
The actual console logs show successful setters AND matching queries three times per
rule, corresponding to the preserved per-dimension fan-out. Collected prerequisite
`server.log:143–184` and warm-phase `server.log:128–163` contain these acknowledgements.
There were no parser-rejection or failed-validation rows. This closes the specific
Paper/Folia per-world readback integration gap from the source/test correction.

Generic teleport remains labeled validation=dispatch; the actual teleport/movement and
scenario convergence provide its effect evidence. Prerequisite save-all flush is
explicitly `validation=folia-noop,mapped:true` (`server.log:190`), not a claimed executed
save. The existing aggressive autosave plus actual Folia shutdown save supports staging.

### Fresh auto-prime and persistence

The initial world did not exist, so the wrapper actually auto-primed and saved a new
base-folia after its checker passed. Final generation2112submitted/completed,0timeouts,
0active; disk2144submitted/completed,2144not-found,0errors/pending. Client2161received,
0NOT_GENERATED,0in-flight,0ingest failures. Server JSONL has64valid rows ending `end`;
client52rows ends `disconnect`; CPU270rows parse. This phase's store counters remain0
because it is the fresh-backfill prerequisite, not the warm-store leg.

### Warm-store admission and parity

Exactly one clearcache action fired at wallMs1788910061532 (+60s). The immediately
preceding server snapshot at1788910061337 records2144deposits,0store hits,2144disk reads,
zero store/disk errors. The final snapshot at1788910149563 records2125store hits and
still2144disk reads: the repeat request wave added2125SQLite hits and **0region reads**.
Deposits stay2144; store queue/errors/deposit drops0; generation all0.

The two real served-byte probes (`20:0`, `-20:0`) both remain
-8582001278133590983 across the NBT-served and store-served waves. Client final4288columns
means two complete2144column waves, with queued/in-flight/ingest failures0. The checker
requires the re-download wave, enough pre-action deposits/store hits, bounded new disk
reads, non-vacuous probe parity and final quiescence; all passed without warnings.
This proves the armed probes' parity, not an independently hashed comparison of every row.

Warm server JSONL has42valid rows ending `end`; client32rows ends `disconnect`; CPU165rows
parse. After process exit, a read-only immutable SQLite open (WAL absent) returned
quick_check=ok; database size671744bytes; schema4/wire20 metadata;2144lods_1 rows, allwirefmt20.
The initial inspection used an incorrect diagnostic column spelling (`wire_format`);
PRAGMA table_info showed wirefmt, and the corrected read-only query above succeeded.
No database content was changed by inspection.

### Lifecycle, ancestry and scope

Observed outer chain1441270→xvfb1441276→soak1441289→owned auto-prime1441299→soak1441300.
Prerequisite owned server1441921 (actual JVM1442234) and client1448008 exited before the
warm phase. Warm owned server2223695 (wrapper JVM2223697, actual server2223970) and
client2226080 (wrapper JVM2226197) likewise exited. At final audit every recorded PID in
those chains was gone. The unrelated regular server489815, parent489750, remained alive.
Both collected server logs show orderly LSS service stop/plugin disable: prerequisite
:196–197; warm:172–173, followed by Folia region shutdown saves. Parent confirmed exit0
before starting unrelated GUI work.

This is representative **single-player** Folia acceptance. The named store-second-join
scenario validates a mid-session cache clear/redeclaration, not two login sessions in
its warm phase. It exercises ordinary startup, serving and shutdown; it does not force
session-replacement/shutdown races or concurrent multi-region ingress. Folia remains
experimental, and neither this pass nor the first attempt changes that label. No all-air
migration, mask-sweep interruption, or graphical rendering claim is inferred here.
