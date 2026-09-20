# Legacy Sodium clones and stationary far-player dummy — preparation only

Both requested legacy combinations are prepared. No original/modern profile was changed, no game/server/Gradle process was launched, and no GUI focus or screenshot was taken.

| Clone ID beneath Windows Prism instances | Pairing | Ready scope |
|---|---|---|
| `lss-astra-validation-fabric-legacy-1.21.1` | MC1.21.1, Fabric Loader0.19.3, Sodium0.6.13, Fabric API0.116.15, Xaero1.45.0, final LSS0.14.0 | Legacy Sodium UI + receive toggle + Xaero ordinary gates. No Voxy installed or claimed. |
| `lss-astra-validation-neoforge-legacy-1.21.1` | MC1.21.1, NeoForge21.1.248, native Sodium0.6.13, native Voxy0.2.9-alpha, Forgified Fabric API0.116.15+2.3.3, Xaero1.45.0, final LSS0.14.0 | Legacy UI + Xaero + native Voxy candidate. Compatibility is established from declared dependencies; actual loading/reset remains unrun. |

The absolute instance root is `/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances`. Fabric clone contains17 files /13,420,785 bytes; NeoForge21 files /119,978,108 bytes. Existing final candidate SHA256 values were preserved: Fabric `65e2486eb725f64b88bbc1abc1597cc41e82e4dde06b550b5b4148af0751e5bf`; NeoForge `528e0ac5e50b437c594539e4935b42ce71661b6c993065d0e3fbad4ea6bd767e`. Exact per-file hashes, copied-source hashes and embedded descriptors are in `prism-legacy-validation-clones.json`.

## Fabric download and dependency verification

A bounded search found only NeoForge0.6.13 artifacts in Prism profiles/Gradle cache/download folders; the parent then explicitly authorized the missing Fabric download. Queried the official Modrinth API for project sodium, Minecraft1.21.1, Fabric loader and selected exact `mc1.21.1-0.6.13-fabric`, version ID `u1OEbNKx`. Downloaded only its primary `sodium-fabric-0.6.13+mc1.21.1.jar` from the API-provided cdn.modrinth.com URL. Both API SHA1 and SHA512 matched downloaded bytes; embedded fabric.mod.json says Sodium0.6.13+mc1.21.1, Minecraft `[1.21,1.21.1]`, Fabric Loader>=0.16.0. API record, complete descriptor and hashes are preserved in `legacy-fabric-sodium-source.json`; downloaded bytes are in the external `downloads/` directory.

Official source: https://api.modrinth.com/v2/version/u1OEbNKx . Verified SHA1 `928a2598178c3a58b0638bab842f467d2e49251a`; SHA512 `13032e064c554fc8671573dadb07bc70e6ea2f68706c65c086c4feb1d2f664346a3414cbf9d1367b42b8d063a35e40f2f967ef9af31642e1f0093b852161fe91`.

The copied Fabric API jar's nested metadata satisfies all named Sodium API dependencies: block-view-api-v2 1.0.10, renderer-api-v1 3.4.0, rendering-data-attachment-v1 0.3.48, rendering-fluids-v1 3.1.6 (required>=2.0.0), resource-loader-v0 1.3.1. No missing declared Fabric dependency was papered over by filename inference.

## Native NeoForge pairing

The parked `voxy-0.2.9-alpha.jar.disabled` was read from `lss-test-neo-1.21.1/minecraft/mods`, verified as a native NeoForge artifact, and copied into the new clone as an enabled `.jar`. It is not the installed mismatched Voxy0.2.16/MC1.21.11 jar. The native Voxy declares Minecraft1.21.1, NeoForge>=21.1, Sodium>=0.6.13 and fabric_api>=0.102.0. The copied native Sodium declares MC1.21.1 and NeoForge>=21.1.82; the clone's21.1.248 satisfies both. Forgified Fabric API explicitly provides mod ID `fabric_api`, requires NeoForge>=21.1.169 and Minecraft[1.21.1,1.22), and satisfies Voxy's dependency. No Connector, ConnectorExtras, Roxy, modern Sodium, modern Voxy, Embeddium or duplicate LSS was copied.

Recursively inspected native jar-in-jar descriptors:51 provided mod IDs and zero missing required mod IDs after supplying Minecraft/NeoForge platform IDs. Xaero's required xaerolib is embedded at1.7.1. Dependency details are preserved in `legacy-neoforge-dependency-closure.json`. This checks declared completeness, not arbitrary binary compatibility or live SQLite/native loading.

## Storage and profile isolation

The new descriptors inherit the prepared clone's Java21 selection, empty account association, disabled automatic join, empty linked-instance list, and explicit disabled global pre/post/wrapper hooks/extra JVM arguments/environment overrides. No original account identity was copied. Each new clone has its own display name. Only descriptors/options, selected client configs and required mod jars were copied. All files are independent regular files, link count1; no symlinks/hardlinks. Copied-source hashes were unchanged after preparation.

No saves, `.lss`, `.voxy`, `.connector`, map data, storage backend configuration, server lists, usernames, account files or logs were copied. Modern Sodium options/fingerprints and modern Voxy config files were intentionally omitted so each legacy implementation generates its own defaults. Both LSS client configs already enable receive, Xaero bridge and Xaero backpressure. The config scan found no absolute Unix/Windows path, traversal component, file:// or redis:// backend reference. JavaPath is an intentional external executable reference in the launcher descriptor, not a storage destination.

Native Voxy's actual bytecode `VoxyClientInstance.getBasePath()` roots multiplayer storage in `Minecraft.gameDirectory/.voxy/saves`, then adds its server identity. Its config store is under FML's CONFIGDIR/voxy-config.json. The Flashback override exists in bytecode but no Flashback jar is installed. No existing storage configuration can redirect this fresh clone. This preparation is a prerequisite, not a blanket reset approval: after actual launch, inspect the loaded root/backend and any generated storage config, and let the candidate's normal reset/force-confirm confinement checks run. A failed root probe must remain a refusal; do not bypass it or delete paths by hand. Fabric has no Voxy, so its Voxy reset combination remains intentionally unavailable.

Launch mechanism, prepared only:

```bash
'/mnt/c/Users/Ian/AppData/Local/Programs/PrismLauncher/prismlauncher.exe' \
  --dir 'C:\Users\Ian\AppData\Roaming\PrismLauncher' \
  --launch lss-astra-validation-fabric-legacy-1.21.1
```

Use the NeoForge legacy clone ID for the other row. Launch only after parent schedules the GUI lane. The existing modern profiles/clones remain as prepared for their separate gates.

## Stationary dummy: exact task semantics

Source inspection: `fabric/build.gradle` soakClient block lines217–245 uses `build/run/soak-client`, fixed username `SoakPlayer`, and explicit quick-play server override `-Psoak.server`. `BenchmarkHook.initClient()` selects soak before benchmark mode; `initSoakClient()` has no elapsed-duration auto-exit. Passing benchmark.duration or an invented soak.duration does not bound it. The optional patrol walks indefinitely; leave it empty for the stationary OFF/ON smoke. Client snapshots append every100 client ticks after the first session-config reply (nominal5s), based on ticks rather than exact elapsed time. On disconnect it writes its last snapshot, drains pending cache IO and immediately halt(0)s. It does not reconnect after a kick.

The soak hook registers a no-op LOD consumer and therefore may acquire LODs; it is not a minimal network-only bot. `FarPlayerClientSupport.capabilityBitFor` explicitly suppresses its FAR_PLAYERS subscription, so use it as the subject visible to a normal Prism observer, not the observer verifying far-player UI. An ordinary non-subscribing player remains a valid subject for the server's player snapshots. Fixed SoakPlayer identity also means one such dummy per server; do not run competing tasks against the shared scratch directory.

The following scheduled command reuses the existing ownership helper/lock, Java21 and Xvfb. It refuses while any soak/benchmark owns shared scratch, even though its target server is25566. Do not run it concurrently with the parent's remaining runtime gates. It does not stage/delete scratch configs or worlds. The outer600s deadline bounds the whole launch, including Gradle and startup; it is not600s of joined play.

```bash
cd /home/vox/projects/lss-lines/1.21.1
timeout --signal=TERM --kill-after=20s 600s \
  env -u WAYLAND_DISPLAY \
  JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH" \
  bash -c '
    set -euo pipefail
    source scripts/lib/harness-lock.sh
    harness_acquire
    harness_run -- xvfb-run -a -s "-screen 0 1280x720x24" \
      ./gradlew --no-daemon :fabric:runSoakClient \
      -Psoak.server=localhost:25566 -Psoak.patrol= \
      -Psoak.probes= -Psoak.clientActionAt= -Psoak.dialect= -Psoak.summary=false
  '
```

Use the owned GUI fixture A console to confirm `list` shows SoakPlayer and the observer, place the dummy beyond vanilla player tracking distance but inside the configured far-player range on known supported terrain, then stop moving both players. Establish visible proxy first. Run console `lsslod set farPlayers off`, verify it disappears, then `lsslod set farPlayers on`, verify it reappears without reconnect or requiring dummy motion. Record the action times and observer evidence; task exit status alone cannot prove visibility. Finish with console `kick SoakPlayer Validation complete` and wait for cache-flush/client exit. Timeout124 or forced termination is bounded cleanup/incomplete validation, not a green result. Do not stop the regular server or the GUI fixture just to terminate the dummy.

The dummy acquires the existing25565 lock to protect its shared client scratch; the graphical observer is an isolated Prism clone and must run in the intentionally coordinated GUI lane. Keep the client log and current append-only soak-results/client.jsonl segment as evidence with wall-time bounds, rather than treating old appended rows as this run. No command above was executed during preparation.
