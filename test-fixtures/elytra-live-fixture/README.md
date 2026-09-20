# Modern Elytra observation fixture

Test-only Fabric fixture for Minecraft 1.21.10 and 1.21.11. It observes the completed `FarPlayerRenderer.submitProxy` call on those deferred-render pipelines; no renderer or entity state is changed. The required mixin fails startup if the exact submission seam is unavailable.

Build independently from a matching worktree with Java 21:

```
./gradlew -p test-fixtures/elytra-live-fixture remapJar \
  -PfixtureMinecraft=1.21.11 \
  -PlssFabricJar="$PWD/fabric/build/libs/lod-server-support-fabric.jar"
```

The remapped jar belongs in the private observer and target Fabric mod directories. Only the target launch sets `-Dlss.rig.elytraTarget=true`, registering an explicit test consumer and logging actual LSS column callbacks. The observer retains its selected real consumer. This consumer is not a terrain renderer and makes no terrain-rendering claim.

The observer emits `LSS_ELYTRA_SUBMIT` JSON records at ten observations per second per subject. Each records the exact private proxy class's UUID/entity ID, absence of the native entity in the client level, actual chest Elytra, crouch/fall-flight flags, pose, camera distance and submitted position. Submission is not proof of the later GPU draw; the private observer captures and required visual review remain separate gates.

Use the actual target's identity-verified private window for Shift and Space. Establish survival mode, actual equipped Elytra, a grounded standing state, then record equipped standing → crouched → standing recovery → falling → native fall-flight → landed recovery. Require native `FallFlying:1b` during gliding and `FallFlying:0b` plus `OnGround:1b` after landing. Never synthesize a player data flag. Keep the target beyond native tracking, within the animation range and within the observer's fog distance. Phase intervals must be ordered and non-overlapping; the checker rejects static gliding, visible native entities, and missing recovery.
