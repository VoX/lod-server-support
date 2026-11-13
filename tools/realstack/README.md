# Real-stack test matrix

The unit tests and gametests run LSS alone. Players run it beside Sodium, Voxy, Xaero's World Map,
C2ME and the rest, and most real failures involved one of those. This tool boots the stacks players
actually run (the published mods, a real server and a real production client) and checks that LSS
works in them. It runs locally, weekly and before a release; it is not part of GitHub CI.

## Run a row

```sh
python3 tools/realstack/realstack.py doctor                 # what this machine is missing
python3 tools/realstack/realstack.py list                   # the matrix
python3 tools/realstack/realstack.py provision <row>        # download and verify (optional)
python3 tools/realstack/realstack.py run <row> --lss published:0.21.0
python3 tools/realstack/realstack.py run <row> --lss dir:<gate output>/jars
```

`--lss` chooses the mod under test: `published:<version>` downloads that version's jars for the
row's platforms from Modrinth; `dir:<path>` takes locally built jars (a release gate's `jars/`
directory). `run` exits 0 only when every check passed.

A run waits for the shared harness lock (up to 30 minutes), then has 15 minutes: it stages a fresh
offline server and a fresh client, starts the client on a private Xvfb display, lets it join,
watches `lsslod diag`, takes a screenshot, stops everything it started and writes the verdict.

Needs: Python 3.11+, JDK 21 and JDK 25, `Xvfb`, `xdotool`, `timeout`, 15 GiB free.
Settings: `LSS_JAVA_HOME_21`, `LSS_JAVA_HOME_25`, `LSS_REALSTACK_CACHE` (default
`~/.cache/lss-realstack`), `LSS_REALSTACK_RUNS` (default `~/.local/state/lss-realstack/runs`),
`LSS_REALSTACK_SEED_ROOTS` (optional folders with `libraries/` and `assets/objects/` to copy from
instead of downloading; a file is used only when its hash matches).

## Evidence

Each run has its own directory, `<runs>/<UTC time>-<row>/`:

- `verdict.json`: `passed`, and for every check its message and the log lines it relied on.
- `evidence/`: server console log, client log, the final `lsslod diag`, `client.png`, the staged
  jar list with hashes, the server's settings file, and what was left running (nothing). A
  scenario that changes the server's settings also keeps the file as the server generated it.
- `facts.json`: port, display, timings, command lines, the diag polls.

`realstack.py replay <run directory>` judges a saved run again from its evidence.
`realstack.py --stacks <file> ...` uses another matrix file (for trying a row before adding it).

## Checks

| Check | Passes when |
| --- | --- |
| `server_started` | the server logged `Done`, LSS logged that its service started, `lsslod diag` says `enabled=true`, and no ERROR record in the server log mentions LSS |
| `handshake` | the server logged the join, the handshake on the current protocol and the registration; the client logged the session config; the diag counts a live session |
| `columns_sent` | the server-wide and the per-player `sent=` counters in the final diag are above zero |
| `client_clean` | no crash report, no LSS ERROR in the client log, and the client stayed alive and connected for the whole observation window |
| `screenshot` | a picture of the private display was saved while a Minecraft window existed (the picture is not judged yet) |

A check passes on positive evidence, never on the absence of an error alone. To see the checks
fail, run a row with `--fault client-without-lss`: the client is staged without the LSS jar and the
handshake check must go red.

## Scenarios and the other brand

`run <row> --scenario <name>` stages a situation a player meets and judges what the client and the
server say about it. A scenario brings its own checks; the row's are not run. Every scenario also
runs `client_clean` and `screenshot`.

| Scenario | Stages | Checks |
| --- | --- | --- |
| `server-without-lss` | the server without LSS | `server_ready`, `client_joined`, `client_notice` (the "this server doesn't seem to have this mod" notice) |
| `off-in-world` | the server setting `lod.distance.by_dimension."minecraft:overworld": 0` | `server_started`, `server_settings`, `client_joined`, `handshake_off`, `no_columns`, `client_notice` (the "switched off in this world" notice) |

| Scenario check | Passes when |
| --- | --- |
| `server_ready` | the server logged `Done` |
| `client_joined` | the server logged the player's join during the observation window |
| `client_notice` | the client spoke its chat notice exactly once, with the build's short name in front and in exactly the expected words |
| `server_settings` | the saved settings file holds each value the scenario set, and the server logged that a reload applied a setting (and no line that says the setting was not applied) |
| `handshake_off` | the server logged the join and the handshake on the current protocol, and every session config the client logged says that protocol and `enabled: false` |
| `no_columns` | the final diag, taken at the end of the window with the player online, says `sent=0` server-wide, and for the player where the server lists the player |

Server settings: the settings file (`plugins/LodServerSupport/lss-server-config.yaml` on Paper,
`config/lss-server-config.yaml` elsewhere) exists only after the first start has created it. So
the tool starts the server once, changes the one line that holds the value (as text, so every
comment stays), sends `lsslod reload`, and lets the client join only after the server has
answered `Reloaded ...: N active setting(s) applied.` This is what the file's own header tells an
operator to do, it needs one start instead of two, and the server's answer is the proof that it
took the value. A key that is missing, commented out or a block, a reload that fails or changes
nothing, and a setting that needs a restart all end the run with the reason. On Paper a
world-name entry under `lod.distance.by_world` would win over the dimension entry, so
`off-in-world` also refuses to run when the generated file has one.

`--brand vss` takes the Voxy Server Side build of the same jars from a gate's `jars/` directory
(`--lss dir:` only). The server is then asked for `vsslod diag`, and the notice check expects
that build's short name as the prefix.

## Add a row

1. `realstack.py pin <modrinth project> --minecraft <version> --loader <loader>` prints the entry
   for the newest published file (project, version id, file name, sha512) and its dependencies.
   `realstack.py pin --neoforge-installer <version>` prints a NeoForge pin.
   `realstack.py pin --paper <minecraft version>` prints a Paper pin (build, file name, sha256)
   for the newest stable build, or for `--build <number>`.
2. Add the row to `stacks.json`: id, Minecraft version, server platform and client loader with
   their loader pins, the pinned mods of both sides, the LSS jar of each side and the checks.
3. `realstack.py run <row> --lss published:<current version>`. If it cannot be made to work, keep
   the row with `"status": "blocked"` and the exact reason in `blocked_reason`.

The client needs a terrain consumer (Voxy or a map mod); without one it requests no terrain and
`columns_sent` has nothing to witness.

Server platforms: `fabric`, `neoforge` and `paper`. A Paper row pins the server jar from the
PaperMC download API (`"paper": {"build", "file", "sha256"}`); the mod under test and the pinned
`mods` of the server side go into `plugins/`. The Paper jar unpacks itself in the run's server
directory on every start. It would download the game's server jar for that; the tool puts its own
verified copy where the Paper jar looks, when the hash is the one the Paper jar asks for. A Paper
console names no thread and prints `[LSS]` in front of the mod's lines; the checks read both forms.

## Rules the tool keeps

- Official sources only (Modrinth, Mojang, Fabric, NeoForge and PaperMC), every file hash-checked, a
  generic User-Agent, no account, token or credential of any kind. Clients start with
  `--accessToken 0` against an offline server.
- Private Xvfb display only, never `:0`; `WAYLAND_DISPLAY` and `XAUTHORITY` are removed.
- It signals only processes it started, found by name plus the run's unique `-Dlssrig.id=` tag.
- A red check is explained, not retried ([decision 0007](../../docs/decisions/0007-retry-rule.md)).
  Every attempt keeps its own directory.

Tests: `python3 -m unittest discover -s tools/realstack` (offline).
