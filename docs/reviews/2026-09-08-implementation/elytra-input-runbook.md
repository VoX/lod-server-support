# WI15 owned-dummy Elytra input runbook

Prepared read-only on 2026-09-08. No launches or key events were performed for this preparation. Root owns runtime scheduling. Paths below use `E=/home/vox/.local/state/lss-review/20260908-implementation`.

## Ownership and display discovery

Use the `DUMMY_OWNER_PID=... LINE=... PORT=...` marker from the exact active dummy log. Walk `/proc/*/status` parent links to that owner, requiring the current UID throughout. Select the unique descendant whose command line contains the exact tokens `net.fabricmc.devlaunchinjector.Main` and `-Dfabric.dli.env=client`; inspect these tokens in memory, never print the whole command line. This excludes the Gradle launcher/daemon. Abort on no match or multiple matches.

Read **only** `DISPLAY` and `XAUTHORITY` from that game PID's `/proc/PID/environ`, retaining the values in shell variables without dumping the environment. The inherited DISPLAY on the Xvfb process itself is not authoritative. With the game's XAUTHORITY, enumerate windows using `xwininfo -display "$DUMMY_DISPLAY" -root -tree`, then check the candidate with `xprop -display "$DUMMY_DISPLAY" -id "$DUMMY_WINDOW" _NET_WM_PID WM_NAME`. Require its PID to match the selected game and its title to identify Minecraft. Export XAUTHORITY for these commands and the key helper; the helper does **not** discover it itself. Never read or print the authority cookie.

Read-only snapshot, not reusable identifiers: owner **2633873**, game PID **2634225**, display **:100**, window **0x200007**, XAUTHORITY path `/tmp/xvfb-run.jWnVjf/Xauthority`; source log `runtime/dummy-1211-neo-render.log`, line 1.21.1 / port 25566. Window metadata matched the game PID and title. XTEST is present (opcode 132); `libX11.so.6` and `libXtst.so.6` resolve. Re-derive everything after any restart.

`runtime/dummy-key.py` verifies ancestry, UID, explicit non-default display, window PID and Minecraft title before issuing XTest events. This is compatible with the current Xvfb setup. It cannot establish that the player is in-game rather than in a menu/chat screen, so verify that premise before testing. Do not use `ps ... args`, `pgrep -af`, or dump process environments.

## Later target launches — root only, one at a time

The existing launcher supports:

```bash
"$E/runtime/launch-dummy.sh" 1.21.10 25568
"$E/runtime/launch-dummy.sh" 1.21.11 25570
```

It acquires the harness lock, uses JDK 21 and Xvfb, joins `[::1]:PORT`, and explicitly blanks patrol/probes/actions. Current matching fixture properties bind IPv6 loopback on those ports. Blank patrol makes `SoakPatrol.install` return before replacing vanilla keyboard input. Both existing target `fabric/build/run/soak-client/options.txt` files bind jump to Space, sneak to left Shift, and set `toggleCrouch:false`. Recheck after any user configuration change. Matching isolated observer profiles now have a real Voxy consumer and reception enabled; see `../elytra-validation-fixtures.md`.

## Bounded real pose inputs

After re-deriving and setting `DUMMY_OWNER_PID`, `DUMMY_GAME_PID`, `DUMMY_DISPLAY`, `DUMMY_XAUTHORITY`, and `DUMMY_WINDOW`, this shell function calls the existing verified helper:

```bash
dummy_key() {
  env DISPLAY="$DUMMY_DISPLAY" XAUTHORITY="$DUMMY_XAUTHORITY" \
    python3 "$E/runtime/dummy-key.py" \
    --display "$DUMMY_DISPLAY" --window "$DUMMY_WINDOW" \
    --pid "$DUMMY_GAME_PID" --owner "$DUMMY_OWNER_PID" \
    --key "$1" --action "$2"
}
```

Only execute during the authorized live gate. `dummy_key Shift_L down` holds crouch; `dummy_key Shift_L up` returns to standing. `dummy_key space tap` sends a 150 ms press and releases. Arrange a shell `trap`/finally that releases both keys on completion or interruption, still using the ownership-checking helper. The helper's `down` action does not schedule automatic release. Do not continue input if the owning process/window changed.

1. Establish the disposable subject at a known safe grounded location, approximately 160 blocks from the observer, beyond native entity visibility and within the default 512-block animation range. Choose real fixture ground coordinates and a clear flight corridor; no unverified coordinate is prescribed. Confirm the observer is rendering an LSS proxy and that terrain fog does not obscure it.
2. In the fixture console: `item replace entity SoakPlayer armor.chest with minecraft:elytra`, then `gamemode survival SoakPlayer`. Release Shift and Space. Keep the subject grounded; record standing with equipped wings after several animation ticks.
3. Hold Shift for a bounded few seconds and capture the crouching wing posture. Release Shift, then capture standing recovery. Do not infer crouch from pitch alone. The server snapshot takes `isCrouching()` directly.
4. Prepare the observer before moving the subject into the air. Teleport the subject above the known safe corridor (e.g. 30–50 blocks above its ground), with yaw/pitch suitable for that corridor. While airborne and out of water, tap Space once. Native `Player.tryToStartFallFlying` requires `canGlide()` and sets the fall-flying flag; creative hover, a mount, or a grounded subject is not a valid premise.
5. Read `data get entity SoakPlayer FallFlying` from the console. Require **1b** for the gliding capture, then observe wing spread over several animation ticks while within animation range. A high-altitude/falling/pitched player with **0b** is not a successful glide premise; check equipment, airborne state, window focus and key state, then retry from safe ground.
6. Land or return to the known safe ground and wait for the actual landing state. Require `FallFlying` **0b** and record standing wing recovery. Release both keys during cleanup. Do not assume teleporting instantly clears flight or establishes OnGround.

## Native evidence and gate limits

Cached named Minecraft bytecode for **both 1.21.10 and 1.21.11** confirms `LivingEntity` writes `FallFlying` using `isFallFlying()` and reads it into shared flag 7. `EntityDataAccessor.getData` supports inspection; its `setData` immediately rejects Player instances. Therefore `data get entity SoakPlayer FallFlying` is a valid native readback; `/data merge entity ... {FallFlying:1b}` is not an available player-control route and would not test the real transition anyway. Shared `FabricFarPlayerSnapshots` derives glide from `isFallFlying()` and sneak from `isCrouching()`.

The available checks establish input capability and a valid recipe, **not** a completed visual gate. Each line still requires actual observed equipped-Elytra standing → crouch → standing and standing → glide → landed-standing behavior on its final candidate, with native gliding readback, proxy provenance and captures recorded by the runtime owner.
