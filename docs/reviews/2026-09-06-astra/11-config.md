# MC 1.21.1 configuration, commands and user-facing lifecycle review

Reviewed worktree: `/home/vox/projects/lss-lines/1.21.1`, merged tree supplied as `1b544494`. Review only; no repository edits, build tasks or runtime/server calls. This is an independent review lens, not a repeat of the stopped persistence review. Priority uses P1 urgent, P2 normal correctness fix, P3 operational improvement.

## Finding

### CFG-1 — P3: runtime settings cannot report persistence failure to the command sender

**Locations:** `common/src/main/java/dev/vox/lss/common/config/JsonConfig.java:106-108`; `common/src/main/java/dev/vox/lss/common/config/RuntimeSettings.java:222-227`; `xplat/src/main/java/dev/vox/lss/networking/server/LSSServerCommands.java:104-122`; `paper/src/main/java/dev/vox/lss/paper/PaperCommands.java:107-122`.

**Real trigger:** after a config loads successfully, an admin runs a supported `/lsslod set` (or branded equivalent) while the config directory is unwritable, the disk cannot accept the temporary file, or the temporary-file path cannot be written. Runtime mutation and validation succeed, but writing or replacing the config fails.

**Impact:** the value does apply in memory, and the server error log records the failure. However, the command sender receives the same success reply as a persisted change; it gives the effective value and its application note without indicating that restart will restore the prior value. The command listing describes the keys as applied and persisted, and the runtime-settings plan promises apply plus persist. This is an operational reporting gap, not failed runtime application or corrupt-save behavior.

**Evidence:** `JsonConfig.save()` returns void and catches every ordinary save exception. `RuntimeSettings.applyAndPersist` therefore cannot distinguish success from failure and returns the effective value in both cases. Both command implementations only handle parse failure and then report normal success. The parent independently confirmed this source trace. No new executable probe was run for this lens.

**Fix:** expose a save outcome (or add a nonthrowing outcome-returning save method), carry it through the runtime-settings result, and reply “applied, but not saved” with an actionable log reference on failure. Preserve the applied runtime value. Preserve startup’s nonfatal behavior; do not globally make `save()` throw without auditing its existing callers.

**Regression:** seed a valid config in a temporary directory, obstruct its `<config>.tmp` path with a directory, run a supported setting change, and assert (1) live value changed, (2) original file remains intact, (3) command feedback explicitly reports failed persistence. Remove obstruction and repeat to verify successful persistence. The directory collision is deterministic even when tests run as root. Cover Fabric/shared result and Paper reply; keep the existing nonfatal-save test.

**Pinned-decision distinction:** `JsonConfigLoadTest.saveIntoUnwritableDirectoryIsNonfatalAndObjectStaysUsable` (line 501) explicitly pins logged, swallowed save errors, including direct post-load saves. This finding does not dispute that contract; it concerns carrying the already-detected outcome to an interactive runtime caller. It should not be treated as a release blocker.

## Boundary findings owned elsewhere

- **Runtime `farPlayers off`:** independently confirmed supported by `RuntimeSettings.java:158-167`, whose note promises next-broadcast application. The Fabric and Paper command paths only special-case `lodDistanceChunks`; their runtime reapplication methods contain no far-player disable transition. `RequestProcessingService.tickFarPlayers` at line 724 and Paper’s counterpart at line 1979 return before broadcasting while off. This corroborates the far-player reviewer’s retained-roster issue. Parent assigned ownership to report `07`; not duplicated as a new finding here.
- **Client `receiveServerLods` live toggle:** already confirmed in `04-client.md`. Catalog/save wiring intersects that defect but is not a separate issue in this report.

## Coverage and conclusions

Read the applicable CLAUDE guidance and prior-findings/pinned-decisions memory. Reviewed shared `JsonConfig`, server and client config defaults/validation, runtime setting registry, both server command mutation paths and tick reapplication, menu catalog and save hooks, modern Fabric/NeoForge Sodium page walkers, legacy reflected page construction/storage/save/dependency wiring, screen selection, rate slider mapping, reset coordinator and command binding, and diagnostics-facing persistence/default claims. Read or inspected relevant pins in JsonConfigLoadTest, ConfigValidationTest, RuntimeSettingsTest, PaperConfigLoadTest, PaperCommandsTest, LSSConfigMenuTest, ClientOptionCatalogTest, LegacySodiumPageTest and ResetCoordinatorTest, plus runtime-settings, Sodium-generations and Xaero plans.

No additional P1/P2 defect was established in this bounded lens. In particular:

- Runtime numeric/boolean parsing validates before the single assignment; finite-double parsing prevents command NaN/Infinity. Shared clamp helpers preserve 0=AUTO/disabled sentinels and cross-field generation caps. The two loaders use the shared registry.
- Tick reapplication covers global bandwidth, disk gate capacity, generation caps for existing players and sweep distance. Current-dialect distance changes re-push config; legacy reconnect behavior and AUTO timestamp-cache boot sizing are expressly documented limitations.
- Successful cross-brand config adoption saves back to the chosen file. Fresh-install store-on versus missing-key/corrupt-existing-file store-off is intentional and pinned. Hidden expert overrides survive re-save; default-valued hidden keys and migrated legacy bandwidth spellings drop intentionally.
- Modern and legacy menu adapters share option/default/binding definitions. Staged boolean dependencies and one-save-per-storage behavior have focused pins; far-player preferences have a dedicated save-and-push hook. NeoForge’s modern page uses the same catalog with its loader-specific metadata surface.
- Reset coordinator ordering is drain, Voxy reset, LSS flush, then far-player resubscribe. Destructive no-session forms and force grants have explicit path/connection/expiry guards, contained Voxy failures and outcome-specific feedback. No separate reset-command defect established.

## Dismissed candidates and deliberate tradeoffs

- Entire-file fallback for one malformed field, unknown-key removal on successful load, Gson’s boolean/numeric-string coercions, and preserving corrupt files at load are explicitly pinned. They are not new review findings.
- Nonfatal save errors are intentional, as detailed under CFG-1. Reporting runtime outcome is the limited improvement; startup must remain usable.
- Xaero-only client enabling the bridge after joining with it disabled does not establish an LOD session until rejoin. `XaeroMapCompat.java:675-679` explicitly documents this capability limitation (“when a stream exists”); do not duplicate the master toggle issue or demand unplanned handshake renegotiation here.
- Rate slider rounds display to its supported stops; arbitrary valid hand-edited rates remain supported by config. This is not by itself evidence of unwanted mutation.
- Dirty-push interval 0 still drains invalidations; re-enabling cannot replay already-drained dirty marks. This is documented behavior, not a configuration regression.
- Paper’s default event list intentionally omits high-frequency fluid flow and ChunkPopulateEvent; Paper’s resweep default and inert backfill warning are deliberate platform behavior.
- JSON save has a fixed sibling temporary filename. No supported concurrent same-object save path was established: client UI saves run on the client thread, server commands on the owning tick/pump, and startup load occurs before them. No speculative concurrency finding.

## Remaining validation limits

No live UI check on Sodium 0.6/0.8 and no additional commands were executed against the running server. Existing tests establish adapter contracts, not every installed jar’s live behavior. Full resource/thread lifecycle, client cache identity and Voxy reset internals belong to other review lenses. Cross-line release/artifact branding and toolchain differences are deferred to the next independent lens rather than inferred from this line’s banner. Operational command output may still name the brand-primary config filename even when an alternate filename was adopted; this is a minor discoverability issue, not lost adoption or lost data.
