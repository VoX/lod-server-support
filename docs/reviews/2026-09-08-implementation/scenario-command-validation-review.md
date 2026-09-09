# Independent scenario command-validation review

Read-only Astra review alongside audit03_storage's implementation, 2026-09-08. **No blocking defect found in the narrowed current implementation.** No source edits, builds, launches or GUI actions were performed by this reviewer. Final source inspection matches the five committed revisions listed below. Agent03/root own actual execution evidence; focused five-line tests are now green, while the corrected live Folia run is in progress.

## Corrections incorporated during review

1. Generic strict success would falsely reject valid idempotent cleanup such as kill with no entities or forceload removal with nothing remaining. The final helper validates syntax and executable context for every command, preserves native dispatch, and labels generic results `validation=dispatch`. Only gamerule setters require successful callback plus exact readback. No blanket semantic-success claim is made for generic commands.
2. `Commands.validateParseResults` alone can accept a consumed non-executable prefix. `SoakCommandExecutor.java:35` additionally uses `ContextChain.tryFlatten(...).isEmpty()` before dispatch, closing incomplete forms such as bare gamerule/execute in.
3. Paper/Folia must verify the world it changed. `PaperSoakScenarioDriver.java:216` preserves the complete `execute in <dimension> run gamerule ...` prefix for setter and query, and bitwise `&=` executes every world's verification even after one fails. It cannot pass merely by querying the console's overworld.
4. Historical bad recordings already said `ok=true`. `check_soak.py:3782` now rejects gamerule rows lacking `validation=gamerule-readback`; its explicit legacy unknown-rule fixture prevents old dispatch-only breadcrumbs from becoming new semantic proof. This deliberately also marks earlier valid-name recordings without the new proof field unverified under the new checker.

## Routing and callback timing evidence

Inspected the **actual Folia26.2 build7 runtime jar**, not only Paper compile stubs:
`paper/build/run/folia-soak-server/versions/26.2/folia-26.2.jar`.
Saved bytecode: `folia-command-timing-javap.txt` and `folia-executecommand-javap.txt` beside this report.

`Commands.performPrefixedCommand` trims the optional slash, parses with its existing dispatcher/source, then calls performCommand. The helper preserves that same dispatcher and `performCommand` route; its recording source only replaces the console's empty result callback. It introduces no Bukkit dispatchCommand routing switch or direct command-node invocation that could bypass the registered plugin tree.

`Commands.executeCommandInContext` uses a ThreadLocal execution context. At an independent driver tick it constructs and drains a context before returning. If invoked *inside an already active command*, it queues into that context and returns before the nested command runs. Both current drivers call from their own tick callback (Fabric END_SERVER_TICK; Paper/Folia global-region task). The helper explicitly restricts itself to that boundary, and its new nested-context gametest proves missing callback is not semantic success. No blocking wait or cross-thread callback collection was added.

Folia TeleportCommand schedules entity-thread teleport work then returns its normal command result. A successful synchronous command callback therefore proves handler acceptance/completion, not eventual teleport completion. The narrowed generic dispatch status avoids overstating this; existing per-scenario position/dimension laws must continue proving effects. Generic command handler rejection can still be `dispatch`-accepted by design when parse is valid and native performCommand contains the failure, which is necessary for the existing idempotent cleanup policy. That is an explicit accepted limit, not a newly invented effect guarantee.

Strict gamerule helper `SoakCommandExecutor.java:48` maps boolean false/true to0/1, preserves integer values, requires the setter callback's success flag, then requires query success and exact expected value. It never equates result0 to failure. A missing callback or mismatched readback fails. Per-world queries keep the setter prefix by removing only its final value token. Current scenario commands are simple boolean/integer setters without trailing whitespace or nested executable tails; no broader arbitrary-command parser contract is claimed.

## Platform and line audit

Normalized helper source is byte-identical across both loaders and all five lines after replacing only the package declaration (SHA256 `bbe35767441938abccf7d1debda0e5bba5bb5dcf51c92e33c81a53fbb8d718ab`). The helpers stay inside existing dev-only benchmark/soak namespaces; release exclusions continue to apply.

Scenario inventory checked directly:

| Lines | Actual rule inventory |
|---|---|
|1.21.1|32 daylight false,32 fire false,32 mob spawning false,31 mob griefing false,32 randomTickSpeed0,4 spawnChunkRadius0, retaining legacy names.|
|1.21.10|The same five legacy rule names/counts, but **no spawnChunkRadius steps**: the actual engine already removed that rule and TicketType.START. The four obsolete steps were removed after the real command-tree regression caught them.|
|1.21.11,26.1,26.2|32 minecraft:advance_time false,32 minecraft:fire_spread_radius_around_player0,32 minecraft:spawn_mobs false,31 minecraft:mob_griefing false,32 minecraft:random_tick_speed0; removed spawn radius commands.|

Modern names and the false-fire→radius0 migration were established by agent03/root's actual registry/DFU inspection. The real1.21.10 command-tree run additionally caught its earlier removal of spawnChunkRadius; cached GameRules/TicketType inspection corroborated the absence. Only1.21.1 retains that rule. Final read-only inspection independently confirms these exact source inventories and preserves the distinction between three-line renaming and four-line spawn-rule removal. No nonexistent Folia1.21.1 support was inferred.

Folia save-all remains the existing narrowly acknowledged `folia-noop` branch, bypassing the absent upstream command. Other commands cannot use that mapping. The existing word-boundary mapping test remains relevant; full world saving is still the harness autosave/end-halt premise, not proof that save-all executed on Folia.

## Test/evidence quality

Reviewed the three new methods in the already-registered `CommandGameTests` class; verified entrypoint registration and presence of all three methods on all five lines:

- Reads each line's actual scenario JSON inventory, queries the real registered gamerule, executes the setter, verifies readback, and restores original value in finally before returning from that same test callback. This catches future command-name drift rather than pinning a duplicated list.
- Rejects invalid and incomplete commands; executes inside an actual nested command context to check the no-callback boundary.
- Uses actual registered command handlers to distinguish command failure and generic dispatch, and a successful zero-valued setter with deliberately wrong readback to prevent false proof.

Checker fixtures cover valid zero-valued rule success, explicit semantic failure, legacy unverified setup, failed generic validation, acknowledged idempotent cleanup and the Folia save mapping. The new validation field is in the event-schema allowlist. Command validation runs in the common checker path rather than only one named scenario.

These source-level tests meaningfully target the failure and its boundary. Their execution has not been independently rerun by this reviewer; the agent's recorded final five-line results are listed below. Fabric gametests do not by themselves prove Paper/Folia per-world mutation/callback behavior; the corrected genuine Folia run remains the integration gate and is currently in progress. No need to replace the native routing or create a general asynchronous command executor to address this bounded setup defect.

## Reporting limits

Current strict checker acceptance requires newly recorded gamerule semantic proof. Older recordings—including earlier1.21.1 runs whose names were valid—can retain their historical checker/version result, but must not be labeled new-proof recordings. A new-checker unverified result on those rows is not a product regression. The aborted unaccepted Folia run remains failed/incomplete evidence and cannot publish a passing base/manifest.

No blocker remains in the final inspected source. Focused compilation, registered command-tree tests and checker fixtures have passed per agent03's execution record. Live acceptance still requires the parent's actual corrected Folia run showing new gamerule-readback rows succeed for the intended worlds; no live result is inferred here.

## Final committed revisions and completed focused validation

Read-only inspection of current source/HEAD matched these exact commits and gamerule inventories:

| Line | Commit | Full real server gametests |
|---|---|---|
|1.21.1|`49c8c689fb3a21970fa0110597c0bee8574c3018`|78/78|
|1.21.10|`948b0bf09dd852d357176577d7ccacd589aadb7d`|80/80|
|1.21.11|`8ac799a901e7eb99cb9403d512b790a0b6576190`|80/80|
|26.1|`1e7c9a9d3507c3ba90ac511ba6b62995b8796ab2`|79/79|
|26.2|`98b67abc82283417ad288cbb0cca2f4fb3343425`|79/79|

Per the completed execution record `runtime/soak-command-fix.md`, every line also passed two Fabric executor parity/exclusion unit tests, four Paper soak unit tests,280 checker selftests and33 timeline validations. The26.2 informational report passed20 selftests. The final1.21.10 green follows two preserved, explained reds: its test assertion API seam, then the real removed spawnChunkRadius command. Those are corrections caught by the test, not flakes. Other lines passed their first focused attempt. The new checker also rejects the five actual archived false-green Folia gamerule rows without modifying that archive.

This amendment changes only this external review file. No build, runtime, index or repository mutation was performed by the reviewer. The corrected Folia runtime was still running at amendment time; that result must come from the parent's separate acceptance record.
