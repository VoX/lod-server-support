# Four-client source correctness fixture (26.2 native only)

`SourceWorkload` is shared by the separate `paper-concurrent`,
`fabric-concurrent` and `folia-source-concurrent` adapters. These are test artifacts, outside every shipping
module. Both adapters passed Java API compilation against the current candidates and
actual native 26.2 Fabric/Paper dependencies; live controls remain pending.
It is not a measured performance preset or a completed P6b acceptance result.

Before clients launch, each adapter uses an independently prepared flat-world
snapshot and seeds three source columns per subject, retains the loaded-column ticket, flushes disk, deposits serialized
V20 bytes for the store target, and waits for the other targets to unload. It
checks region-header presence, actual store frame hit/miss, and absence of the
untouched generation target before printing `LSS_RIG_SOURCES_READY`. Generation
expects the flat world's bedrock at minimum Y. Target positions are four separate
corners at offsets (+8,+8), (-8,+8), (+8,-8), (-20,-20), around four centers 4096
blocks apart. Readiness is a prerequisite, not source attribution: the existing
independent client fixture captures the actual payload source byte, and the
checker requires source 0/3/1/2 respectively.

After all four owner-thread teleports and actual LSS product registrations, a
shared monotonic schedule applies (vanilla joins alone do not start the clock):

| Seconds | Action |
| --- | --- |
| 160–180 | Subject D defers consumer acceptance; actual deferred/released receipts are required |
| 170 | Loaded-column edit for all four subjects |
| 200–220 | Subject C receives NOT_WRITABLE admission snapshots; only reads during this fault count |
| 210 | Second loaded-column edit |
| 360 | Subject B is disconnected; existing client fixture reconnects after five seconds |
| 362 | Third edit, published independently while B is offline |
| 420 | Explicit offers-close marker |
| 420–540 | Two zero-debt observations at least one second apart, with all four product registrations still present |

Each connection stays denied until the independent client has parsed its oracle
and acknowledged the current connection identity. Quit clears that requirement;
a successor cannot reuse the old connection's acknowledgement. Loaded targets
are offered before a native gold-to-diamond mutation, so an earlier cached gold
body cannot satisfy them. The three diagnostic edits use distinct retained
cells at local chunks (12,0), (13,0), and (14,0), and remain current until receipt.
The client retires old acceptance leases. B's pending target transfers to the
successor session and is applied only after its current acknowledgement.

Every target carries an explicit per-cell revision/predecessor. A bounded native
owner ledger records the actual mutation timestamp and revision. The checker
requires actual receipt and commit before any successor mutation; an overwritten
target never passes merely because a later revision returns to the same block.
An independent wire capture is associated with the exact queued payload object,
then the actual receipt callback, rather than a coordinate/timestamp lookup.
All new source scenarios require schema 2, including initial targets.

On Folia, retained corridors connect the loaded targets to the actual player's
region. Readiness observes real player-region ownership; each mutation repeats
the native ownership check. Paper/Fabric observe the actual native server-thread
predicate, thread ID and mutation owner. They do not invent region identifiers.

The workload pins radius 32, generation global 4/per-player 1, store on and backfill
off. A separate native Paper fixture, with no LSS plugin, pregenerates a radius 34
square around each center and saves all 19,044 columns as FULL. An explicit
construction step copies the closed world and removes four generation-target
records from region/entity/POI headers, preserving the original. A normal native
source-seed startup must then reprove initial/final disk, store and protochunk
absence at all four holes, source readiness, and all 19,040 surrounding FULL
columns before a new snapshot is accepted. This bounds fresh terrain within the
normal scanner's declared geometry without introducing a targeted request API.
Source readiness alone does not prove delivery or performance.

Measured mode retains the separately registered 720-second, 23,040-offer schedule:
256 cells per subject, eight edits/second/subject, no catch-up bursts. The 32-second
revisit interval remains an actual current-revision delivery deadline; the 120-
second recovery ceiling never permits acceptance after a successor mutation.
Repeated-edit targets explicitly declare `allowed_sources: [0,1,3]`: live memory,
disk, or the LOD store may deliver the current block. Their `expected_source: 0`
records the independently verified loaded premise; it is not a routing promise.
Folia intentionally releases held requests after one tick even when an owner
probe is late. Generated source2 and unknown sources cannot satisfy these edits.
The initial four-source probes and sparse diagnostic targets keep their exact
source assertions. All original block, wire/body association, revision, receipt,
lease, every-edit and fixed schedule checks still apply. A matching old or later
revision cannot pass because its route is allowed.
Calibration and measured comparisons remain separate gates.

## Build and materialize (serialized Java slot required)

Select exact cached inputs. If the native Paper cache has not been expanded,
`tools/rig/materialize_paper_cache.py --paperclip "$task_paper_launcher"
--mojang "$task_cached_mojang_jar" --output "$task_new_paper_cache"` reconstructs
it offline without a Java process or world access. All patch inputs, patched
outputs, and final dependencies must match the embedded Paperclip SHA256 lists.
The 26.2 build119 closure has passed that check (106 files). Use the resulting
native Paper server and libraries for the final API compile:

```bash
python3 test-fixtures/paper-concurrent/build.py --java-home "$task_jdk" \
  --paper-server "$task_paper_server" --libraries "$task_paper_libraries" \
  --lss-paper "$task_paper_candidate" --output "$task_paper_fixture_out"
python3 test-fixtures/fabric-concurrent/build.py --java-home "$task_jdk" \
  --classpath-file "$task_complete_native_compile_classpath" \
  --output "$task_fabric_fixture_out"
```

Inside the inherited rig lock, `tools/rig/prepare_paper_concurrent.py` and
`prepare_fabric_concurrent.py` accept a reviewed existing four-client runtime and
profile, copy its independent client launches, and construct a separately hashed
server profile. Run `--help` for the explicit input arguments. Paper requires its
own cached libraries/versions/cache and launcher. Fabric requires a reviewed
**dependency-only** native server classpath; project output directories are
rejected and the chosen candidate is added explicitly. Both require the chosen
server candidate, built server fixture and checked `--world-snapshot`. They write a 550-second diagnostic
scenario; they do not launch it or claim acceptance. The server startup marker
waits for source preparation, rather than accepting vanilla's earlier Done line.

The maintained runner produces and recomputes the strict `concurrent-sources`
proof after orderly shutdown and during collection. It invokes the same source,
current-revision and debt checks on all three native platforms. Folia additionally
requires actual owning-region/tick overlap. Every platform requires current
product registration intervals and real v20 handshake evidence. A direct
`check_concurrent_sources.py` diagnostic is only a subset of this acceptance.
Preregister every debt bound.
The source pins establish held_sync=200, held_gen=1, send_queue=1024,
backlog=1024, generation.active=4 and store.queue=1024. `debt-bounds.json` also
registers disk.pending=800, derived from four registered subjects × 200 held
SYNC slots and one completion per admitted disk request. See
[the accounting proof](debt-bound.md). This is a workload acceptance ceiling;
the underlying completed-result queue is not a bounded collection. Unexpected
disk-delivery failure logs disqualify the one-result premise and fail the lane.

Historical preparation receipts record earlier artifact hashes and plan results;
they do not certify the current strict fixtures. Recompile each changed fixture,
materialize exact candidate/dependency/snapshot identities and run `rig plan`
before any new acceptance attempt. Failed runs remain evidence.

The fixture source still targets explicit 26.2 native APIs even when mirrored into
sibling worktrees. It is never part of a shipping artifact.

The generation-only offset is (-20,-20), within radius 32. Native Paper's startup
spawn halo persisted a structure_starts protochunk at the former subject A
(-8,-8) location; the failed measured seed retained that evidence. All four new
locations were absent after the same 1,024-cell seed/save. Before any fixture seed,
each adapter now records four initial generation facts and rejects disk, store,
full chunk or pending native protochunk presence. The final source facts repeat
that absence check. Paper/Folia use the native dimension-rooted Bukkit folder;
Fabric resolves the engine's DimensionType.getStorageFolder API. These checks
remain independent of actual client payload.source and body correctness gates.
