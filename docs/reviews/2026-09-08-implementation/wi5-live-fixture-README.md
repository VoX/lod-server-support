# WI5 isolated positive-backlog reception fixture

External test-only MC 1.21.1 fixture; never a production deliverable. Installs one `Minecraft.tick()V` HEAD observer. It neither changes the Xaero bridge nor pauses its pump, invents queued work, sends fake protocol frames, or patches candidate classes. The only configured state mutation is the same receive option catalog setter and `SaveHook.run(CONFIG)` used by the Sodium menu Apply action.

Use the remapped Fabric jar only with Fabric. Use the separately packaged **neoforge-named** jar only with native NeoForge. Both depend on the exact MC 1.21.1 surface and LSS >=0.14.0; Java 21. Neo packaging follows the already validated WI9 lowcodefml/mixin-only layout. Do not install both artifacts or a WI9 fault jar for this gate.

No JVM flags are required. Installation alone records READY but performs no Apply. Marker paths resolve against `Minecraft.gameDirectory`, not an assumed launcher working directory. In the isolated Windows profiles this is:

- Fabric: `C:\Users\Ian\AppData\Roaming\PrismLauncher\instances\lss-astra-validation-fabric-1.21.1\minecraft\lss-wi5-arm-off`
- NeoForge: `C:\Users\Ian\AppData\Roaming\PrismLauncher\instances\lss-astra-validation-neoforge-1.21.1\minecraft\lss-wi5-arm-off`

The ON marker is `lss-wi5-arm-on` beside it. Confirm READY's actual markerRoot before creating either marker. Remove stale marker files **before launch**; an existing ON marker when OFF first arms is a fixture failure. Each launch executes at most one cycle. It writes its own durable `lss-wi5-fixture.log` beside the markers and duplicates all events into the game's SLF4J log.

## Procedure and required evidence

1. Root launches only the isolated observer, final candidate, actual Xaero, bridge enabled, reception ON, and the real fixture server. Establish a real session. Arrange ordinary fresh acquisition/backfill; do not freeze or alter bridge internals to manufacture the premise.
2. Create `lss-wi5-arm-off`. The fixture waits up to 180 wall-clock seconds for reception ON, real established config/manager, native level and connection, active Xaero, **queued > 0 and pending_updates > 0**. Timeout logs PRECONDITION_TIMEOUT and never toggles.
3. In that same client tick, PRECONDITION records all diagnostics and world/connection/sub-key identity, then the real menu Apply hook switches OFF. AFTER_OFF must show acquisition queue/owed zero, manager retired, and **the same positive native pending-update count** without dropped_updates rising. These checks run immediately before another client tick/frame can drain pending work.
4. With OFF retained, native frame/tick rebuild machinery runs normally. New bridge writes, resurfacing acquisition queue/debt, manager recreation, native world change or discarded pending updates fail the fixture. OFF_NATIVE_REBUILDS_DRAINED requires pending_updates zero after actual frame flushing. OFF_OBSERVE records continued OFF state. An indefinitely stalled pending set is not a pass; root should record its diagnostics and stop rather than repair it.
5. Create `lss-wi5-arm-on` when ready. It remains pending until the native retained rebuilds have drained. BEFORE_ON and AFTER_ON bracket the same catalog setter/SaveHook call. The hook must reset received-session-config immediately and subsequently receive a genuine server config with a fresh manager. The original level, network connection, Xaero bridge/world ID and cache sub-key must remain unchanged.
6. PASS_SAME_WORLD_OFF_ON additionally requires actual post-toggle received-column count and Xaero written count to exceed the pre-OFF baseline. A valid negotiation with no resumed native writes is not a pass. RESUME_TIMEOUT after 180 seconds is incomplete evidence. Preserve fixture log, game/server logs and candidate/fixture hashes.

Reflection is read-only and limited to Xaero's instance/lastWorldId and the manager's worldSubKeySnapshot. All bridge content/queue/rebuild/debt observations use existing production diagnostics. Failure only disarms the fixture; it never resets bridge state or automatically restores reception. Root can restore reception through the normal menu after preserving evidence.
