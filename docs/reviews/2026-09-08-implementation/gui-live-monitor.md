# Live GUI evidence monitor — 2026-09-08

The four1.21.1 UI lanes, both loader render/reset/switch lanes, strict seated-fault
proofs and both Elytra lines are closed and archived. Two final controlled timing
variants are recorded separately when complete. Root owns all UI inputs, launches and runtime changes.
This monitor only reads/copies logs and writes evidence. All accepted Windows client
connections recorded below use IPv6 loopback (`::1`, serverA25566/serverB25567).

## Immutable modern-client evidence

`gui-logs/fabric-modern-ui/` contains complete latest.log, the LSS trace, server A/B
checkpoint logs and manifest hashes. Client closed at2026-09-08T23:38:07Z; the archived
log ends with Stopping, map-session finalization, cache save and worker shutdown.
Server logs remain checkpoints because the servers continue serving later lanes.
Earlier checkpoint `gui-checkpoints/20260908T233823Z` is retained. Local log clocks are
America/New_York (UTC−04:00); append four hours for UTC.

Actual client census: MC1.21.1, Fabric Loader0.19.3, LSS0.14.0, Sodium0.8.13-beta.2+mc1.21.1,
Xaero World Map1.45.0 (`latest.log:68,71,75–76,83`). Xaero consumer registration is at:113.
This particular modern lane has no Voxy in the loaded mod census. It tests the modern
Sodium page plus Xaero consumer path, not simultaneous Voxy+Xaero ingest.

## Real OFF → ON and joining while OFF

Root supplied the UI action times and captured screenshots in `gui-fabric-modern/`;
the log observations below independently constrain the resulting behavior.

| UTC action/observation | Log evidence and observed result |
|---|---|
|23:33:35 OFF applied|Client saved2244 cached entries (:252);23:33:53 diagnostics says LSS not active (:253).|
|23:33:52,23:34:09,23:34:12 OFF samples|ServerA requests remains101254, sent2244, all pending/send queues0 (:205–210). These are sampled frozen counters, not a packet capture.|
|23:34:38 ON applied|ServerA receives v20/caps7 handshake and registers (:211–212); by23:34:40 requests106054 (:214). Client receives session config and loads2244 entries (:254–256).|
|23:34:40 resumed diagnostics|Client new response counters:0terrain bodies,2244up-to-date; Xaero written2244 retained, queue/pending0, ingest failures0 (:257–270).|
|23:35:03 OFF,23:35:04 kick|Client preserves2244 entries and finalizes native map session (:271–277); serverA logs kick/leave (:215–217).|
|23:35:18 join while OFF|Actual IPv6 login appears serverA:218–219. At23:35:34 vanilla list shows player online but LSS list says No players connected with LSS (:220–221); client inactive at23:35:33 (:295).|
|23:35:55 ON after OFF join|Fresh handshake/registration serverA:222–223; client config/cache load :296–298, subsequent up-to-date responses and no ingest failures (:299–312).|

These observations cover the actual Apply path and reannouncement without a forced
reconnect for ON, plus silence on an initial-OFF join. They do not claim packet-level
absence from sparse stats alone. Root's captured UI images establish which page/control
was used; this monitor did not inject inputs or independently inspect the screenshots.

## Pending-work server switch

At23:37:07 root cleared the client cache through the normal command. At23:37:09 the
client diagnostic sample showed processor queue60, Xaero queue69, pending native
updates140, written6307, ingest_failed/parked0. Root immediately kicked in the same
second; actual serverA kick/leave appears :226–228, and client logs show cache save,
map-session finalization and worker shutdown before the next connection.

At23:37:13 client connected to `[::1]:25567`; serverB logged its IPv6 login and v20/caps7
handshake. Client initialized a new Xaero map session and received a new LSS session
configuration. Cache address changed from `___1__25566` to `___1__25567`, each with
alias:no-group. These cloned worlds share the displayed world id07f476339e08515f;
endpoint identity still differs. This is not an independently identified different-seed
world test.

At23:37:39 B diagnostics showed2244received terrain columns, processor queue0,
Xaero queue0, pending_updates0, active Xaero state, written8679, commit_failures0,
drops_reported0, owed0, ingest_failed0, ingest_parked0. dropped_updates149 records native
world teardown; it is not the ingest failure counter. The later native map drained
while the receipt retry counters stayed clean. Root captured
`gui-fabric-modern/map-second-server-settled.png` at this state.

Limits: written is cumulative across sessions;8679−6307 is NOT an exact B-only write
count, because work could commit between the pre-kick diagnostic and the kick. The
69/140 pre-kick counters are likewise a real sampled premise, not a latch proving an
exact count at disconnect. At B's sample the scan was95/256 and still requesting
missing columns; zero map backlog does not mean the whole acquisition had quiesced.
This live composition supports teardown/recovery and absence of phantom ingest failures;
deterministic receipt ownership/race assertions remain in the focused tests.

## Continuing lanes

Root reports launching the separate legacy Fabric clone as offline LssSubjectB to
IPv6 serverA25566, without an injector, and plans to retain it for the later far-player
render lane. Modern-client archive above must not be replaced by that later restart's
latest.log. Subsequent lane evidence will be appended after observations/closure.

## Real Fabric command persistence denial (completed)

On serverA at23:39:10, `/lsslod set farPlayers on` established the baseline. Root then
made the config directory0555, applied OFF at23:39:11, restored0755 and applied ON
at23:39:13. Actual server log :229 records baseline ON; :230–260 contains the real
AccessDeniedException for `./config/lss-server-config.json.tmp`; :261 reports
`farPlayers = off ... applied, but not saved — see server log`. Independent subsequent
listing :271 confirms effective runtime OFF. After restoration, successful ON is at
:274 and separate listing :285 confirms effective ON. Current directory mode was
independently observed0755 by this monitor.

Root's `fabric-command-baseline.json`, `fabric-command-denial.json`, and
`fabric-command-final.json` all have SHA-256
74c58d77c8c9625b31c636cae6831e47f5cc2f462d297644865467ab57f1f5b1 and farPlayers:on.
Thus the denied OFF changed runtime state while leaving persisted bytes unchanged,
and the restored ON ended with both runtime and disk ON. The final hash matching the
baseline is expected for that restoration; the separate successful command response
and listing support the success leg. This is actual file-permission failure evidence,
not a mocked write result. Paper and NeoForge equivalent gates are parent-owned and
recorded separately; this monitor inspected the Fabric evidence here.

## Legacy Fabric launch checkpoint

Actual legacy client census is Fabric Loader0.19.3, LSS0.14.0, Sodium0.6.13+mc1.21.1,
Xaero World Map1.45.0. The launcher CLI mishandled its bracketed IPv6 argument; that
failed prelude is not accepted connection evidence. Root used the actual GUI direct
connection, and the client log at23:39:45 records `Connecting to ::1,25566`; serverA
23:39:46 records LssSubjectB's real IPv6 login, followed at23:39:47 by v20/caps7
handshake and registration. At23:40:36 server stats show2244sent columns. The legacy
page/toggle observations are pending. No injector was involved in this UI subject.

## Legacy Fabric UI observations completed; client retained for WI9

Checkpoint archive: `gui-logs/fabric-legacy-ui-checkpoint-20260908T234416Z`. This is a checkpoint, not the final
client log: root retained the legacy client for later far-player rendering work.
The relevant UI observations all precede the second subject's arrival at 23:42:48Z.

- OFF: cache save at 23:40:55 and inactive client diagnostics at 23:40:58
  (legacy client log :183–184). Server requests remain 77716 across 23:40:58,
  23:41:21 and 23:41:25, with pending/send queues zero (server A :294–299).
- ON: renewed handshake at 23:41:27 (server A :300–301), fresh session config
  and 2244 cached entries loaded (client :185–187). At 23:41:29 the client has
  2244 up-to-date responses, zero new response bodies, Xaero written=2244,
  queues/pending updates zero and no ingest failures (:188–201).
- Initial OFF join: preserved cache and kick at 23:41:51–52, IPv6 rejoin at
  23:41:55. At 23:42:23 the player is online but no LSS player is registered
  (server A :304–310), and the client reports inactive (:202–226).
- ON after that join: 23:42:28 handshake/registration (server A :311–312),
  client config and 2244 cache entries restored; at 23:42:30 up-to-date responses
  resume, Xaero remains active with written=2244 and all map queues, pending
  updates, ingest failures and parked counts zero (client :227–243).

Actual loaded Sodium is 0.6.13+mc1.21.1. As for the modern lane, these log-derived
state transitions corroborate root's page interactions; they are not independent
visual inspection or a packet trace. At 23:42:48 a separate SoakPlayer logs in and
starts the WI9 setup; later observations must be attributed to that separate lane.

## WI9 preparation — not yet accepted

AstraValidator's next modern Fabric launch logged in at 23:43:23 and disconnected at
23:43:24. Root identified an external validation fixture IllegalClassLoadError (Probe
inside a mixin package), before controlled injection. Agent04 owns the fixture archive
and correction. This is not classified as an LSS product failure and is not a live WI9
pass. The immutable earlier modern UI archive remains separate. Legacy client19080
continues as the second subject; SoakPlayer joined at23:42:48 and both subjects mounted
boats at23:43:05. These setup events alone prove no far-player rendering outcome.

## WI9 corrected Fabric fixture: strict same-frame recovery PASS

Current complete log prefix is archived before reuse at
`gui-logs/fabric-wi9-pass-checkpoint-20260908T235013Z`. This remains a checkpoint until client closure is confirmed.
Actual client log at 23:47:36Z reports:

- :215 ARMED pass=676, two real proxy draws observed, first proxy seated.
- :216 INJECTED pass=677, passenger=true and real_dispatcher_push_translate=true.
- :218 NEXT_PROXY_HEAD on pass=677, sentinel_and_matrices_restored=true.
- :219 NEXT_PROXY_RETURN on the same pass.
- :220–221 two TAG_HEAD observations on pass=677, each restored.
- :222 PASS_SAME_FRAME pass=677, next_starts=1, next_returns=1,
  tag_starts=2, tag_returns=2, outer_unwind=true, crash_latched=false,
  assertion_failed=false.

The controlled fixture thus reports continuation through the next proxy and both tags
in the injection frame, after the real seated draw pushed/transformed the matrix stack.
The later 23:47:54 diagnostic drawn=2, mounts=1, tags=2 corroborates live participation
but is not the decisive recovery assertion. Root and agent04 own fixture source fidelity
and artifact review; this monitor records the actual runtime outputs.

Visual qualification: root reports the subjects at 160 blocks were obscured by vanilla
fog in this Xaero-only client. No visually verified rider placement, model appearance,
or outside-view-distance visibility is claimed from that scene. The intentional lack
of a fog override is separate from matrix recovery. A matching Voxy client route is
being investigated by the other reviewer for the visual lane.

## Voxy + Xaero observer follow-up

The earlier Fabric WI9 archive actually includes clean Stopping/map finalization and
worker shutdown at 23:50:06Z; its initial checkpoint label was conservative. Root then
launched a matching Voxy observer. Actual census records Voxy 0.2.15-beta with Sodium
0.8.13-beta.2+mc1.21.1, Xaero 1.45.0 and LSS 0.14.0. Both Voxy raw-ingest and Xaero
consumers registered; Voxy created its world engine and renderer. The IPv6 login at
23:51:31–33 and cache load of 2257 entries are logged.

At 23:52:20Z the corrected fixture again reports strict same-frame recovery:
ARMED pass=2820; INJECTED pass=2821 with passenger=true and real dispatcher push/translate;
NEXT_PROXY_HEAD restored; NEXT_PROXY_RETURN; two restored TAG_HEAD callbacks;
PASS_SAME_FRAME with next starts/returns=1/1, tag starts/returns=2/2,
outer_unwind=true, crash_latched=false and assertion_failed=false. The mount rendering
warning at that moment is the controlled injection, not an uncontrolled product crash.
This pass prefix is archived at `gui-logs/fabric-voxy-wi9-pass-checkpoint-20260908T235454Z` while the client remains live.

The 23:52:02 snapshot had zero new terrain responses/Xaero writes because existing
content proof was restored from cache. Loading two consumers alone therefore does not
prove fresh dual-consumer ingest. Visual scene assessment and any forced re-request
premise remain root-owned; this monitor does not infer them from registration alone.


## Fabric Voxy render and ordinary reset — closed run

Complete client log is now archived at `gui-logs/fabric-voxy-render-reset-complete/latest.log`
(SHA256 `8d459b7ba3fab087970d4aa6a200c34e4f0ab10d55d915ae44bcb8c78877a537`).
The manifest identifies live server checkpoints separately. The copied 19:32 trace
belongs to the earlier UI run; its presence is not a trace of this later observer.
Client clean Stopping/map finalization/cache save occurred at 23:56:06Z and Voxy
instance shutdown completed at 23:56:07Z. Server A logged departure at 23:56:05Z,
then explicitly dismounted/kicked both subjects before the next sole-client UI lane.

Root reports visible WI6 ON/OFF/ON acceptance with both subjects stationary at 160
blocks, observer FOV 30. Captures are `gui-fabric-render/on-before.png`, `off.png`,
and `on-restored.png` at 23:54:18/38/40Z. Server A independently logs effective
`farPlayers = off` at 23:54:36Z and `on` at 23:54:38Z. The visual judgment belongs
to root's direct observation; this monitor corroborates the command transitions.
The same run's strict WI9 pass=2821 remains the matrix-recovery evidence above.

At 23:54:56Z the read-only first stage of `voxy-force` reported the actual Voxy
storage root and the connection-derived root identical, both contained within the
validation clone: `.voxy/saves/[__1]_25566`. It explicitly reported no deletion yet;
no forced second-stage deletion is claimed. Root then invoked the ordinary reset
at 23:55:11Z. Actual logs show renderer/instance shutdown, wiping that exact root,
new instance/world engine/NormalRenderPipeline creation, clearing one LSS cache
bucket, and the disk+memory-cleared/re-requesting feedback at 23:55:12Z.

The fresh-data premise is now established: 23:55:14Z has 682 new column bodies,
Xaero written=391, queued=291 and pending_updates=47; 23:55:32Z has 2244 new bodies
(13.5 MB), processor queue=0, ingest_failed=0, ingest_parked=0, ingest_backlog=0,
Xaero written=2244, queued=0, pending_updates=0, commit_failures=0 and owed=0.
Thus the earlier cache-only limitation is resolved for this ordinary reset route.
Both consumers are registered, and fresh traffic/map commits converge after the
live Voxy reinitialization. The monitor does not independently inspect Voxy's
rendered block contents; root records the visible terrain capture at
`gui-fabric-render/voxy-reset-terrain.png`. Acquisition still scans 95/256 at the
settled map snapshot, so this is drained ingestion, not whole-scanner quiescence.


## NeoForge modern UI and ordinary reset — live checkpoint

Archive `gui-logs/neoforge-modern-ui-checkpoint-20260909T000148Z` is a live prefix; original logs continue.
Actual loader census: NeoForge 21.1.248, MC 1.21.1, LSS 0.14.0, Sodium
0.8.12-beta.1+mc1.21.1, Xaero 1.45.0, Voxy 0.2.15-beta through Sinytra Connector.
The native scan initially skips the Fabric Voxy jar, but Connector subsequently
loads its mapped jar and the Voxy world engine/renderer initialize successfully;
the first native scan warning does not mean Voxy is absent.

Both Fabric subjects were removed before the sole NeoForge client IPv6 login at
23:57:34Z. Server A independently lists one online player at 23:58:25Z. OFF saves
2244 cached entries at 23:58:45Z and client reports inactive at 23:58:47Z. Server
requests remain exactly 79256 at 23:58:47, 23:59:08 and 23:59:10, pending work zero.
ON produces a v20/caps=7 handshake at 23:59:12Z and config at 23:59:13Z. At
23:59:15Z the client restores 2244 cached entries, receives 2244 up_to_date replies
and zero new bodies, retains Xaero written=2244, and has zero queued/pending map
work and zero ingest_failed/parked.

After another OFF, root kicks at 23:59:45Z. Actual IPv6 reconnect is 23:59:50Z.
At **2026-09-09 00:00:13Z** (local Sep8 20:00:13), the server lists the sole vanilla
player and no LSS players; client also reports inactive. ON produces a new handshake
at 00:00:18Z. At 00:00:20Z cached=2244, up_to_date=5, new bodies=0, Xaero written=2244,
queues=0 and ingest failures=0. Times after midnight UTC belong to September 9.

The 00:00:22Z read-only forced-reset preview reports live and expected roots equal
inside this clone, `.voxy/saves/[__1]_25566`, with no deletion yet. Ordinary reset
then logs shutdown, contained wipe, new Voxy world engine/render pipeline and LSS
cache clear at 00:00:39Z. At 00:00:40Z fresh bodies=284 and Xaero pending work is
visible. At 00:01:03Z fresh bodies=2244, Xaero written=4488 cumulative (2244 new
writes since reset), queued/pending_updates=0, commit_failures=0 and
 ingest_failed/parked=0. This is observed fresh recovery, not inferred from old
cache proof. A second reset starts at 00:01:05Z; subsequent activity is not yet
classified in this checkpoint.


## NeoForge modern completed server-switch run

The complete client log and trace are safely archived at
`gui-logs/neoforge-modern-ui-complete`; latest.log SHA256 is
`0861ff15f8128dd6cdcbc67808cb20c7a1d15e1dfc301870e461ed80c172ab18`.
All 108881 archived trace rows parse as JSON. Clean Stopping/map/cache/renderer
shutdown occurs at 2026-09-09 00:01:59Z; Voxy instance shutdown ends 00:02:00Z.
Server logs in the same archive remain checkpoints of continuing shared servers.

Root's second ordinary reset starts at 00:01:04Z (wipe logged at :05). The
00:01:06Z pre-disconnect diagnostic observes Xaero queued=192 and pending_updates=96.
Client finalization begins at 00:01:06.9 local-log fractional time; root's console
kick is recorded at 00:01:07Z. This sub-second difference is a clock/logging
observation, not a claim that the kick was caused after disconnect. Root issued
the immediate kick as the diagnostic returned. Client saves 1269 cached entries,
finalizes the old native map, and establishes a new IPv6 connection to B
(`::1,25567`) at 00:01:10Z. New map/Voxy world/render initialization and v20 config
follow at :11; cache address changes to `___1__25567` with no alias group.

At 00:01:36Z B has 2244 new bodies, zero ingest_failed/parked, zero processor and
Xaero queues, zero pending_updates/commit_failures/owed, and Xaero written=8001
cumulative. Native teardown dropped_updates=106 is expected pending native update
retirement, not consumer ingestion failure. Counters are sampled before disconnect;
neither the 192/96 premise nor cumulative written difference is an exact tally of
work abandoned or performed solely on B. As in Fabric, both servers use the same
cloned world identity; this validates address/session isolation, not different-seed
world content. Root records the settled map image at
`gui-neoforge-modern/map-second-server-settled.png`. Scan remains 111/256, so the
claim is converged ingestion/map work rather than globally finished acquisition.


## Legacy NeoForge first attempt — consumer exception qualification

Live checkpoint `gui-logs/neoforge-legacy-voxy-exception-checkpoint` preserves an
actual native Voxy 0.2.9-alpha ingestion exception at 2026-09-09 00:03:01.459Z:
`IllegalStateException: Section was dirty but is also unloaded, this is very bad`.
The stack is WorldSection.release → WorldUpdater.insertUpdate →
VoxelIngestService.processJob on Chunk Render Task Executor #3. LSS registers both
Voxy and Xaero consumers before login, but that alone establishes no successful
completion of the old Voxy ingestion work. No product cause is inferred from the
absence of an LSS frame in this worker stack; root is notified for classification.

Server A already has requests=123/sent=123/pending=0 at 00:03:47Z before OFF at
00:03:49Z. Therefore a subsequent fixed count of 123 is insufficient evidence that
OFF stopped an active stream on this attempt. Actual client inactive state, renewed
handshake and request growth after ON, plus initial-OFF admission silence, can
still establish narrower lifecycle facts independently. No healthy dual-consumer
convergence or successful active-stream suppression is claimed yet.


Root rejected that legacy native-Voxy attempt rather than accepting a vacuous
fixed-count OFF result. Client closed at 00:04:42Z; complete log is preserved in
root's `neo-legacy-native-voxy-failure/latest.log`, copied with the trace to
`gui-logs/neoforge-legacy-first-rejected-complete`. This attempt is **not counted
as a legacy UI pass**. The observed native Voxy 0.2.9-alpha exception remains not
fully diagnosed and outside this product-fix scope. Root disabled only that jar
inside the contained validation clone, restored receive ON for a fresh Xaero-only
legacy UI trial, and will use the already-working modern Voxy pairing for the
NeoForge renderer fixture. No original instance modification is asserted here.


Shutdown qualification for rejected native-Voxy trial: root confirms PID27184
remained alive with its window after the 00:04:42Z renderer-shutdown log. The
application did not exit cleanly. Root force-stopped only that owned PID at about
00:06Z; initial relaunch attempts were refused while it remained alive. Earlier
"client closed" wording refers to root's close request, not completed process exit.
This observed shutdown stall is retained alongside the ingestion exception without
assigning an unproven cause. The final fresh Xaero-only launch follows forced cleanup.


## Legacy NeoForge Xaero-only retry — UI lifecycle accepted

Complete client log and current-run trace are archived at
`gui-logs/neoforge-legacy-xaero-ui-complete`; the failed first trial remains separate.
This trial uses Sodium 0.6.13 and the native NeoForge LSS/Xaero paths with the old
native Voxy jar disabled. It makes no native Voxy 0.2.9 compatibility claim.
Actual IPv6 login is 2026-09-09 00:07:02Z, v20/caps=7 handshake :03. Restored proof
covers 123 previous entries; the new run receives 2121 fresh columns, Xaero writes
2121, and queues drain by 00:07:23Z. Server requests grow from 23741 at :26 to
53341 during the later OFF interval, supplying the missing active-flow premise.

OFF produces an inactive client and requests hold exactly 53341 at 00:07:55,
00:08:16 and 00:08:20Z, pending queues zero. ON yields a fresh handshake at
00:08:22Z and 4800 requests by client diagnostic :24. Cached proof=2244 and Xaero
written=2121 remain intact; no map backlog or ingest failure appears.

Following another OFF, root kicks at 00:08:50Z. Reconnect over IPv6 at :54 admits
the sole vanilla player; at 00:09:16Z the server lists one player but no LSS
players and client reports inactive. ON yields a new handshake at 00:09:21Z;
:23 diagnostics show cached2244, up_to_date5, zero new bodies, zero ingest_failed/
parked, Xaero written2121 and queued/pending/owed0. This proves the intended
initial-OFF silence and same-connection ON recovery for the legacy Sodium UI.
Root's UI actions/screenshots supply the direct page-interaction evidence; logs
corroborate lifecycle effects. Clean shutdown sequence logs Stopping00:09:23.881,
map finalization, worker stop and cache save00:09:24; this monitor has not separately
queried process exit. No native Voxy engine is involved in the accepted retry.


## NeoForge visible far-player OFF/ON and first WI9 evidence gap

Modern Neo observer's first renderer run is archived at
`gui-logs/neoforge-wi9-first-no-marker-complete` (latest/debug/server A). Root
observed two stationary proxies disappear and restore with server farPlayers
OFF at 2026-09-09 00:12:01Z, ON at :02 and restored by :04. Direct images are
`gui-neoforge-render/on-before.png`, `off.png`, `on-restored.png`. The monitor
attributes visible WI6 acceptance to root's observation and corroborating command
logs, independently of fault-injection proof.

The isolated test fixture is present in the actual loaded census and a mount
rendering warning occurs at 00:10:41.775Z, but neither latest.log nor debug.log
contains the required ARMED/INJECTED/PASS_SAME_FRAME sequence. No WI9 pass is
claimed from this first Neo observer or its ordinary post-warning draw counters.
Root confirms the external fixture is being changed to durable SLF4J logging
before one fresh-process rerun; no production source change is involved.
The first observer logs clean map/render/instance shutdown at 00:12:16Z.


## NeoForge WI9 durable same-frame PASS

The fresh SLF4J fixture observer connects over IPv6 at 2026-09-09 00:20:46Z.
Actual latest.log now contains durable proof (archived at
`gui-logs/neoforge-wi9-durable-pass`):

- :425 at 00:20:49.138Z ARMED pass=2 after two real proxy draws, first seated.
- :426 at .187 INJECTED pass=3, passenger=true and real_dispatcher_push_translate=true.
- :428 at .312 NEXT_PROXY_HEAD on pass=3 with sentinel_and_matrices_restored=true.
- :429 at .313 NEXT_PROXY_RETURN on pass=3.
- :430–431 at .313–314 two TAG_HEAD callbacks on pass=3, both restored.
- :432 at .318 PASS_SAME_FRAME pass=3: next starts/returns=1/1, tag starts/returns=2/2,
  outer_unwind=true, crash_latched=false, assertion_failed=false.

The expected mount warning lies between injection and next-proxy recovery and is
therefore the controlled fault, not an unexplained product crash. The loaded native
NeoForge renderer has now produced the same strict runtime proof as Fabric. Root
reports fixture SHA256 `c1ac8a0520ec044dbc446c86fe62536d1a9c4f5a1257308a7fa8a571c11d0dea`;
the archive manifest independently hashes the installed test-only fixture jar.
Agent04 owns its source-fidelity review. This closes the first observer's logging
evidence gap without retroactively marking that earlier no-marker recording a pass.
Root is closing the lane; final shutdown log capture follows separately.


Final 1.21.1 lane closure: complete durable Neo WI9 client log and final A/B server
logs are archived at `gui-logs/neoforge-wi9-durable-pass-complete`; the preceding
pass archive retains the independently hashed injector jar. Root confirms Neo
clean close00:21:17Z, legacy Fabric subject close00:21:18Z, dummy kick/exit0,
and both GUI servers stop/exit0. Root removed the Neo injector and fixture flags
before subsequent lanes. The unrelated regular server remains outside this task's
process ownership. This monitor performed no runtime/process manipulation.

The remaining GUI observations will cover real Elytra subjects on 1.21.10 and
1.21.11; they do not change the accepted/rejected scope of the 1.21.1 records above.


## 1.21.10 real Elytra subject — visual transitions in progress

Actual observer census: Fabric Loader0.18.4, MC1.21.10, LSS0.14.0,
Sodium0.7.3+mc1.21.10 and Voxy0.2.9-alpha. This is the Fabric build of that older
Voxy version, a distinct pairing from the rejected native NeoForge 0.2.9 attempt.
The observer connects over IPv6 `::1,25568` at 2026-09-09 00:23:15–17Z. The real
SoakPlayer subject is equipped with Elytra, switched to survival, and placed at
x160.5 relative to observer x0.5; the renderer reports tracked=1/drawn=1/tags=1
at 00:23:46Z. No fault injector is involved in this visual transition lane.

Root confirms actual Shift down/up at 00:24:17–31Z and directly observes/captures
`gui-elytra-1.21.10/standing.png`, `crouching.png`, `standing-recovered.png`.
The root's exact command is `data get entity SoakPlayer FallFlying` (console
outputs omit the path; attribution comes from root's issued stdin). Server logs
show subject teleport to y185 at00:24:50Z, FallFlying=1b at00:24:51Z, and the
root observes horizontal posture plus visible wing in `gliding.png` at00:24:52Z.
After return to y150 at00:25:02Z, FallFlying=0b at00:25:25Z and root captures
`landed-standing.png` at00:25:27Z. These are real subject state transitions;
this monitor corroborates state and participant counts, while the visual pose
judgment belongs to root's image inspection. Larger spyglass captures are pending.


1.21.10 visual acceptance refinements from root: clear `spyglass-standing.png`
at00:26:11Z, `spyglass-crouching.png` at00:26:22Z with visibly changed wing angle,
and `spyglass-standing-recovered.png` at00:26:47Z. Original normal-20-TPS gliding
capture remains `gliding.png` at00:24:52Z. A closer `spyglass-gliding-rate5.png`
at00:27:12Z shows a distinct wing/body angle with real FallFlying=1b queried
at00:27:10Z; its 5-TPS condition is explicit and is not timing acceptance.
Later narrow-view/frozen attempts lost the subject and are **not accepted** as
visual evidence. Root restores target20TPS/unfreezes at00:28:07Z, observes
FallFlying=0b at00:28:09Z, and captures `spyglass-landed-standing.png` at00:28:11Z
matching the initial standing pose. Thus the accepted live-20-TPS transition and
accepted closer pose images are distinguished from rejected capture attempts;
gametest coverage, not these slowed/frozen images, supports timing assertions.


1.21.10 completed logs are archived at `gui-logs/elytra-1.21.10-complete`, with
server/dummy/client hashes and a hash inventory of all images (including rejected
attempts, which remain explicitly distinguished above). Client renderer/world
instance shutdown completes00:28:42Z; server records all dimensions saved00:28:43Z.
No additional Voxy internal worker error appeared in this observed Fabric pairing.


## 1.21.11 real Elytra subject — complete normal-speed visual acceptance

Actual census: Fabric Loader0.18.4, MC1.21.11, LSS0.14.0,
Sodium0.8.2+mc1.21.11 and Fabric Voxy0.2.9-alpha. IPv6 connection is
`::1,25570` at 2026-09-09 00:31:08Z; v20/caps=7 handshake follows :09.
Server confirms survival subject equipped with Elytra at x160.5, observer x0.5.
At00:31:37Z client records tracked1/drawn1/tags1, new terrain2244 and
zero ingest_failed/parked/backlog. No injector is involved.

All accepted 1.21.11 observations run at ordinary20TPS. Root observes and captures
`gui-elytra-1.21.11/spyglass-standing.png` at00:31:39Z. Real Shift down00:31:58Z
and up00:32:01Z produce the clear changed wing angle in `spyglass-crouching.png`;
`spyglass-standing-recovered.png` at00:32:25Z restores the baseline pose.
At00:32:26Z the subject is teleported to y185 and receives actual space input;
server `data get entity SoakPlayer FallFlying` reports1b. Root's `gliding.png`
at00:32:27Z shows horizontal body plus visible wing. After ground return at
00:33:17Z, FallFlying=0b at00:33:19Z, and `spyglass-landed-standing.png` at
00:33:21Z matches the baseline. This monitor corroborates actual state/counters;
root's direct image inspection is the visual acceptance authority.

Complete client/server/dummy logs and all image hashes are archived at
`gui-logs/elytra-1.21.11-complete`. Client clean renderer/instance shutdown is
logged00:33:23Z; root confirms server stop00:33:24Z and dummy exit0. Both Elytra
version lanes are now complete, with no claim that 1.21.10 slowed/frozen attempts
constitute animation-timing evidence. The accepted normal-speed transitions and
standing recovery are explicitly identified for each line.
