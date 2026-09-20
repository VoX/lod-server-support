# Isolated Elytra observer profiles and loopback servers — prepared, not launched

Prepared 2026-09-08 under parent authorization. No downloads, Gradle tasks, Minecraft/Prism/server starts, original-profile mutations or existing 1.21.1 fixture changes. Full source/destination SHA256 and dependency metadata are in `elytra-validation-fixtures.json`.

| MC | Prism clone ID | Server directory under evidence runtime/ | Loopback |
| --- | --- | --- | --- |
| 1.21.10 | `lss-astra-validation-fabric-1.21.10` | `gui-server-1.21.10` | `127.0.0.1:25568` |
| 1.21.11 | `lss-astra-validation-fabric-1.21.11` | `gui-server-1.21.11` | `127.0.0.1:25570` |

## Observer profile isolation and dependencies

Prism root: `/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances`. Each clone contains exactly nine files: copied matching `mmc-pack.json`; newly generated safe `instance.cfg`, minimal `options.txt`, minimal LSS client config and enabled Voxy config; exactly four enabled mods. No original options/config/account/server-list/world/cache/resource-pack/log/history files are copied. All selected original files were hashed before/after copying; destinations match. No symlinks or hardlinks.

Both clone descriptors explicitly select the existing Windows Java21.0.7 runtime, use3GiB maximum/512MiB minimum, disable instance-account selection and autojoin, and override inherited custom commands, environment and JVM args with empty values. Default account selection remains Prism-owned; no credentials or authentication args are read/copied.

| MC | Fabric Loader | Fabric API | Sodium | Candidate SHA256 |
| --- | --- | --- | --- | --- |
| 1.21.10 | 0.18.4 | 0.138.4+1.21.10 | 0.7.3+mc1.21.10 | `7c2c9c2516f923c5e3838e7ce49bcd4942674f45fa43d33d7bffaec1963b0e46` |
| 1.21.11 | 0.18.4 | 0.141.1+1.21.11 | 0.8.2+mc1.21.11 | `262c559b17e56bdaef9ce430c3b20666e65dc389a06059aa7d66d469effef2b0` |

Candidate sources are the exact final CI jars `fabric/build/libs/lod-server-support-fabric-0.14.0+MC.jar` in each reviewed line, installed under stable `lod-server-support-fabric.jar` names. Their hashes match the independent final artifact inspection. Fabric API and Sodium are copied from the matching existing profiles. All required top-level/nested dependency IDs are available; declared MC, Java, loader and API floors match. 1.21.10 has legacy Sodium0.7.3 (no modern config API);1.21.11 Sodium0.8.2 contains the modern config API. This preparation does not claim a live Sodium page compatibility check. Matching original Voxy0.2.9-alpha is included as the real LSS terrain consumer needed for the initial handshake:1.21.10 declares Sodium=0.7.3 and MC1.21.9/1.21.10;1.21.11 declares Sodium=0.8.2 and MC1.21.11. Both exact Sodium constraints match the staged jars; required Fabric API/loader floors also match. Xaero, ModMenu, Kotlin, Zoomify, YACL and Spark remain absent.

LSS client config explicitly enables far-player rendering/share-self/tags with the existing512-block animation distance and enables terrain reception with the real Voxy consumer (Xaero bridge remains off). The initial handshake requires both reception ON and an available consumer; retaining far-player capability during a later OFF transition does not bootstrap a never-handshook session. New minimal Voxy config explicitly enables rendering and ingestion and bounds service threads to2, with no personal storage path copied. Vanilla entity distance scale1, render distance6 and windowed mode are explicit. The clones are prepared for the wing gate; they do not certify terrain/Voxy reset or menu-toggle behavior before live execution.

## Server inputs and isolation

Each fixture has an independent copy of its matching line's `soak-worlds/base/world`; the `mc-version` marker was verified. Playerdata, stats, advancements and session.lock are excluded. No real-map world, account cache, original ops/config or LOD cache is copied. Every output is a regular independent file with link count1; no writable runtime path points back at an original.

The local standalone installer launchers embed the correct MC and Fabric Loader0.19.3. Their original locations had no populated Fabric install cache. To avoid downloading or running an installer, each fixture uses its matching cached raw Mojang server bundle (SHA1 verified against the line's cached Mojang version metadata), with all bundled libraries/version jars extracted and SHA256-verified against the bundle lists. Loader0.19.3 dependencies are independent copies from the earlier verified local cache, and each line gets its exact official→intermediary mappings from Prism's local library cache. The small fixture-local launcher manifest is generated from the verified0.19.3 template with the correct line-specific intermediary path; all eight manifest paths resolve inside that fixture. This generated launcher metadata is distinct from the unmodified final LSS candidate. No game/dependency class is rewritten.

Each server has only Fabric API and final LSS. New config binds loopback, disables RCON/query/online authentication/secure-profile enforcement, limits4players, uses creative/peaceful mode, allows flight, view6/simulation4 and farPlayers=on. Terrain generation by LSS is disabled and lodStore=off for the focused rendering gate. Vanilla movement may still generate fixture terrain. EULA acceptance is fixture-local under the existing rig authorization; ops starts empty. No server is started.

Ports25568 and25570 were free at preparation; re-check before launch. Preserve protected25564, harness25565 and existing1.21.1 fixtures25566/25567. Runtime scheduling still belongs to the parent.

## Scheduled launch routes — not executed

```bash
/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-1.21.10/launch.sh
# In a separate scheduled run:
/home/vox/.local/state/lss-review/20260908-implementation/runtime/gui-server-1.21.11/launch.sh
```

Each launch script passed `bash -n`; it changes to its own directory, clears inherited JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS, then execs Java21 with512MiB initial/2GiB maximum and the standalone launcher. Use an owned foreground PTY for console commands and clean `stop`; no RCON, detached launcher, Gradle daemon or shared run directory is required.

Launch one prepared Prism clone by its instance ID through the already verified Prism executable and `--dir C:\Users\Ian\AppData\Roaming\PrismLauncher`; manually direct-connect to `localhost:25568` or `localhost:25570` after the server is ready. No server list/autojoin entry is installed. Windows-to-WSL loopback, actual mod loading, title screen/render behavior and clean shutdown remain runtime observations.

## Remaining live gate inputs

The observer and server are prepared with no known missing declared mod dependency. A separately controlled second player/target still needs to join the matching fixture, equip an Elytra and cycle standing/crouching/gliding beyond vanilla tracking range while inside512blocks; the existing parent-controlled dummy/soak-client route can provide that target, but it was not launched or copied by this task. Record actual loaded versions, candidate hashes, target/observer positions, pose timeline and visible wing interpolation. A successful title screen alone does not close WI15. No seated-error injector or optional-plugin replacement fixture is introduced.


## Initial-handshake correction

The first preparation incorrectly set reception OFF and omitted every consumer. Parent source review caught this before launch: `ClientSessionGate.onJoin` returns at the receive/consumer guards before announcing, and `FarPlayerClientSupport` documents the never-handshook case. Both isolated clones now have reception ON and their original matching Voxy jar plus a fresh enabled Voxy config. No alternate far-only bootstrap was established. No no-op consumer or production hook was added. This correction affects only the new clones and evidence; original profiles, server fixtures and final LSS candidate bytes are unchanged.

- 1.21.10 Voxy SHA256: `f52b817f44a4407d68d28243b1cfbac8c7ff29ae1bc0b342ced033959c56e984`.
- 1.21.11 Voxy SHA256: `7a640f888c11f33d765dd6b7e6666538d04c1a5ea0e2160bd02acfa5ad6f6ef4`.

Nine manifest files per clone all revalidated; Voxy embedded Windows/Linux LMDB and Zstd natives remain stock inside its jar. Actual native loading, Voxy bridge registration and completed server handshake must be confirmed in the later launch logs.
