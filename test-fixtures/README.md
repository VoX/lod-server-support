# Disposable rig fixtures

These independent projects are **not included in settings.gradle or shipping source sets**.
`tools/rig/check_packaging.py` checks every selected LSS/VSS artifact, including nested jars.

WI5, WI6 and WI9 source was imported from the retained 2026-09-08 external fixtures.
The maintained source requires an explicit `-Dlss.rig.runId=<run>` in addition to
scenario arming. Runtime helpers are outside the Mixin package. WI6 uses
`-Dlss.rig.serverRoot=<owned server directory>` and remains an **adapter-denial**
fixture; it does not demonstrate physical Netty saturation. Logs are private
until the scenario checker extracts allowlisted results.

Build each 1.21.1 Fabric fixture independently with a Java21 JDK and:

```
/path/to/gradlew -p test-fixtures/wi5-live-fixture remapJar \
  -PlssCommonJar=/exact/common.jar -PlssFabricJar=/exact/fabric.jar
```

WI5/WI9 `neoforgeFixtureJar` packages official-name classes with dedicated Neo
metadata and no Fabric refmap. Validate descriptors against each selected loader
before execution. The imported fixtures are **1.21.1-specific**; copying this
source to sibling branches does not establish sibling execution support.

`folia-region` is an independent Folia26.2-only plugin. Compile it using its
`build.py` with explicit Java25, cached Folia server and libraries inputs. The
plugin asserts owning-region access, assigns a connection identity per join,
records real entity-scheduler work intervals and performs bounded hash work only
inside the occupied chunk. Distinct constructor-assigned Folia region IDs identify
region lifetimes, including replacement after split/merge. The asynchronous
bounded evidence queue must not overflow. A one-region negative control is
selected with `-Dlss.rig.oneRegionControl=true`.

`check_regions.py` closes only the early two-client overlap feasibility gate.
The callback duration is **not full tick duration**; the long performance lane
also requires tick/frame instrumentation and an independent workload oracle.

`folia-timing` supplies the exact-bytecode observer for full owning tick spans and
separate tick-start delay. It pins `ConcreteRegionTickHandle` SHA256 and the
`tickRegion(JJJ)V` / `addTickTime(TickTime)` descriptors. Folia has already installed
the current region before these hooks run. The MC-free recorder is packaged in
a separate bootstrap observer jar because Paperclip does not delegate to the
application agent loader. Missing descriptors, target-byte changes, exceptions,
writer overflow and incomplete shutdown reject evidence. The agent never sleeps
on a tick thread or touches another region's state.

`folia-region/build.py` also requires `--lss-paper` for the explicitly selected
Paper candidate API. `prepare_folia.py` requires both `--fabric-candidate` and
`--paper-candidate`; it never selects a candidate from a filename glob.
The optional `--concurrent-client` stages the independent four-client target
consumer and enables the region plugin's target oracle. This is a diagnostic
lane, not a calibrated performance experiment. `check_workload.py` requires every
published target to be applied and committed by its current registered subject,
with real payload-body bytes, active acceptance ownership, bounded recovery, and
closed consumer writers. It also checks the genuine owning-tick overlap evidence.

The optional workload currently creates loaded target edits and explicit
send-admission/slow-consumer intervals. The server admission seam is an adapter
constraint, not evidence of physical Netty saturation. Source mix, churn,
full queue/debt drain, and the complete performance protocol are required
for a full P6b acceptance claim. Dated run results and outstanding gates are
recorded in the [implementation ledger](../docs/implementation/project-improvements-ledger.md). The concurrent client's required `FrameMixin`
records real `Minecraft.runTick(Z)V` frame intervals through its bounded writer.
The original game, loader, candidate and fixture bytes are pinned by the run.
The earlier `client-timing` class-hash observer is retained as diagnostic source:
Mixin transformation details varied between identical launches, so that observer
is not selected by the maintained runner and supplies no acceptance evidence.

The concurrent client records the actual payload `source` discriminator and
matches explicit gold, diamond, or bedrock target blocks. Server adapters can
publish `expected_source` and `target_ready` for prepared source lanes; the checker
rejects a body from another source. The Folia workload now keeps publishing during
Subject B's controlled disconnect, and the client requests one fresh connection
after five seconds. An independent `session_transfer` rebases unresolved targets;
old-session acceptance is discarded. See the
[implementation ledger](../docs/implementation/project-improvements-ledger.md)
for source/churn validation results, exact fixture identities, and remaining
acceptance gates. Fixture source on a support line does not establish native
execution or platform availability on that line.


The WI6 send-admission scenario uses three actual connected clients. It declines
only the selected observer's native empty full roster during far-players OFF.
The unaffected viewer must accept its own native clear during that same window;
this proves progress in the far-player control path, not LOD body throughput.
The original observer must receive the replacement roster and subject update at
the held successor epoch after ON. After at least 100 further ticks, a read-only
owner-thread observation requires its native clear/full-roster/control debt bits
to be false. The maintained checker independently binds the runtime properties,
READY identities, native joins and enabled v20 handshakes, validates the first
three identical refused retries and actual accepted baseline, and recomputes the
retained native report during collection. It does not claim physical Netty
saturation or test the channel-pressure sensor. The fixture remains 1.21.1-only;
source copies on sibling branches do not imply execution there.
