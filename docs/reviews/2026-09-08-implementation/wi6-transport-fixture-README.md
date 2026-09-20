# WI6 isolated live send-admission fixture

Built and package-verified on 2026-09-08/09; no staging or game launch performed by the builder. This separate external project leaves the accepted WI9 source/artifacts unchanged and adds no production hook or shipped code. Exactly two classes: runtime `dev.vox.lssfixture.wi6.Probe`, outside mixin-owned `dev.vox.lssfixture.wi6.mixin`, and `ServiceMixin` inside it.

## Scope and decisive observations

Production reference: `xplat/.../RequestProcessingService.java:758` sendFarPlayerFrame normally returns false when FabricChannelPressure reports NOT_WRITABLE. `common/.../FarPlayerBroadcastService.java:248` consumes that same boolean and retains the pending clear on false. The mixin cancels this real adapter at HEAD with false only for the named observer's empty full roster during actual server farPlayers OFF. It neither writes packets nor edits broadcaster membership/epoch state. Therefore this tests actual platform/broadcaster integration under **send-admission denial**, not physical Netty pressure or the channel-pressure sensor itself.

At RETURN it observes true only after the normal product sender has called LoaderServices.sendToPlayer and charged bandwidth. This is accepted local send admission, not an end-to-end ACK; the root must also inspect the real observer's tracker/rendered subject after ON. The fixture consults the real server player list and actual LSSServerConfig; it does not synthesize participants, mode changes or updates.

An actual accepted full roster containing SoakPlayer, followed by an accepted same-epoch subject update, establishes the ON premise. A fixed fixture-owned marker arms the next cycle. Require ARMED before OFF. The fixture then requires at least three identical declined clear bodies/epochs (baseline+1), proving the broadcaster retried without consuming the unpublished epoch. ON releases admission automatically without deleting the marker. Require the real sender to accept a full replacement roster containing that same subject at the held epoch and a matching subject update. The following 100 server ticks must contain no obsolete empty full-roster attempt, participant departure, hold-file removal or new OFF. No replacement within 200 ticks or OFF exceeding 1200 ticks fails. PASS is bounded to those 100 ticks; after PASS the one-shot fixture becomes inert, allowing ordinary cleanup without spurious failures.

All markers use SLF4J info logger LSS-WI6-Fixture and appear as `[WI6-FIXTURE]`. Any FAIL_OR_INCONCLUSIVE, missing marker, timeout, unsupported identity or no terminal PASS is not accepted. Marker failures stop fault injection; the fixture does not keep an accidentally failing observer permanently withheld.

## Artifact and build

Artifact: `build/libs/lss-wi6-transport-fixture-1.0.0-test-only.jar`

SHA256: `3f6356b6bd3540700a2f4dfa2122c2f1a451c6335690c3e3ba4f9e96ba4397ac` (9212 bytes).

Build completed in 9 seconds, exit0 under owned-process.py, Java21, --no-daemon and --max-workers=1. Build log `../wi6-transport-build.log`; receipt `../wi6-transport-artifact.json`; bytecode `../wi6-transport-javap.txt`. An AP recommendation to specify the public LSS target by class literal is the only compile warning. Server-only Fabric metadata, a generated refmap (no Minecraft mixin target needs obfuscation entries here), remapped MC bytecode, SLF4J calls, exact two-class inventory, no nested jars or bundled LSS/MC/dependencies all checked. Actual loader application remains a runtime requirement.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" \
python3 /home/vox/projects/lss-lines/1.21.1/scripts/lib/owned-process.py -- \
/home/vox/projects/lss-lines/1.21.1/gradlew --no-daemon --max-workers=1 \
-p /home/vox/.local/state/lss-review/20260908-implementation/wi6-transport-fixture remapJar
```

## Root-owned live recipe — prepared, not executed here

Only server A is permitted. The helper fails closed unless process CWD resolves to `/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-a`, that root is not a symlink and the actual server has authentication disabled. No configurable arbitrary marker root is provided. Hold marker must be an ordinary file, not a symlink:

`/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-a/wi6-hold-clear`

Stage the fixture jar into only server A's mods, retaining the exact unchanged Fabric LSS candidate. Start server A with the added JVM flags (do not change the prepared launch.sh):

```bash
cd /home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-a
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms512M -Xmx2G \
  -Dlss.wi6.enabled=true -Dlss.wi6.observer=AstraValidator \
  -jar fabric-server-launch.jar nogui
```

Keep stdin/PTY for normal console commands and stop. No shared or regular server is involved; preserve protected port25564. Use the ordinary observer and SoakPlayer with offline UUIDs. Observer default AstraValidator may be overridden by exact offline name property; subject is fixed SoakPlayer. Helper validates the derived offline UUIDs and actual game-profile names, and rejects identical participant identities. For default observer the root can retain the existing isolated modern Fabric+Voxy client and its visible far-player setup. There is no client injector in this gate.

1. Start at `lsslod set farPlayers on`. Connect both actual participants, place SoakPlayer stationary beyond native entity tracking and within far-player range, and establish a visible subject. Wait for BASELINE_ROSTER and BASELINE_UPDATE in server A latest.log.
2. Create only the fixed marker: `touch /home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-a/wi6-hold-clear`. Wait for ARMED with actual_roster_and_update_accepted=true. Creating it before baseline is also safe, but does not skip the wait.
3. Console `lsslod set farPlayers off`. Wait for OFF_ENTERED followed by CLEAR_DECLINED counts1,2,3 with one epoch. This simulates an unwritable send admission; the client may retain the old proxy while the clear remains withheld, which is intended.
4. Console `lsslod set farPlayers on` with the marker still present. Require ON_ENTERED with held_retries>=3, REPLACEMENT_ROSTER_ACCEPTED at held_epoch, then REPLACEMENT_UPDATE_ACCEPTED. Do not change subjects/privacy/dimension/mode during this window.
5. At least100 server ticks later require PASS_SEND_ADMISSION with replacement_roster=true, replacement_subject_update=true, obsolete_clear_attempts=0 and no FAIL_OR_INCONCLUSIVE. Capture the actual observer's `/lss diag` and visible subject/name tag after the window. Return=true alone does not prove client receipt.
6. Archive complete server/client logs, screenshots, installed fixture/product hashes and command timestamps. Keep PASS/admission scope distinct from physical channel-pressure testing. Then close only owned clients, stop server A cleanly, remove its fixture jar/marker and JVM flags before later unrelated use. No product jar/config or original Prism profile needs modification.

Existing permanent `FarPlayerModeTransitionTest.withheldWithdrawalRetriesAndRapidOnSupersedesOldClear` also covers releasing a declined clear while still OFF and rapid supersession. This one live cycle specifically exercises rapid ON superseding the still-unaccepted clear through the real adapter. No broad traffic stress or two-viewer pressure matrix is claimed.
