# Paper and NeoForge command fixtures — prepared, not launched

Prepared 2026-09-08 under parent authorization. No builds, downloads, installers, servers or clients started; no original profile, world, config, account/ops cache, RCON env or protected server changed/read beyond the permitted runtime/source inputs. All exact source/destination SHA256 values are in `platform-command-fixtures.json`.

| Platform | Fixture directory under evidence `runtime/` | Loopback | Files / bytes |
| --- | --- | --- | --- |
| paper1.21.1 | `command-server-paper-1.21.1` | `127.0.0.1:25572` | 157 / 233,397,133 |
| neoforge1.21.1 | `command-server-neoforge-1.21.1` | `127.0.0.1:25571` | 119 / 120,373,474 |

## Runtime inputs and isolation

Paper inputs come from `/home/vox/projects/lss-port-1.21.1/test-server/paper`: stock `paper.jar`, its listed103 libraries, patched `versions/1.21.1/paper-1.21.1.jar`, and original-bundle `cache/mojang_1.21.1.jar`. Every listed library and patched server hash matches Paperclip’s embedded lists. The patched game version.json is1.21.1. The cache is launcher data, not a personal map/authentication cache. Only the final LSS plugin is installed; no external plugin or old plugin data/config is copied.

NeoForge inputs come from the sibling `test-server/neoforge`: installed21.1.248 `unix_args.txt`, its runtime module/classpath jars, and the four extra game artifacts selected by the actual FML production-server provider (Minecraft srg/extra and NeoForge server/universal). All70 required runtime jar paths exist inside the new fixture. FML4.0.43 launch-handler bytecode was inspected to derive the extra paths; no guessed installer output or installer-time patch tooling is needed. No original user_jvm_args, env files, defaultconfigs, mods or world is copied. The only external mod is final LSS; stock nested SQLite/Zstd ride in that previously inspected candidate.

Disposable world sources: Paper uses the current `1.21.1/soak-worlds/base-paper/{world,world_nether,world_the_end}`; NeoForge uses `soak-worlds/base/world` with its nested dimensions. Both markers read1.21.1. Independent copies exclude playerdata, advancements, stats, session.lock and any inherited LSS/VSS LOD-store directories. Neither touches the real-map world. Every selected source file matched its before/after-copy hash; every output hash is verified, with no symlinks/hardlinks.

The new configs bind127.0.0.1, disable query/RCON and online/secure-profile checks, permit4players in creative/peaceful mode, view6/simulation4, enable farPlayers, disable LSS generation and disable the LOD store. EULA acceptance is local to the authorized disposable fixture and ops starts empty. Vanilla can still write/generate fixture data. Ports25571/25572 were free when checked; re-check before launch.

| Platform | Exact final candidate SHA256 |
| --- | --- |
| paper | `5399264a3b2d5b20babd36a6659861d45587932e07e4b9eef874c2130bbfd1d8` |
| neoforge | `528e0ac5e50b437c594539e4935b42ce71661b6c993065d0e3fbad4ea6bd767e` |

Sources are the exact `lod-server-support-{platform}-0.14.0+1.21.1.jar` files under the reviewed line’s build/libs; hashes match the final artifact inspection. Neither candidate was modified. Paper metadata declares `name: LodServerSupport`, `api-version:1.21.1`, and the actual plugin main class. Neo runtime args declare MC1.21.1/NeoForge21.1.248. No Fabric API, Voxy or Sodium is necessary for these dedicated server command gates.

## Interactive launch — not executed

```bash
/home/vox/.local/state/lss-review/20260908-implementation/runtime/command-server-paper-1.21.1/launch.sh
# Schedule separately:
/home/vox/.local/state/lss-review/20260908-implementation/runtime/command-server-neoforge-1.21.1/launch.sh
```

Both scripts passed `bash -n`. They cd to their own fixture and exec `/usr/lib/jvm/java-21-openjdk-amd64/bin/java` with512MiB initial/2GiB max, clearing inherited JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS/_JAVA_OPTIONS. Paper runs `-jar paper.jar --nogui`; NeoForge runs its local installer-generated unix_args file and `nogui`. Use a retained foreground PTY for console commands and clean `stop`; no Gradle, detached daemon, shell pipeline, FIFO or RCON is involved. Startup, actual loaded candidate and no-download launch remain runtime checks; preparation does not claim successful startup.

## Exact config ownership and later write-failure procedure

| Platform | Config directory relative to fixture | File | Prepared permissions / owner |
| --- | --- | --- | --- |
| paper | `plugins/LodServerSupport` | `lss-server-config.json` | directory0755, file0644; UID1000/GID1000 |
| neoforge | `config` | `lss-server-config.json` | directory0755, file0644; UID1000/GID1000 |

Paper resolves `PaperConfig.load(getDataFolder())`; plugin metadata therefore selects its dedicated `plugins/LodServerSupport/` directory. NeoForge resolves `LoaderServices.configDir()`, which is the fixture’s **shared loader `config/` directory**, not a dedicated LSS subfolder. It may contain NeoForge-created config files after startup. A brief directory write denial there is fixture-local but also prevents unrelated config saves during the same interval; do not initiate other reload/config operations in that interval. No narrower existing LSS directory can be selected without changing product configuration-path behavior.

`JsonConfig.trySave` writes sibling `lss-server-config.json.tmp` and atomically replaces the JSON. Making only the JSON file read-only is insufficient; deny directory writes after successful startup and baseline save. The server should run as the prepared owner UID1000, not a root process that bypasses Unix mode bits. The following is a later controlled procedure, not an action performed by this preparation:

1. Start one fixture; wait for actual LSS enable. In its console run `lsslod set` and `lsslod set farPlayers on` to establish the readable baseline. Record the config file SHA256 and the directory’s actual current mode. Verify the `.tmp` sibling is absent before the denial window.
2. From the parent’s separate shell, set only the exact listed fixture config directory to0555. Do not chmod recursively, alter the fixture root/world/plugins parent, or target any original path. Keep that directory readable/searchable so only persistence is denied.
3. In the owned server console run `lsslod set farPlayers off`. Expect runtime application to succeed and the reply to include `applied, but not saved — see server log`; confirm `lsslod set` reports farPlayers=off, the log reports the save failure, and the on-disk JSON hash is unchanged. This verifies WI10’s runtime/persistence distinction on that platform.
4. Restore the directory’s recorded mode immediately (prepared default0755), **before shutdown**, then run `lsslod set farPlayers on` and confirm saved success and listing=on. Use a finally/trap around the short denial window so an interrupted observation restores directory mode. No permissions have yet been changed for injection by this preparation.
5. If a real far-player viewer/target is connected, observe proxy withdrawal/restoration during those same commands for WI6. With no clients, console/listing proves command apply and feedback only; it does not prove roster delivery or visual disappearance. Stop the exact owned server cleanly.

No live command acceptance is claimed yet. The fixtures remove the known launch/dependency prerequisites; the parent can now run bounded platform command gates without risking the original test rigs.
