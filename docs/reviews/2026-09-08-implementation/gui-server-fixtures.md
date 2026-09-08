# Two disposable GUI fixture servers — prepared, not launched

Prepared 2026-09-08 for parent-authorized subsequent GUI validation. Both directories contain91 files /144,970,880 bytes. No server or launcher was started. The normal real-map server and all Prism original profiles were untouched.

| Fixture | Directory | Listen address |
|---|---|---|
| A | `/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-a` | `127.0.0.1:25566` |
| B | `/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-b` | `127.0.0.1:25567` |

These ports were unoccupied at preparation inspection; re-check immediately before the scheduled launches. Both are distinct from the preserved regular server25564 and soak harness25565. The fixtures do not take the harness25565 lock; the parent must still schedule runtime CPU/memory use serially. Two servers plus a GUI client require their corresponding combined memory budget.

## Inputs and isolation

- Launcher and dependency source, read-only: `/home/vox/projects/lss-port-1.21.1/test-server/fabric`. Copied `fabric-server-launch.jar`, `libraries/`, `versions/`, and `.fabric/server/`. The launcher embeds `game-version=1.21.1` and `fabric-loader-version=0.19.3`. Its `.fabric/server` cache is necessary for the standalone launcher, so it was included; processed mods, remapped jars, C2ME caches, runtime config/logs and the real world were excluded.
- Exactly two mods: copied matching source `mods/fabric-api.jar` (embedded version0.116.15+1.21.1; supports MC1.21 through below1.21.2, Java21+) and the final `/home/vox/projects/lss-lines/1.21.1/fabric/build/libs/lod-server-support-fabric-0.14.0+1.21.1.jar`, installed as `mods/lod-server-support-fabric.jar`. No C2ME or old LSS jar was copied.
- Disposable world source: `/home/vox/projects/lss-lines/1.21.1/soak-worlds/base/world`, whose sibling `mc-version` reads1.21.1. World region/entities/POI/level data and dimensions were copied independently. Player data, advancements, stats and session.lock were excluded. No real-map world, account cache or source ops list was read/copied; each fixture has a new empty `ops.json`.
- Every destination is an independent regular file (link count1), with no symlinks/hardlinks back to originals. The source launcher/dependencies/world/mods are never referenced by a writable runtime path. All selected source hashes matched before/after preparation, and all destination hashes were verified. All exact source/destination hashes and the workspace HEAD at preparation are in `gui-server-fixtures.json` beside this report.
- EULA is accepted in each disposable fixture under the task's existing rig authorization.

Key SHA256 values, identical in both fixtures:

| Artifact | SHA256 |
|---|---|
| Fabric launcher | `a9822390dd1df52e7de381ca3d0a1431d565a19f5c70c38eafb197944703cc71` |
| Fabric API | `a61a10f730ab8aa45ff42486ee65699e6e51c28a0c168deb161e7d4029473aa3` |
| Final LSS Fabric0.14.0 | `65e2486eb725f64b88bbc1abc1597cc41e82e4dde06b550b5b4148af0751e5bf` |

## Fixture behavior

New minimal `config/lss-server-config.json`: enabled=true, requireServicePermission=false, lodDistanceChunks=256, enableChunkGeneration=false, lodStore=on, lodStoreBackfill=true, lodStoreBackfillColumnsPerSecond=200, diskReaderThreads=2, maxConcurrentDiskReads=2, enableRegionSummaries=true, dirtyBroadcastIntervalSeconds=5, farPlayers=on. Other settings follow the candidate's defaults. LSS generation is disabled; normal vanilla movement/spawn loading can still generate terrain in the disposable world, and the store necessarily writes its own local SQLite data. This is not a filesystem-read-only server.

`server.properties` binds loopback explicitly, disables query/RCON, uses offline-mode with secure-profile enforcement disabled for the local fixture, caps players at4, view distance6/simulation distance4, forces creative mode, uses peaceful difficulty, disables PVP, and permits flight. Spawn protection0. The store/world paths remain inside each fixture. A and B intentionally use identical world bytes; only port and MOTD differ. This supports connection/session switching, not a claim that different seeds were exercised. The prepared base covers a bounded region: range256 permits distant requests but does not imply a fully generated256-chunk disk. Out-of-base requests must receive ordinary no-generation outcomes.

The isolated Prism clones already have receiveServerLods=true, enableXaeroMapBridge=true and enableXaeroMapBackpressure=true; no further Prism file changes were needed for this task.

## Exact launch and manual console route — NOT executed

Use a foreground PTY and keep the returned session open. Run only when parent releases the runtime slot. For A:

```bash
/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-a/launch.sh
```

For B, when specifically scheduled:

```bash
/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-b/launch.sh
```

Each script changes into its own directory then `exec`s exactly:

```bash
/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms512M -Xmx2G -jar fabric-server-launch.jar nogui
```

The scripts passed `bash -n`. There is no Gradle, background daemon, detached `nohup`, external log pipeline, FIFO or RCON dependency. With `exec_command`, request `tty:true`, `yield_time_ms:1000`, and retain the returned session ID. Vanilla logs remain in the fixture's `logs/latest.log`; preserve PTY output as additional runtime evidence. Send console commands with `write_stdin` to that exact owned session, for example `list\n` or `stop\n` (both vanilla). Use server console for temporary fixture-local commands rather than importing personal ops identities. Wait for clean shutdown and the session's exit before starting a conflicting runtime lane; never target the regular server PID/process group.

Connect a scheduled Prism clone via direct connect to `localhost:25566` or `localhost:25567`; Windows-to-WSL localhost reachability has not been exercised by this preparation. If it fails, diagnose the local forwarding prerequisite rather than rebinding either fixture to a non-loopback address. No server list or auto-join entry was installed in Prism.

These are prepared integration fixtures, not completed gates. Startup, loaded mod list, SQLite store initialization, client handshake, GUI toggle/reconnect/reset behavior, and shutdown must still be observed and recorded. The launcher dependency cache is copied to avoid routine downloads; no offline startup guarantee is claimed without an actual launch.
