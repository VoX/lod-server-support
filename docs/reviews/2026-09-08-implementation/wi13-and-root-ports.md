# Ports completed by operations agent

All picks used `git cherry-pick -x`. Source commits: WI13 `851479d7`; WI10 `8729d674`; WI11 `e438a633`; WI6 `f84f9f6d`.

| MC line | WI13 | WI10 | WI11 | WI6 |
|---|---|---|---|---|
| 1.21.10 | `390a70d7` | `f8d0398b` | `a0b3bbeb` | `9e49dcf7` |
| 1.21.11 | `8886a12f` | `61127067` | `5f7166af` | `96d25bf5` |
| 26.1 | `ff1975da` | `4bae1626` | `781cd067` | `11103640` |
| 26.2 | `20aa7c47` | `20253500` | `8974a7bf` | `d080f621` |

## Line adaptations

- All four lines retain `-x runClientGameTest` on their harness Fabric builds. The source 1.21.1 line intentionally has no such task, so accepting its complete command line would have weakened the other lines' benchmark build exclusions.
- Soak staging and base-save bodies preserve each target line's original world-layout behavior, including 1.21.x Bukkit split directories and the 26.x unified layout. The original target's base-save block moved after the checker verdict; it was not replaced wholesale by the 1.21.1 block. The lock/helper/manifest/fixture code is otherwise literal.
- 26.2 WI10 preserves its pre-existing per-world `ApplyResult.display()` / `repush()` semantics and `renderReplyValue` helper. It adds `persisted` alongside them, routes `applyAndPersist` through the outcome-returning method, and appends the persistence note in both existing command renderers. The per-world map/scalar change detection and existing grammar/round-trip tests remain intact. The incoming scalar failed-save fixture uses `display()` on this line. Other WI10 ports are literal apart from automatic surrounding context.
- WI11 and common WI6 changes port literally. Platform service contexts auto-merged around existing line adapters.

## Validation (serial, temporary fixtures only)

| MC line | Actual-script fixture suite | Checker selftest | Report selftest | Shell syntax |
|---|---|---|---|---|
| 1.21.10 | 21/21, 25.726 s | 274/274 | 20/20 | 14 files pass |
| 1.21.11 | 21/21, 26.028 s | 274/274 | 20/20 | 14 files pass |
| 26.1 | 21/21, 25.635 s | 274/274 | 20/20 | 14 files pass |
| 26.2 | 21/21, 25.492 s | 274/274 | 20/20 | 14 files pass |

Logs are `harness-port-<line>-{fixtures,checker,report}.log` beside this report. No Gradle, real benchmark, soak, Minecraft/server operation, or toolchain change was performed. Java compilation/test validation for WI6/10/11 remains under the parent's serialized build slot. Existing uncommitted wing, gametest, test-server, C2ME/build and documentation changes were not staged or modified. The server agent was told every target line was free after these commits, and began WI2 picks afterward; any subsequently visible WI2 merge state is outside these completed ports.
