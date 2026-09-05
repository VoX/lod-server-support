# Xaero map drops under multi-player far generation — remediation plan (v2, 2026-09-05)

Status: v2 after the 1-Fable + 4-Opus panel (§7 is the record; every MAJOR is folded below).
**EXECUTING since 2026-09-05 on `feat/xaero-scatter` (main): WI-1a, WI-1b Option L, WI-3 (with the old
WI-4 folded in), WI-5 and WI-6 implemented — §8 is the as-built record; ports follow.** The user
assumed WI-0 positive ("this is a real bug that has been in for a while") and asked for the rest. Line references are the `main` (26.2) tree unless a line is named.

Provenance: the user's live observation (several players online, each triggering far-away chunk
generation 5000+ blocks apart; the Xaero World Map bridge recorded "a lot of dropped map chunks"),
two Fable investigations on 2026-09-05 (a five-line PARITY audit of the bridge and a MECHANISM trace
from generation through the dirty broadcast, the client re-declaration and the bridge to Xaero
1.45.0's own region loader, decompiled), the facts re-verified in the tree by hand, and the panel.

## 0. What we know

**F1 — The user's hypothesis is refuted on a generation-ENABLED server and CORRECT on a
generation-disabled one.** `ColumnStateMap.markDirtyIfKnown`
(`xplat/.../networking/client/ColumnStateMap.java:572-590`) re-marks a position only when the client
has SOME disposition for it: a stamp OR a `sessionSatisfied` bit (`:585`). A never-fetched position
has neither, so on a gen-enabled server another player's fresh generation cannot make this client
ask. But a position parked as NOT_GENERATED sits in `sessionSatisfied` with no stamp, and the
javadoc (`:565-570`) names the broadcast as "the ONE revival path" for it. NOT_GENERATED is emitted
only under `enableChunkGeneration=false` (`ServerConfigBase.java:258`, default true) or a hard
generation failure — so on a gen-DISABLED server the observer's whole far disc is parked, and player
B generating 5000 blocks away → first save → broadcast → revival → declared → served scattered
across B's trajectory. Exactly the user's mechanism. The first live question (§5) is therefore the
server's `enableChunkGeneration`.

**F2 — Generation does not re-broadcast; FIRST-OBSERVED SAVES do.** The dirty content filter marks
a save dirty when the hash of the chunk's serialized NATIVE section bytes differs from the stored
one (`DirtyContentFilter.observeSave`, `xplat/.../networking/server/DirtyContentFilter.java:111-135`;
the only callers are `ServerReceiverGlue.java:100/:102` off the `copyOf` RETURN hook, Fabric mixin
and NeoForge twin `lss.neoforge.mixins.json:16`). `storeHash` (`:170-181`) keeps hashes in an
in-memory `Long2LongOpenHashMap` with `defaultReturnValue(0)`, so an ABSENT hash always compares as
changed: every chunk's first save per server PROCESS marks dirty regardless of content. The only
seeder is the generation serve (`ChunkGenerationService.java:190-199`). The table is CLEARED
WHOLESALE at `MAX_ENTRIES_PER_DIMENSION = 512 * 1024` (`:45`, `:176-178`). Why merely-LOADED chunks
save at all (26.2, javap of the mapped jar): `incrementInhabitedTime` is a plain field add and does
NOT mark unsaved; the LOAD pyramid's light step (`ThreadedLevelLightEngine.lightChunk` →
`setLightCorrect(true)` → `markUnsaved` → `chunksToEagerlySave` → `saveChunkIfNeeded`, 10 s
cooldown) does — so every chunk a player loads saves once within ~10 s, bounded by LOADS, not by the
tick radius. `sectionLightChanged` also marks unsaved on neighbour relight. Moonrise/Starlight's
path (the live rig runs Moonrise) is unverified — same premise, different call chain.

**F3 — The storm is already RECORDED.** `soak-results/cold-restart-resync-*` (fresh-backfill world
+ client cache into a NEW server JVM — `soak.sh:497-501`, `:678-700`): `dirty.marked_total` 449 /
450, `suppressed_total` 0 / 1, `columns_sent` 184, client `columns.known` ~2150 → 20.8 % of the
known disc re-marked on a restart with NOTHING changed. `warm-rejoin`'s two client runs share ONE
server JVM (`soak.sh:622-655`) — its run-2 delta reads +659 marked / +786 suppressed / +1600 sent,
the warm-table shape. `check_cold_restart_resync` (`check_soak.py:2283`) currently BLESSES the
storm ("a bounded re-send wave is expected (first-save content-filter seeding in the new process)").
On real terrain neighbour relight at generation frontiers changes the hashed `DataLayer`s
(`SectionSerializer.java:64-66`), so genuine dirtiness of seeded chunks is LIKELY there.

**F4 — Broadcast fan-out and its server-side cost.** `DirtyColumnBroadcaster`
(`xplat/.../server/DirtyColumnBroadcaster.java:81-170`): the drain runs every
`dirtyBroadcastIntervalSeconds` (10); ALL drained positions are tscache-invalidated BEFORE the
player loop (`:105`; `OffThreadProcessor.java:812-830`), their store rows DELETED
(`ServerReceiverGlue.java:115`), and each player's `diskReadDone`/probe-suppress cleared; then the
positions go to every handshaken same-dimension player within Chebyshev `lodDistanceChunks`
(`:117-141`; raw config value, default 512 chunks = 8192 blocks — players 5000 blocks apart are all
in range of each other's chunks), filtered by RANGE ONLY, never by held set, paginated at 10240.
`dirtyBroadcastIntervalSeconds 0` silences the WIRE only — the drain and the invalidation still run
(`:88-95`, `:159`). A first-save storm therefore also wipes warm STORE rows for unedited chunks. The
counters `dirty.marked_total`/`suppressed_total` exist in the EXPORTER only
(`BenchmarkMetricsExporter.java:328-335`, gated behind `-Dlss.benchmark`/`-Dlss.soak.scenario`) —
`DiagnosticsFormatter` has no dirty line.

**F5 — The client's own walk is region-local; the dirty broadcast is the ONLY scatter carrier.**
The declared want-set is the complete prefix of the combined near-ring/region-spiral order
(`RegionScanner.java`); unsatisfied ungenerated positions hold the prefix at the observer's OWN
generation frontier, region by region, so the observer never declares B's area until the spiral
reaches it — that is the designed 1-2-active-regions shape the bridge handles. `reopenRing` is a
no-op (`:117`); needs bits carry marks; a sparse dirty scatter across many regions fits one
800-position batch (`region_span=` `:66-68`, ≤2 in normal fill). Hence the one-command live
discriminator: with `dirtyBroadcastIntervalSeconds 0` the scatter must vanish (WI-0).

**F6 — The bridge under scatter (corrected).** `XaeroMapCompat`
(`xplat/.../compat/XaeroMapCompat.java`): decode-thread `offerColumn` (`:797-840`) → insertion-
ordered `LinkedHashMap` queue (latest-wins does NOT reinsert, `:874-881`), caps `MAX_QUEUE` 8192 /
`MAX_QUEUE_BYTES` 48 MB (`:88-93`), evict-oldest (`:892-900`) — so retained AWAITING/DEFERRED entries
are permanently the OLDEST and go first. The pump is the client tick (`:318-321`), ≤
`MAX_COMMITS_PER_PUMP` 64 / `PUMP_NANOS_BUDGET` 2 ms (`:97-98`), bucketed per region with rotation
(`:1461-1585`); buckets are built from QUEUED BYTES only (`:1470-1481`) and `grantLoads` sees only
those (`:1609-1626`, largest-cluster-first `:1613-1618`, ≤ `MAX_OUTSTANDING_LOADS` 8, `:110`);
`requestRegionLoad` hard-throws off the main thread (`:1640-1646`). A region not loaded → AWAITING
(`:1782-1786`), cap-exempt; a region not resting → DEFERRED, cap-exempt (`:1552-1563`); a TILE
deferred past `DEFER_CAP` 200 is removed SILENTLY and deliberately unreported (`:1535-1546`,
`dropped_expired` — "heals by revisit or clearcache": no healing loop). Occupancy counts QUEUE
entries only (`:921-927`), reported as `6144 × min(1, occ/0.75)` (`:739-740`); no ≥5 % recession
from the in-window peak for 7 s → wedge (`:199-216`, `:749-777`) → −1 for 10 s. Reporting
(`reportDroppedIfGoverned`, `:938-943`): overflow evictions are REPORTED only while governed (halt
at 75 %, in-flight sends still landing) and SILENT while wedged or kill-switched; stale-dimension
drops always report. A report → `LSSApi.reportIngestFailure` (carries no stamp) → `onIngestFailed`
drops any stamp to −1 + retry (`ColumnStateMap.java:718-720`, `:804-811`) and counts a strike; the
FOURTH report parks (`:756-757`); a park is revived by `markDirtyIfKnown` (`:583-585`). Reports go
`mc.execute` per position (`ClientNetGlue.java:130-139`). Diag `describe()` (`:1081-1108`) already
carries `dropped_overflow/stale/expired/updates/unloaded`, `skipped_settings/native`,
`drops_reported`, `regions_waiting`, `load_requests`, `defer_events`; there are NO bridge fields in
the exporter. A tile is one CHUNK (`:1743-1748`: tileChunk = chunk>>2, region = tileChunk>>3, so a
region is 32×32 chunks = 1024 tiles).

**F7 — Xaero 1.45.0 itself (decompiled).** No distance-based unload. `MapLimiter` evicts the
oldest regions only above `MAX_LOADED_REGIONS` 300. A tile texture frees 1 s after its last visit
once `!isBeingWritten` (`LeafRegionTexture:58-69`); `beingWritten` clears only after a SAVE
(`MapSaveLoad:1332`; 60 s timer `:1066-1073`, ≤3 saves per pass), after which recache makes the
region NOT resting until the cache pass. Unlimited virgin loads, ONE file load per 100 ms pass
(`:1094`). `writeChunk` on a region that is not loaded is a silent no-op (`MapWriter:500-528`). The
test stubs under `fabric/src/test/java/xaero/map/` start created regions at loadState 2 via the
deliberate seam `MapProcessor.createdRegionLoadState` (`:38`; ~15 tests already set it to 0);
the real jar starts at 0; no limiter, no saver loop.

**F8 — Ranked causes of the observed drops (v2).**
1. *First-observed-save re-broadcast of HELD columns* (established world + the observer's cache —
   the user's situation). Per process, every chunk any other player LOADS saves once (F2): ~21 new
   chunks per chunk travelled at view distance 10 ≈ 340 columns/min walking, ~2300/min elytra, per
   player; each save the observer holds from an earlier session re-marks → re-asks along N far
   trajectories → each a disk read + ~30 KB send + a far Xaero write. RECORDED by
   cold-restart-resync (449 marked / 0 suppressed, F3). Signature: `dirty.marked_total ≫
   suppressed_total`, client `/lss trace` `dirty` events with large `n`, `region_span=` ≫ 2.
2. *NOT_GENERATED revival by others' generation* — CONDITIONAL on `enableChunkGeneration=false`
   (F1). The user's literal mechanism; the mark is CORRECT (real new content), so no filter change
   touches it — only the bridge item (WI-3) does. Signature: server `not_generated` > 0 at all.
3. *Genuine relight/edit dirtiness at others' generation frontiers* — LIKELY on real terrain (F2/F3),
   same carrier, correct marks; also bridge-side only.
4. *Bridge saturation by the scattered far set* — the AMPLIFIER of 1-3, not an independent source:
   parked region reloads (≤10/s, 8-window), the 60 s save/recache not-resting window, cap-exempt
   awaiting/deferred → occupancy → halt → wedge → −1 → full-rate stream → evict-oldest = the
   awaiting far set → SILENT (wedged) → permanent holes until revisit/rejoin; governed overflow →
   reports → 4 strikes → park; deferred-tile expiry → silent holes. Signature: `regions_waiting`
   high, `load_requests`/`defer_events` climbing, `dropped_overflow ≫ drops_reported`,
   `dropped_expired` > 0, `bp=-1(wedged)` or the 7 s "made no progress" WARN, client
   `ingest_failed`/`ingest_parked`.
5. *Plain gate blocks* — episodic, `(blocked)`, `cave_layer_waits`, `skipped_settings`.
The own hybrid walk is not a source (F5).

**F9 — Paper.** Dirty detection is event-driven (`PaperWorldHandler`); no content filter, no
first-save storm. Filter items are Fabric/NeoForge (xplat) server work; bridge items are client
work on every line.

**F10 — Parity audit (five lines, corrected).** No missed backport, no behavioural drift; every
reflective member resolves (`javap`) against each line's own 1.45.0 rig jar (Fabric on all five,
NeoForge on 26.2/1.21.1; the `xaero.lib` chain sits in the nested `xaerolib` jar); the 1.42.0 floor
is real and line-invariant. File variants (sha256 across the five checkouts):

| file | variants | diff vs 26.2 |
|---|---|---|
| `XaeroMapCompat.java` | 26.2=26.1=1.21.11=1.21.10; 1.21.1 | 8 lines (contract-pinned height hunk) |
| `XaeroTileExtractor.java` | 26.2=26.1 (`getLightDampening()`); 1.21.11=1.21.10 (no-arg `getLightBlock()`); 1.21.1 (2-arg) | 4 / 8 lines |
| `DirtyContentFilter.java` | 26.2=26.1; 1.21.11=1.21.10=1.21.1 (`getPos().x()` vs `.x`) | 4 lines |
| `DirtyColumnBroadcaster.java` | 26.2 (`lodDistanceForWorld`); 26.1=1.21.11; 1.21.10=1.21.1 (`config.lodDistanceChunks`) | 6 / 8 lines |

Paper cuts: (M1) 26.1's `LSSClientNetworking.java:167-168` says "`WorldRenderEvents.END`" while
`:171-172` registers `v1.level.LevelRenderEvents.END_MAIN` (wrong class AND member for itself);
1.21.10 `:167-168` same sentence while `:171-172` registers `v1.world.WorldRenderEvents.END_MAIN`
(wrong member); 1.21.11 `:164-168` is ALREADY correct; 1.21.1 registers `v1.WorldRenderEvents.END`
(comment fine); the shared `XaeroWiringContractTest.java:84-85` carries the wrong 26.1 claim on all
five lines (its `:90` regex accepts every variant — no test reds). (M2) "member-verified against
26.2/1.21.1 jars only" sits in 1.21.11 `CLAUDE.md:32` and 1.21.10 `:58-60`; 26.1 `:20` says "the
26.2/1.21.1 jars"; 26.2 has no such banner; 1.21.1 `:67` is already per-loader precise. (N1) the two
`HalfTransparentBlock` comments over-attribute to 26.2 (`XaeroTileExtractor.java:361` and `:365` on
1.21.1; test `:302` / `:300`) — the code is identical on all five. `XaeroMapCompat.java:32`
"verified 26.2 ≡ 1.21.1" is CORRECT scoping, not over-attribution — dropped from the list. (M3) no
real-jar surface-resolves test exists (contrast `SodiumLegacySurfaceResolvesTest`) — a member rename
surfaces only live as `state=unavailable`; accepted-open. (N3) the `smp*` Prism instances carry
Xaero 1.40.6/1.41.0, below the floor — `state=unavailable` there by design.

**F11 — Observability surfaces.** `DiagData` is shared and built by `LSSServerCommands.java:246`
and `PaperCommands.java:187` (the nullable `xrayLine`/`summaryLine` precedent,
`DiagnosticsFormatter.java:27-70`). `fabric/src/test/resources/exporter-contract/server-snapshot.contract`
is an exact-equality literal shared by `ExporterContractTest` and `PaperExporterContractTest`; its
header requires `check_soak.py` KNOWN keys and `docs/soak-test-design.md` to move in LOCKSTEP;
Paper zero-fills Fabric-only fields (`PaperSoakMetricsExporter.java:256-264`, cf. `suppressed_total`).
`check_soak.py` pins top-level keys (`:452-460`) and lists `SERVER_MONOTONIC` (`:339-402`; today
only `dirty.broadcast_positions`/`suppressed_total` from this group). Every named check carries
`clean(...)`/`hits(...)` selftest pairs (`:4473-4482`). `run_checker` evaluates ONE results dir
(`:3741`) — chained phases are checked independently, so no pin may span phases.

## 1. Goals / non-goals

Goals: (G1) a chunk whose content did not change must not re-broadcast after a restart or under
table pressure; (G2) no UNHEALED holes — every bridge drop class either reports (late is fine) or
carries a healing loop, and none burns ingest strikes against a resource the writer provably cannot
take yet; (G3) both mechanisms VISIBLE in `/lsslod diag` and `/lss diag` so the next live session is
attributed from counters, with a one-command discriminator; (G4) the parity paper cuts closed.

Non-goals: the want-set model and the hybrid walk (F5); per-recipient filtering of dirty notices by
the server's served-set — `diskReadDone` is session-scoped and region-summary-validated cached
columns never pass the router, so a converged client's columns are absent from it, AND it would
break the F1 revival (the client never held the position); the wire; Paper's event-driven dirty path.

## 2. Work items

### WI-0 Live discriminator and capture (no code — the next multi-player session)

- Ask first: server `enableChunkGeneration` (decides whether F8-2 exists), server uptime and any
  restart during the session (pull `latest.log` over SFTP — rotation-per-restart answers it
  objectively and carries the wedge WARN), Xaero's "Max loaded regions".
- A/B in one session: `/lsslod set dirtyBroadcastIntervalSeconds 0` (silences the wire, keeps the
  drain — F4) while the others keep exploring; the observer's `XaeroMap:` drop counters must go flat
  if F8-1/2/3 carry the scatter and keep climbing if the bridge is choking on the observer's own
  walk. NOTE `set` PERSISTS — restore `10` afterwards.
- Capture before/after: client `/lss diag` `XaeroMap:` (`bp=`, `dropped_overflow`, `drops_reported`,
  `dropped_expired`, `regions_waiting`, `load_requests`, `defer_events`), `ingest_failed`/
  `ingest_parked`, `region_span=` (`ClientCommandActions.java:204,237`); server `not_generated`,
  and — once WI-1a ships — the `Dirty:` line. Today the server dirty counters are unobtainable live.

### WI-1a `Dirty:` diag line + exporter gauges (server, xplat — ship FIRST, read-only)

- `DiagData` gains a nullable `dirtyLine` with a compat overload (the `summaryLine` precedent);
  Fabric/NeoForge render `Dirty: marked=, suppressed=, entries=, dims=`; Paper passes null (F9).
- Exporter: `dirty.entries` (GAUGE — falls on eviction/restart; excluded from `SERVER_MONOTONIC`),
  Paper zero-fills; the four lockstep edits (contract literal, `check_soak.py` KNOWN keys,
  `docs/soak-test-design.md`, `PaperSoakMetricsExporter`). `DiagnosticsFormatterTest` line pin,
  exporter key pins both platforms.

### WI-1b Stop the first-observed-save storm (server, xplat — a MEASURED decision)

Two candidates; the decision record must carry the measured per-load cost.

**Option L — seed at chunk LOAD (recommended).** Hash the just-loaded chunk's native section bytes
once at the loader's chunk-load seam (Fabric `ServerChunkEvents.CHUNK_LOAD`, NeoForge
`ChunkEvent.Load` — a new `LoaderServices` seam, main-thread on both; ~30-60 µs per load per the
`skipDirtyHash` comment, i.e. ~0.1 s/min at 2000 loads/min) and `seed` it. The first save then
compares equal unless content or light actually changed. No file, no world-identity drift, no
restart storm, and the table is bounded by LOADED chunks: drop the entry at unload ONLY if the
loader's unload event demonstrably fires AFTER the unload save reaches `observeSave` (verify the
injection point per line; if not provable, keep the LRU cap as the bound — it is rarely reached
once entries die with their chunks). Load-time seeding has no probe hazard (no edit has happened
yet) and covers the residual WI-2 exists for (chunks never save-observed since install). Cost to
measure under a C2ME/Moonrise load burst before committing. Residual: a suppressed re-save still
bumps the region header second, so on rejoin the summary flags the tile STALE and the client
re-declares ts>0 — `up_to_date` via the tscache rung, or a full re-serve where the tile was
tscache-evicted and the header rung's margined compare fails against the fresh header. Say so in
the release note; `columns_sent` is therefore NOT the WI-5 pin — the suppression ratio is.

**Option P — persist the table (fallback if L's cost or event ordering fails).** Own latest-wins slot
over an MC-free snapshot (copy key/value arrays under the filter monitor, serialize off it), sharing
the single-threaded save EXECUTOR (the #62 bound) but NOT `TimestampSaveScheduler`'s slot — it is
package-private, typed to `ColumnTimestampCache`, and fires on the ~2 s invalidation debounce
(`OffThreadProcessor.java:144-147, 785-792`); schedule from the periodic branch, only when changed,
NEVER write an untouched/empty table (a quiet session — `skipDirtyHash`,
`ServerReceiverGlue.java:96-98,121-140` — would otherwise overwrite a good file), load additively
like `ColumnTimestampCache` (`:468-474`), magic+version only (no seed/folder key — the file lives in
`<world>/data/` and moves with backups; a folder rename must not discard it). Orderly stop mirrors
`OffThreadProcessor.shutdown()` (`:1770-1795`: discard pending + bounded join) without its
processing-thread-alive guard; SERVER_STOPPING nulls the service before vanilla's final save-all
(`LSSServerNetworking.java:176-182`), so the shutdown save-all is never in the file — harmless
(self-heal below). LRU = `Long2LongLinkedOpenHashMap` with `putAndMoveToLast` + `removeFirstLong`
(plain `put` is insertion order = FIFO, evicting the hottest chunks); memory ≈ 25 MB per dimension
at the cap (link array), so derive the cap from the world's region count rather than "keep 512K";
drop dead dimensions' tables. `DirtyContentFilterTest.overflowEvictionTreatsNextSaveAsFirstObservation`
(`:89-120`) pins the wholesale clear and must be REWRITTEN for LRU.

**Both options — the safety argument (corrected).** A wrong stored hash cannot seal a stale client:
`observeSave` re-serializes LIVE bytes at every save, so a bad hash marks dirty at that chunk's next
save (self-heal, safe direction). What both options GIVE UP is the restart storm's accidental
reconciliation of earlier lost invalidations; that loss is bounded because the drain invalidates the
tscache and deletes the store row BEFORE the wire (F4) even when the notice itself is lost, so the
holder's next ts>0 re-declaration (rejoin summary → STALE tile) misses the tscache and fails the
header rung → re-serve. The tscache rung itself answers `up_to_date` on any hit with
`cachedTs <= clientTs` (`IncomingRequestRouter.java:402-412`) and the header rung seals a miss when
the stamp clears the header second (`AbstractChunkDiskReader.java:614-635`) — a world restored from
backup WITHOUT its `data/` files therefore shows a newer-stamped client stale terrain until the
chunk's next save. That hole is PRE-EXISTING (`lss-timestamps.bin` has it today) and neither option
widens it. No config key; the table is derived data and deleting it is always safe — log that at boot.

### WI-2 Seed on DISK serves (server, xplat — conditional; moot under Option L)

Native section bytes exist on the disk path whenever ANY section takes the object fallback — the
whole column then assembles native bytes (`NbtSectionSerializer.java:477-485` → `emitV20Direct`
only when `allTranscoded`; `:504-520` otherwise) — so seeding there is free on mixed columns, not
just "the fallback section". Never on PROBE serves (a live chunk mid-edit — the memory rule) and
never on STORE hits (wire bytes). Decision: do nothing here unless WI-5's AFTER run under the chosen
WI-1b option still reds.

### WI-3 Bridge: the OWED set — evict bytes, keep the debt, report when the writer can take it (client, both bridge files, all lines)

Amends hybrid-scan-plan.md §12.8 bullet 3 ("overflow drops REPORT for re-serve") — record it as
§12.10 there AND in CLAUDE.md's `XaeroMapCompat` paragraph (the §12.9 MAJOR-C precedent: a stale
doctrine record caused a bad backport). Deferral still heals where §12.8's silence did not; with
the unknown-region rule below NO existing pin inverts (`governedEvictionsReportAndUngovernedStaySilent`
`XaeroMapCompatTest.java:1166-1185` evicts inside a loaded region; `:1186-1197` never pumps).

1. **Where owed lives.** OUTSIDE the queue (so occupancy — queue entries only, F6 — is untouched by
   construction): per DIMENSION, per region, a `LongOpenHashSet` of packed positions (no boxing —
   ~48 KB/region otherwise), `MAX_OWED_REGIONS` 256 LRU (the per-region axis never binds — 1024
   tiles IS the region; the unbounded axis is the NUMBER of regions, which outlive the queue).
2. **Classification at eviction.** The evictor runs on the DECODE thread under `queueLock`
   (`:884-910`) and cannot touch Xaero state (`requestRegionLoad` throws off-thread `:1640-1646`).
   Each pump publishes a volatile snapshot of its `waiting` (awaiting-load) region set; the evictor
   classifies against it: awaiting → owe silently; DEFERRED-region or LOADED → today's governed
   report; UNKNOWN region → report (today's doctrine, fail toward reporting).
3. **Fold the silent hole classes in.** DEFERRED_TILE expiry past `DEFER_CAP` (`:1535-1546`) and
   WEDGED-stream evictions (silent today, `:938-943`) become OWED instead of dropped — G2's whole
   point; `dropped_expired` keeps counting, `owed=` shows the debt.
4. **Release trigger — must be OBSERVABLE.** Owed regions have no bytes, so no bucket, no probe, no
   load grant today (`:1470-1481`, `:1609-1626`). Add a bounded owed-region probe pass per pump
   (modelled on `keepOwedRegionsVisited`, `:2072-2085`, under the `FLUSH_PROBE_EXEMPT_FLOOR`
   discipline `:169-174`: N regions per pump, memoized) and let owed regions FEED `grantLoads`
   (largest-cluster-first already, `:1613-1618`) — otherwise a sparse scatter never loads and every
   owed position falls through to the TTL. When a region is loaded AND resting, report its owed
   positions at ≤ 64 per pump (each report is an `mc.execute` task and a want-set entry; an unbounded
   release of 1024 spikes past `WANT_SET_BUDGET` 800).
5. **Invalidation — a late report must never un-stamp a fresher column.** `reportIngestFailure`
   carries no stamp and drops ANY current stamp to −1 + strike (`:718-720`, `:804-811`). So: any
   later OFFER of the position clears its owed entry; COMMITTED/`skipped_native`/settings outcomes
   clear it; the WHOLE owed set drops at dimension change, session end and world-id change
   (precedent `deferredReports.clear()` in `settleSessionEnd`, `:653`); late reports go through the
   bridge's own dimension check so a foreign-dimension report can never reach
   `LodRequestManager.onIngestFailure`'s `ColumnCacheStore.removeAsync` path (`:1051-1066` — a
   silent cache deletion per report).
6. **TTL.** `OWED_TTL` 10 min → ONE report at expiry (the tile is not a permanent hole; if the region
   is still unloaded the cycle repeats at most once per TTL — bounded, and far cheaper than four
   strikes and a park).
7. **Wedge gate (absorbs the old WI-4).** While `haltWedged`, do not EMIT owed reports (mirror the
   `reportDroppedIfGoverned` conjunct) — the −1 full-rate stream must not be joined by an owed
   re-declaration burst. Excluding owed from occupancy is automatic (item 1).
8. **Coalescing — DROPPED.** `grantLoads` is already largest-cluster-first and a hold only pays when
   waiting regions exceed the 8-window; worse, holding small buckets delays the only thing that
   lowers occupancy, so under the exact target shape the 7 s halt time-box (recession-based,
   `:749-760`) wedges. With awaiting bytes shed into owed, occupancy recedes on its own.
9. **Steady state under sustained scatter (say it in §4).** The bridge sheds awaiting bytes silently
   but owed; `owed=` climbing with `owed_reported=` flat is the alarm that a region set never loads.
10. **Diag/tests.** `XaeroMap:` gains `owed=`, `owed_regions=`, `owed_reported=` (no `queue_regions=` —
    `regions_waiting` exists); diag-only, no exporter group (there is none today). Tests on the
    `XaeroMapCompatTest` seams: awaiting-eviction owes without a strike, loaded-region eviction still
    reports, unknown-region reports, release on load+rest ≤ 64/pump, offer/commit clears owed,
    dimension change drops the set, TTL expiry reports once, wedge gate holds reports,
    `MAX_OWED_REGIONS` LRU. Fix stub fidelity first with ONE line: `createdRegionLoadState` default
    → 0 (`MapProcessor.java:38`) and `setUp` (`XaeroMapCompatTest.java:113-138`) sets 2 — not ~90
    per-test edits.

### WI-5 Gates

- **Re-pin `cold-restart-resync`** (it IS the restart-rejoin chain — no new scenario). Rewrite the
  `check_cold_restart_resync` docstring (`check_soak.py:2283`: the "bounded re-send wave is
  expected" sentence is the storm being blessed) and pin, per phase and absolute (no cross-phase or
  client/server ratio — F11): `suppressed_total / (marked_total + suppressed_total) >= 0.9` and
  `marked_total <= 50`; BEFORE = 449 / 0 (reds), AFTER must green; `clean`/`hits` selftest pairs.
  Add a store hit-rate leg (a first-save storm wipes warm rows — F4): `store.h` on the rejoin must
  not collapse against the run-1 deposit count.
- Live gate: WI-0 on the user's next group session, before/after WI-1a+1b and again after WI-3.
- Ports: 26.1/1.21.11/1.21.10 smoke `cold-restart-resync` (correct-not-perfect tier); 1.21.1
  best-effort (the harness, scenario and checker all exist on every line).

### WI-6 Parity paper cuts (docs/comments)

- M1: fix 26.1 `LSSClientNetworking.java:167-168` (class AND member) and 1.21.10 `:167-168`
  (member); the shared `XaeroWiringContractTest.java:84-85` sentence on all five lines; 1.21.11 and
  1.21.1 are correct already.
- M2: 1.21.11 `CLAUDE.md:32`, 1.21.10 `:58-60`, 26.1 `:20` record the per-line static `javap`
  verification; the LIVE NeoForge nested-xaerolib `Class.forName` check stays owed (plan §16.1).
- N1: the two `HalfTransparentBlock` comments (`XaeroTileExtractor.java:361/:365`, test `:302/:300`).
- M3: accepted-open, recorded in surfaces row 19 ("reflective surface verified by `javap` at port
  time, not by a test").

## 3. Per-line matrix and phasing

| WI | 26.2 | 26.1 | 1.21.11 | 1.21.10 | 1.21.1 | Paper |
|---|---|---|---|---|---|---|
| 0 live discriminator | user session | — | — | — | — | — |
| 1a Dirty diag + gauges | ✔ | ✔ | ✔ | ✔ | ✔ | null line, zero-fill |
| 1b storm fix (L or P) | ✔ | ✔ | ✔ | ✔ | ✔ | n/a |
| 2 disk-serve seeding | conditional | ← | ← | ← | ← | n/a |
| 3 owed set | ✔ | ✔ | ✔ | ✔ | ✔ | client = all |
| 5 cold-restart-resync re-pin | ✔ full | smoke | smoke | smoke | smoke | — |
| 6 paper cuts | test comment | ✔ | banner | ✔ | test comment | — |

Phasing: WI-1a → WI-5's BEFORE pin (reds by construction) → WI-1b (Option L measured first) →
WI-5 AFTER → WI-3 → ports. Ports use the change-core applier with PER-LINE cores from the F10
variant table (the accessor spelling in `DirtyContentFilter`, the `lodDistanceForWorld` /
`config.lodDistanceChunks` broadcaster shape, the light-opacity call in the extractor); the
`XaeroMapCompat` hunks are line-invariant outside the pinned height hunk. WI-6 rides each port.

## 4. Risks

- Option L's load seam: chunk-load events may fire before light is attached on some line → a
  load-hash that never matches the first save (storm unchanged, never worse than today) — the
  measurement gate catches it; the unload-order question is settled per line before dropping
  entries at unload.
- Option P's stale-file classes are all self-healing at the chunk's next save (§2 WI-1b); the
  backup-restore hole is pre-existing and unchanged.
- The owed set holds positions without bytes: two `LongOpenHashSet`s per region (fastutil rounds
  a full 1024-tile region to a 2048-slot table) → ≈ 4-8 MB at the 256-region cap; TTL-bounded in
  time (the TTL is per REGION, from its first debt — a long-lived region expires its newest
  debts with its oldest, accepted). The probe pass creates up to 8 Xaero regions per pump
  (`getLeafMapRegion(create=true)`, the commit probe's own idiom); a full 256-region debt is
  real pressure on Xaero's `MAX_LOADED_REGIONS` 300 limiter — the cap is sized under it.
- Steady state under sustained scatter = silent shedding with `owed=` as the alarm (WI-3 item 9);
  a region set Xaero never loads costs one re-serve per owed position per 10 min.
- A late report racing a fresh offer: closed by item 5 (offer clears owed); the residual is a
  report between the client's stamp write and the offer — the same window today's governed report
  already has.
- Stub fidelity: tests written against loadState-2 stubs pass while the real jar starts at 0 — flip
  the default before writing WI-3's tests.

## 5. Open questions for the user's next session

`enableChunkGeneration` on that server; uptime/restarts (pull `latest.log`); the WI-0 A/B result;
the client `XaeroMap:` tokens and `ingest_parked` before/after; Xaero's "Max loaded regions".

## 6. Decisions log

- 2026-09-05 v1: plan drafted from the two investigations; the user's hypothesis marked refuted.
- 2026-09-05 v2 (panel fold): the hypothesis is CONDITIONALLY correct (gen-disabled servers, F1);
  the restart storm is already recorded by `cold-restart-resync` (F3) — no new scenario; WI-1 split
  into diag-first (1a) and a measured L-vs-P decision (1b); WI-3 redesigned around an observable
  release trigger, decode-thread classification, invalidation rules and the silent hole classes;
  WI-4 folded into WI-3; coalescing dropped; the risk section's tscache-bound claim inverted and
  the hole recorded as pre-existing; byte-identity replaced by the F10 variant table.

## 7. Review record (2026-09-05, 1 Fable + 4 Opus, read-only)

- Fable (mechanism lead): F1/F2/F4/F5/F6 verified line by line, no dirty source missed; MAJOR
  load-time baseline alternative (→ WI-1b Option L); MAJOR wedged evictions are silent, WI-4
  vacuous (→ F6/F8-4, WI-3 items 1/3/7); MINOR light-step save mechanism, invalidation-before-loop,
  non-goal reasoning, the header-bump residual, native bytes on mixed columns, coalescing vs
  largest-cluster-first — all folded.
- Opus A (WI-1): MAJOR risk bound inverted (tscache rung serves the stale answer; self-heal is the
  next save's live re-serialization; the reconciliation net) → §2 WI-1b safety argument; MAJOR
  quiet-session empty-write; MAJOR scheduler piggyback impossible; MAJOR LRU primitive + memory ×3
  — all folded into Option P; MINORs (lock discipline, keying, `DiagData` overload, gauges, the
  dying test pin) folded.
- Opus B (WI-3/4): MAJOR unobservable release trigger; MAJOR eviction site cannot classify; MAJOR
  late report un-stamps fresher columns + dimension-trip cache deletion; MAJOR doctrine amendment
  record; MAJOR coalescing feeds the wedge — all folded (items 2/4/5/8 and the §12.10 record);
  MINOR/NIT sizing, park-on-fourth, park revival, stub default flip, report storm bound — folded.
- Opus C (gates): MAJOR `cold-restart-resync` already is the chain with the BEFORE numbers; MAJOR
  thresholds unconstructible; MAJOR byte-identity false; MAJOR the live BEFORE capture impossible
  without the diag line — all folded (WI-5, F3, F10 table, WI-1a first); MINORs (contract lockstep,
  gauges, selftest pairs, `latest.log`) folded.
- Opus D (adversarial/parity): MAJOR F1 conditionally wrong (sessionSatisfied revival) → F1/F8-2;
  MAJOR the one-command discriminator → WI-0; MAJOR deferred-tile expiry is an unhealed hole →
  WI-3 item 3; MINORs (header rung seals the restore hole — pre-existing; store-row wipe → WI-5 leg;
  three-way extractor variants; exact M1/M2/N1 texts) folded; `XaeroMapCompat.java:32` dropped from
  the paper cuts.

## 8. As-built record (2026-09-05, branch feat/xaero-scatter off main)

- **WI-1b = Option L, decided by the seam facts, not by measurement:** both loaders fire
  their chunk-load event from the FULL status task with the light engine attached
  (fabric-api 4.1.3 `ChunkStatusTasksMixin` at `lambda$full$0` TAIL; NeoForge 26.2.0.59's
  `ChunkStatusTasks` patch posts `ChunkEvent.Load(levelChunk, …)`), so the load hash equals
  the first save's by construction; the per-load cost is one `serializeColumn` (~30-60 µs)
  on the main thread, the same call the save hook already pays per save. The live
  measurement is `seeded_load=` in `/lsslod diag` (0 on a lively server = the chunk system
  does not fire the event; the filter then behaves exactly as before). LINE FLAVOR: the
  fabric-api 4.x callback takes a third `generated` flag (surfaces row 22). LRU replaces the
  clear-all (`putAndMoveToLast` + `removeFirstLong`); entries are NOT dropped at unload
  (Fabric's unload event fires before the unload save reaches the hook — verified in the
  `ServerChunkLoadingManagerMixin` injection). Option P is not implemented.
- **WI-1a:** `Dirty: marked=, suppressed=, seeded_load=, entries=` after the Gate slot
  (`DiagData.withDirtyLine`, Paper null); exporter `dirty.seeded_load` (cumulative) +
  `dirty.entries` (gauge) with the four lockstep edits (contract literal, `check_soak.py`
  fixture, `soak-test-design.md`, Paper zero-fill).
- **WI-3 as built:** hybrid-scan-plan.md §12.10 is the doctrine record. Two panel gaps
  found at implementation: (1) a region-ready release of a deferral-EXPIRED tile would
  re-serve into the same busy tile chunk (the §12 review's original strike-burn objection)
  → tile-scoped debts (`OwedRegion.busyTiles`) release only once their tile chunk is ready;
  (2) the pump's idle fast-out returned before the probe pass when the queue was empty →
  an owed debt keeps the ladder running. Twelve pins in `XaeroMapCompatTest`; the stub's
  `createdRegionLoadState` default flipped to the faithful 0.
- **WI-5:** `check_cold_restart_resync` re-pinned (`marked_total ≤ 50`, suppression ratio
  ≥ 0.9; docstring rewritten; three selftest cases). BEFORE proof: the re-pinned checker
  against the recorded 2026-08-21 run reds exactly the two new legs (449 / 0). The store
  hit-rate leg is NOT added: the scenario pins `lodStore: "off"`.
- **WI-6:** the per-line comment/banner fixes ride each port (26.1 + 1.21.10 event
  wording; the shared test comment on all five; the three banners; the two extractor
  comments).
- **Implementation review (2026-09-05, 2 Opus, folded):** server side — M1 chunks loaded before
  the service exists (the spawn set in `prepareLevels`) or while the skip gate is shut were never
  seeded (a ~25-chunk floor by default, unbounded under `/forceload`) → positions recorded
  (≤ 8192) and flushed via `getChunkNow` at service construction and at the gate-opening
  registration (`ServerReceiverGlue.flushPendingLoadSeeds`; Tier 1 + a Tier 2 pin against a
  real chunk); M2 the load hash ran on the tick thread INSIDE the filter monitor → hash
  outside, `storeHash` inside. Bridge — M1 `owedLock` was held across a Xaero region monitor
  (`tileChunkReady`) while the decode thread takes it per offer → tile probes run outside the
  lock; M2 the owed conjunct defeated §12.9's 1 Hz blocked-idle ladder throttle → restored;
  minors: the latest-wins REPLACE path also pays the debt, `forgetOwed` clears both sets,
  releases wait for queue room (`occupancy < 0.75`) besides the wedge, gauges published after
  the owed feed, two more pins (a classified loaded region still reports; queue-room hold).
  Fresh-backfill on the fixed jar (superflat generation): `dirty.marked_total` 41 /
  `suppressed_total` 2325 / `seeded_load` 2325 — the old baseline read 674 / 1198.
- **AFTER gate (2026-09-05, `soak-results/cold-restart-resync-20260905T202622Z`, PASS 0
  violations):** `dirty.marked_total` 8 / `suppressed_total` 441 / `seeded_load` 441 (the
  restarted server's whole 21×21 loaded disc seeded at load and suppressed at its first save);
  `service.up_to_date` 2144 = the client's every resync ask, `columns_sent` 0 (BEFORE: 449 /
  0, 184 re-sent). Gates on the final commit: T1 2321/0, T2 76/0, Paper 10/0 contract +
  the rest, NeoForge 23/0, `check_soak --selftest` 273.
