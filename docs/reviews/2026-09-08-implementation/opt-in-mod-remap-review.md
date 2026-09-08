# Opt-in C2ME development remap review

Read-only source and cached Loom bytecode review; no source edits or Gradle invocation.

Verdict: replacing C2ME's `localRuntime` with `modLocalRuntime` on the three obfuscated 1.21.x lines is correctly scoped. Each declaration remains inside `benchmark.c2me=true`; the dependency coordinates are unchanged. On1.21.11 both named `benchmark` and `regular-map` profile entries flow through that declaration. No default dependency, include/storeDeps packaging edge, or publication configuration changes.

Cached Loom1.17.13 bytecode confirms the distinction: `LoomConfigurations` attaches ordinary `localRuntime` directly to runtimeClasspath, while `RemapConfigurations` registers the remapped localRuntime option with compile=false, runtime=true and `PublishingMode.NONE`. This supplies the development namespace conversion while retaining development-only/nonpublished scope. The build's explicit Fabric nested-jar inputs remain common plus the two native libraries; C2ME is not included. Existing final default-dependency artifact hashes remain applicable to their recorded builds.

The parent reproduced the regular-map C2ME failure before gametests: nested c2me-base access widener used intermediary while the dev environment expected named. This review independently found the raw C2ME0.4.0-alpha.0.18+1.21.11 cache artifact and the corresponding unchanged profile mapping; runtime reproduction belongs to the parent's log.

Other opt-in route with the same source shape: `benchmark.moonrise=true` still declares a production Fabric mod through plain localRuntime on1.21.1 (`fabric/build.gradle:102`),1.21.10 (`:118`) and1.21.11 (`:105`). It needs the same remap-aware configuration if its shipped classes/access widener use intermediary. No cached Moonrise jar was found in the relevant Gradle/cache/project/temp trees, so this is an identified remap-path risk rather than a claimed reproduced failure. The two native Java libraries correctly stay on ordinary localRuntime; Sodium golden configurations intentionally expose raw artifacts to zip/bytecode tests and should also stay unchanged. NeoForge does not use Fabric Loom. The26.x lines retain their unobfuscated runtime path as requested.

No new shipping or wire change was identified.

## Follow-up: explicit bundled module inputs

After the parent reproduced Loom stripping umbrella nested jars (whose Modrinth POM declares no module dependencies), reviewed the added detached transitive=false umbrella resolution and `modLocalRuntime files(zipTree(...).matching { include 'META-INF/jars/*.jar' })` input. This remains wholly opt-in and nonpublished, with no `include` or storeDeps edge and unchanged artifact coordinates.

Independently inspected two available original bundles in memory:

- MC1.21.1 C2ME0.4.0-alpha.0.27, `/home/vox/projects/lss-port-1.21.1/test-server/fabric/mods/c2me.jar`:28 first-level nested jars; all28 declared by outer fabric.mod.json; each has Fabric metadata; no deeper nested jars; access-widener header intermediary.
- MC1.21.11 regular-map C2ME0.4.0-alpha.0.18, cached Maven MfQIu1Y0 artifact:30 first-level nested jars; all30 declared; each has Fabric metadata; no deeper nested jars; access-widener header intermediary.

The one-level explicit module inputs cover both available actual bundles. The1.21.11 benchmark0.26 and1.21.10 bundle were not available in the inspected local cache, so those actual bundle layouts and complete runtime resolution remain run-gated. No further implementation blocker identified in the updated block; the parent's next loader/gametest execution is the required runtime evidence.

## Follow-up: preserve optional nested Java selection

Parent's third launch reproduced another development-flattening difference: the optional nested natives-math module declares Java>=25; exposing every nested module as a root runtime input makes it mandatory under the Java21 gate.

Metadata-aware exclusion is appropriate, with one implementation correction found by this independent review: **the0.18 opts-dfc module itself requires natives-math**, despite declaring no Java floor. Filtering only Java-incompatible modules leaves DFC mandatory and produces a missing-module error. Compute the transitive exclusion closure over optional nested modules whose required dependencies are excluded; fail if it intersects the root's required-module closure. Reject unrecognized Java predicates instead of guessing, use the declared gate runtime/toolchain Java21, and log excluded module ids and reasons.

Actual bundle graph results:

| Bundle | Java21 exclusions | Root-required nested closure | Retained modules |
| --- | --- | --- | --- |
| 1.21.1 /0.27 | c2me-opts-natives-math (Java>=25) | c2me-base |27/28 |
| 1.21.11 regular-map /0.18 | c2me-opts-natives-math (Java>=25), c2me-opts-dfc (requires natives-math) | c2me-base |28/30 |

This preserves the optional-module selection implied by the production bundle under Java21; it is not an LSS feature cut. Java25 execution would exercise a different module selection and would not replace the requested Java21 gate. No broad whitelist or silent pruning of unrelated loader conflicts is justified. Graph evidence was read from original jar metadata only, without runtime or jar mutation.

## Final shared helper review

Reviewed `gradle/c2me-dev-runtime.gradle` on all three obfuscated lines after the optional-dependency correction. The three files are byte-identical (SHA-256 `1ae2d02d48590f8d387b5a7b643cb383efef1fce77ef0c8331b1b29b48adb65a`), and each call passes the intended Java 21 runtime explicitly. No blocking source issue for the inspected pins.

The helper reads the outer metadata-declared jar list, rejects Java predicates other than the verified `>=N` shape, excludes Java-incompatible modules, and folds their required dependents to a fixed point. Checking root dependencies after that fold protects the complete root-required closure: a required ancestor of an excluded descendant will itself be excluded. Module IDs and reasons are logged. The detached input is non-transitive; final dependencies are only `modLocalRuntime`, with release `include`/store dependency paths unchanged. This preserves the intended optional-module selection for these pinned bundles under Java 21, rather than switching the gate to Java 25. General future Fabric predicate/version solving is intentionally not implemented; an unrecognized Java predicate fails explicitly.

Parent reports the 1.21.11 regular-map profile now launches the actual C2ME bundle on Java 21 and executes all 77 gametests (75 pass, two assertion failures under separate diagnosis). That verifies loader selection/remapping for this profile; it does not declare those two compatibility assertions resolved. Benchmark 0.26 and other pinned line bundles remain separate validation arms. Moonrise remains the separately recorded, unverified plain-localRuntime remapping risk.
