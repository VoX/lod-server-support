# Controlled live lifecycle variants — monitor

Evidence root: `/home/vox/.local/state/lss-review/20260908-implementation`.
All timestamps below are UTC on September9 unless specified; source game logs
use local September8 EDT, four hours behind. Monitor performs read-only log/artifact
inspection and external evidence copies. Root owns all inputs, markers, launches,
config operations and cleanup. No production code is modified by these fixtures.

## Acceptance boundaries

WI5 uses the real existing receive-option catalog setter and `SaveHook.run(CONFIG)`
in the same client tick where actual Xaero diagnostics show queued>0 and native
pending_updates>0. It must immediately retire acquisition while preserving exactly
that native pending set without increasing native drops, let committed rebuilds drain
while OFF, and later complete genuine same-world/connection/sub-key ON handshake
with new manager and new terrain/Xaero writes above the pre-OFF baseline. Merely
installing the fixture or showing an inactive diagnostic is insufficient.

WI6 injects false at the actual Fabric send adapter admission boundary for the named
observer's empty OFF full-roster frame. It simulates **send-admission denial**, not
physical Netty unwritability or the channel-pressure sensor. Required evidence is
real accepted baseline roster+subject update, >=3 identical declined clear bodies at
one unpublished epoch, ON replacement roster+subject update at that held epoch,
and100 subsequent server ticks without obsolete empty-clear attempts. Sender true
means local admission; actual observer tracker/visible subject must corroborate receipt.

Fixture contracts read: `wi5-live-fixture/README.md` and
`wi6-transport-fixture/README.md` under E. Source-fidelity/build reviewers own their
independent assessment; this monitor records actual outputs and artifact identities.

## Running setup

Server A controlled log: `runtime/gui-server-a-controlled-console.log`.
It logs WI6 READY tick1 at00:38:36Z with expected observer/subject UUIDs and the
contained hold marker under `runtime/gui-server-a/wi6-hold-clear`. Real SoakPlayer
IPv6 login and v20/caps3 handshake occur00:40:08Z. WI5 is staged in both closed
modern client clones; staging alone is not a pass. Runtime results follow below.


## Fabric WI5 — exact positive-backlog PASS

Actual fixture markers at00:40:30.547Z PRECONDITION tick526 capture native world
539517535, network connection1805800828, Xaero world`Multiplayer_..1`,
sub-key`world-07f476339e08515f`, live manager1256025961, received2, Xaero queued1,
written1, pending_updates1 and dropped_updates0. AFTER_OFF at00:40:30.551Z in the
**same tick526** has manager0, decode/acquisition queue0, written1 unchanged,
pending_updates1 preserved exactly, dropped_updates0, owed0. This directly
exercises the narrow positive queued+native-pending premise, rather than inferring
it from a prior diagnostic followed by a later UI action.

OFF_NATIVE_REBUILDS_DRAINED at00:40:32.094Z tick557 has frame_flushes1,
pending_updates0, written1 and dropped_updates0. Repeated OFF_OBSERVE through
tick1000 keeps manager absent, no acquisition debt and no new native writes.
BEFORE_ON at00:40:55.146Z tick1018 reports24,592ms OFF; AFTER_ON same tick
sets receive=true/sessionConfig=false with manager still absent. Genuine server
handshake/config then yields PASS_SAME_WORLD_OFF_ON at00:40:56.944Z tick1054:
fresh manager1913293568, same native world/Xaero world/network connection,
new received count3 (baseline2), new Xaero written2 (baseline1), drops0.
Its newly committed pending_updates1 is ordinary resumed work, not the old retained
pending set; the required original set already drained while OFF.

At00:41:13Z normal client diagnostics show total proof2244, new bodies146,
Xaero written147 and queued/pending/owed0, with ingest_failed/parked0.
The difference between response146 and written147 is compatible with earlier
session work and must not be recast as a one-to-one cumulative response assertion.
Raw client/durable fixture/server checkpoint logs and installed candidate+fixture
jars are archived/hash-manifested at
`controlled-lifecycle-proofs/fabric-wi5-pass-checkpoint`. Client remains live.


Fabric WI5 incidental command qualification: at00:40:43Z while OFF, root invoked
only the first-stage `/lss reset` preview. Actual log says no active session and
requests `/lss reset confirm` to proceed. Root did not confirm; no cache wipe or
Voxy reset occurred. The WI5 pass therefore remains attributable to same-session
ordinary receive OFF/ON and honest backlog recovery, not a reset-assisted repair.

## Fabric server WI6 — real adapter-denial PASS

Before OFF, the unmounted subject initially fell; root installed a barrier and
repositioned at00:41:34Z. The actual held cycle begins afterward and root reports
no movement during OFF/ON. Baseline roster+subject update at epoch2 were accepted;
ARMED at tick3238 certifies that premise. OFF at00:41:36Z begins repeated identical
empty-clear attempts at epoch3, each declined at the actual send adapter. The
ON_ENTERED marker at00:42:03Z tick4320 reports549 held retries at epoch3.
The normal sender accepts the replacement full roster containing the real subject
and its matching update at tick4327, still epoch3. PASS_SEND_ADMISSION at00:42:08Z
tick4427 records100 following ticks, replacement_roster=true,
replacement_subject_update=true and obsolete_clear_attempts=0. The marker itself
explicitly labels scope `adapter_denial_not_physical_netty`.

Real client diagnostic00:42:06Z independently reports tracked1/epoch3/rosters3,
drawn1/tags1 and no epoch drops, corroborating actual receipt after ON (this
client sample precedes the terminal server100-tick marker by2 seconds). Root's
`far-restored.png` visibly shows the restored subject. This is not merely a
local sender-return assertion. No claim is made about actual socket buffering,
physical writability pressure, or more than the bounded100-tick no-obsolete-clear
window. Candidate receives ordinary mode changes and real participants throughout.

Complete Fabric client/durable WI5 log and post-WI6 server/dummy checkpoint logs
plus the installed WI6 fixture are archived at
`controlled-lifecycle-proofs/fabric-wi5-wi6-complete`. The independently hashed
installed WI6 jar matches
`3f6356b6bd3540700a2f4dfa2122c2f1a451c6335690c3e3ba4f9e96ba4397ac`.
Root confirms Fabric client clean close00:42:24Z and dummy exit0. Server continues
for the remaining NeoForge WI5 gate; its copied log is therefore not final shutdown.


## NeoForge WI5 — exact positive-backlog PASS

Native NeoForge uses only the named WI5 fixture during this run, with the working
modern Sodium/Voxy/Xaero pairing. PRECONDITION at00:43:16.830Z tick758 records
native world839883584, connection2112528377, Xaero world`Multiplayer_..1`,
sub-key`world-07f476339e08515f`, manager58095987, received2, queued1, written1,
pending_updates1 and native drops0. AFTER_OFF at00:43:16.833Z in the same tick
retires manager to0 and acquisition queue to0 while preserving pending_updates1,
written1 and drops0. OFF_NATIVE_REBUILDS_DRAINED tick798 at00:43:18Z shows the
retained native update naturally flushed while reception remains OFF. This is
about2 seconds after OFF, not an18-second drain duration.

BEFORE_ON reports21,994ms OFF; AFTER_ON tick1198 at00:43:38.831Z sets
receive=true/sessionConfig=false. Genuine negotiation supplies fresh manager
848936620; at tick1200 the fixture explicitly awaits fresh writes rather than
passing on negotiation alone. PASS_SAME_WORLD_OFF_ON at00:43:40.129Z tick1224
requires same native world/Xaero world/connection, fresh manager, old native
rebuilds drained, received2→3 and written1→2, drops0. New pending_updates1 at
that pass belongs to resumed commit work; the earlier retained set already drained.

Raw client/durable fixture/server logs plus the installed candidate jar are
hash-manifested at
`controlled-lifecycle-proofs/neoforge-wi5-pass-checkpoint`. The fixture itself was
already removed by root during cleanup before this monitor could hash the installed
copy; the final archive therefore preserves the built named artifact and existing
build manifest separately, without claiming an independent installed-copy hash.

## Exact controlled server flags

Read-only `/proc` evidence captured the actual Java server PID2647783 and its
containing launcher/tee in `controlled-lifecycle-proofs/wi6-server-commandline.json`.
Working directory is the contained `runtime/gui-server-a`. Java arguments are
Java21 `-Xms512M -Xmx2G -jar fabric-server-launch.jar nogui`; the **actual selected
environment value** is `JAVA_TOOL_OPTIONS=-Dlss.wi6.enabled=true -Dlss.wi6.observer=AstraValidator`.
These flags were inherited through the owned launcher, so they do not appear as
ordinary Java argv options. Only that named environment field was read/recorded.


## Final archive, identities and cleanup

`controlled-lifecycle-proofs/final-complete` preserves the complete Neo client and
durable fixture logs, complete controlled server log, and named Neo WI5 built jar
plus its build manifest. Its SHA256 is
`e3e96f7ed1b94bc179e9366a71a017e1491412599eb50eeb584558756e89cea2`.
Actual loaded fixture identity and hook behavior are demonstrated in the game log;
root confirms staging used `shutil.copy2` from that exact built named path to the
clone mods at00:39Z. There was no independent destination hash before cleanup;
provenance is the exact copy operation plus source hash, not a direct installed hash. Fabric installed WI5 hash was independently
captured as `602c96a765c0710f7031ef180a83fe1d2a64d4fe881490875bc20a698106e6b0`.
The unchanged installed candidate jars were independently archived/hashed:
Fabric `65e2486eb725f64b88bbc1abc1597cc41e82e4dde06b550b5b4148af0751e5bf`,
NeoForge `528e0ac5e50b437c594539e4935b42ce71661b6c993065d0e3fbad4ea6bd767e`.

Neo logs renderer shutdown00:44:20Z and instance shutdown00:44:21Z; controlled
server logs its normal stop/all-dimensions-saved sequence00:44:20Z. The final
`cleanup-readonly.json` records actual task-server PIDs absent, protected unrelated
server489815 present, both modern clone WI5 jars/markers absent, and server WI6
jar/hold marker absent. Root owns client process exit confirmation and removal.
The copied flags describe the now-ended task process, not persistent launch changes.

Verdicts: **WI5 exact positive-backlog OFF/ON PASS on actual Fabric and native
NeoForge; WI6 bounded real send-admission-denial/rapid-ON replacement PASS on
Fabric server with real observer receipt.** No physical Netty-pressure claim,
no cache-reset-assisted WI5 recovery, no synthetic Xaero backlog, and no
production-code modification is included in this evidence.


Root's final process check at00:45Z confirms both Windows observers absent; among
the relevant fixture/regular ports, only protected regular server489815 on25564
remains listening. Task server process absence and jar/marker removal were also
independently observed above. All files covered by the controlled-lifecycle archive
manifests were rehashed after finalization with no mismatch.
